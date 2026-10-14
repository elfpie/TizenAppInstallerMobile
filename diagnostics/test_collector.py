import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from collector import Store, handler


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / "events.sqlite3")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler(self.store, "write-key", "read-key"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port) + "/v1/events"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, key, events=None):
        data = None if events is None else json.dumps({"events": events}).encode()
        req = urllib.request.Request(self.url, data=data, headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_write_key_cannot_read_and_read_key_cannot_write(self):
        event = {"event": "samsung.login.waiting", "session_id": "test-session"}
        self.assertEqual(401, self.request("read-key", [event])[0])
        self.assertEqual(202, self.request("write-key", [event])[0])
        self.assertEqual(401, self.request("write-key")[0])
        status, result = self.request("read-key")
        self.assertEqual(200, status)
        self.assertEqual("samsung.login.waiting", result["events"][0]["event"])

    def test_sensitive_payload_is_rejected_atomically(self):
        good = {"event": "samsung.login.waiting", "session_id": "test-session"}
        bad = {**good, "details": {"access_token": "should-never-be-stored"}}
        self.assertEqual(400, self.request("write-key", [good, bad])[0])
        self.assertEqual([], self.store.read())

    def test_retention_removes_old_records(self):
        good = {"event": "samsung.login.waiting", "session_id": "test-session"}
        with self.store.connect() as db:
            db.execute("INSERT INTO events(received_at,payload) VALUES (?,?)", (0, json.dumps(good)))
        self.store.append([good])
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM events").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
