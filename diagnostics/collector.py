"""Private, authenticated diagnostic events. No account data or free-form logs."""
import hmac
import json
import os
import re
import sqlite3
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

RETENTION_SECONDS = 7 * 86400
MAX_EVENTS = 20000
TEXT = re.compile(r"[A-Za-z0-9_.:-]{1,100}\Z")
REASONS = {"timeout", "cancelled", "invalid_state", "invalid_payload", "http_error",
           "network_error", "signing_error", "browser_error", "unexpected_error", "validation"}
FIELDS = {"stage", "reason", "exception_class", "method", "address_family", "operation",
          "tv_error_code", "http_status", "duration_ms", "android_sdk", "app_version"}


def validate_event(event):
    if not isinstance(event, dict) or set(event) - {"event", "session_id", "level", "details"}:
        raise ValueError("Unexpected event fields")
    for key in ("event", "session_id"):
        if not isinstance(event.get(key), str) or not TEXT.fullmatch(event[key]):
            raise ValueError("Invalid event identifier")
    if event.get("level", "info") not in {"info", "warning", "error"}:
        raise ValueError("Invalid level")
    details = event.get("details", {})
    if not isinstance(details, dict) or set(details) - FIELDS:
        raise ValueError("Unexpected detail fields")
    for key, value in details.items():
        if key in {"http_status", "duration_ms", "android_sdk"}:
            if type(value) is not int or not 0 <= value <= 86400000:
                raise ValueError("Invalid numeric detail")
        elif not isinstance(value, str) or not TEXT.fullmatch(value):
            raise ValueError("Invalid detail")
    if "reason" in details and details["reason"] not in REASONS:
        raise ValueError("Unknown reason")
    return {"event": event["event"], "session_id": event["session_id"],
            "level": event.get("level", "info"), "details": details}


class Store:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, received_at INTEGER NOT NULL, payload TEXT NOT NULL)")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=3)
        try:
            with db:
                yield db
        finally:
            db.close()

    def append(self, events):
        now = int(time.time())
        with self.connect() as db:
            db.executemany("INSERT INTO events(received_at,payload) VALUES (?,?)",
                           [(now, json.dumps(e, separators=(",", ":"))) for e in events])
            db.execute("DELETE FROM events WHERE received_at < ?", (now - RETENTION_SECONDS,))
            db.execute("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ?)", (MAX_EVENTS,))

    def read(self, limit=100):
        with self.connect() as db:
            rows = db.execute("SELECT id,received_at,payload FROM events WHERE received_at>=? ORDER BY id DESC LIMIT ?",
                              (int(time.time()) - RETENTION_SECONDS, limit)).fetchall()
        return [{"id": row[0], "received_at": row[1], **json.loads(row[2])} for row in rows]


def handler(store, write_key, read_key):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *_):
            pass  # Never log headers, query strings, request bodies, or keys.

        def respond(self, status, value):
            data = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)

        def authorized(self, key):
            return hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + key).encode())

        def do_GET(self):
            path = urlparse(self.path)
            if path.path == "/health":
                self.respond(200, {"status": "ok", "service": "tizen-pocket-diagnostics"})
            elif path.path == "/v1/events":
                if not self.authorized(read_key):
                    self.respond(401, {"error": "unauthorized"})
                    return
                try:
                    limit = max(1, min(500, int(parse_qs(path.query).get("limit", ["100"])[0])))
                    self.respond(200, {"events": store.read(limit)})
                except (ValueError, sqlite3.Error):
                    self.respond(400, {"error": "invalid_query"})
            else:
                self.respond(404, {"error": "not_found"})

        def do_POST(self):
            if urlparse(self.path).path != "/v1/events":
                self.respond(404, {"error": "not_found"})
                return
            if not self.authorized(write_key):
                self.respond(401, {"error": "unauthorized"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 1 <= size <= 65536 or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    raise ValueError("Invalid body")
                payload = json.loads(self.rfile.read(size))
                if not isinstance(payload, dict) or set(payload) != {"events"}:
                    raise ValueError("Invalid envelope")
                events = payload["events"]
                if not isinstance(events, list) or not 1 <= len(events) <= 64:
                    raise ValueError("Invalid batch size")
                safe = [validate_event(event) for event in events]
                store.append(safe)
                self.respond(202, {"accepted": len(safe)})
            except (ValueError, UnicodeError, TimeoutError):
                self.respond(400, {"error": "invalid_events"})
            except sqlite3.Error:
                self.respond(503, {"error": "storage_unavailable"})

    return Handler


if __name__ == "__main__":
    write_key = os.environ["DIAGNOSTIC_WRITE_KEY"]
    read_key = os.environ["DIAGNOSTIC_READ_KEY"]
    if min(len(write_key), len(read_key)) < 32 or write_key == read_key:
        raise ValueError("Configure distinct, strong read and write keys")
    state = Path(os.environ.get("STATE_DIRECTORY", "/var/lib/tizen-pocket-diagnostics"))
    state.mkdir(parents=True, exist_ok=True)
    store = Store(state / "events.sqlite3")
    server = ThreadingHTTPServer(("127.0.0.1", 8794), handler(store, write_key, read_key))
    server.daemon_threads = True
    server.serve_forever()
