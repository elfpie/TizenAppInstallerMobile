#!/usr/bin/env python3
"""CI-only Samsung fixture on the emulator host. Never installs any apps."""
import http.server
import json
import socketserver
import struct
import threading

metadata_enabled = True


class Metadata(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if metadata_enabled else 503)
        self.end_headers()
        self.wfile.write(json.dumps({"device": {"OS": "Tizen", "type": "Samsung SmartTV",
            "name": "CI Samsung TV", "modelName": "CI-TV", "developerMode": "1"}}).encode())

    def do_POST(self):
        global metadata_enabled
        metadata_enabled = self.path == "/metadata/on"
        self.send_response(200)
        self.end_headers()

    def log_message(self, *_):
        pass


def read_exact(stream, count):
    data = bytearray()
    while len(data) < count:
        chunk = stream.read(count - len(data))
        if not chunk:
            raise EOFError()
        data.extend(chunk)
    return bytes(data)


def receive(stream):
    command, arg0, arg1, length, checksum, magic = struct.unpack("<6I", read_exact(stream, 24))
    assert magic == command ^ 0xffffffff
    payload = read_exact(stream, length)
    assert checksum == sum(payload)
    return struct.pack("<I", command).decode(), arg0, arg1, payload


def send(stream, command, arg0, arg1, payload=b""):
    code = struct.unpack("<I", command.encode())[0]
    stream.write(struct.pack("<6I", code, arg0, arg1, len(payload), sum(payload), code ^ 0xffffffff) + payload)
    stream.flush()


class Sdb(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(20)
        try:
            assert receive(self.rfile)[0] == "CNXN"
            send(self.wfile, "CNXN", 0x02000000, 262044, b"device::CI-Samsung;\0")
            remote = 4
            while True:
                command, local, _, service = receive(self.rfile)
                if command == "CLSE":
                    continue
                assert command == "OPEN", command
                service = service.rstrip(b"\0").decode()
                if service == "capability:":
                    text = b"platform_version:9.0\nprofile_name:tv\nsdb_version:2.2.31\n"
                    payload = struct.pack("<H", len(text)) + text
                elif service == "shell:0 getduid":
                    payload = b"CI-FAKE-TV-DUID-12345\n"
                elif service == "shell:0 vd_applist":
                    payload = b"app_tizen_id = org.jellyfin.test\napp_title = Jellyfin\napp_version = 0.1.0\n----\napp_tizen_id = org.tizenbrew.test\napp_title = TizenBrew\napp_version = 2.0.5\n"
                else:
                    raise AssertionError("Unexpected/mutating service: " + service)
                send(self.wfile, "OKAY", remote, local)
                send(self.wfile, "WRTE", remote, local, payload)
                ack, ack_local, ack_remote, _ = receive(self.rfile)
                assert (ack, ack_local, ack_remote) == ("OKAY", local, remote)
                # Observed on the real TV: CLSE remote ID is zero.
                send(self.wfile, "CLSE", 0, local)
                remote += 1
        except (EOFError, ConnectionError, TimeoutError):
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    for port, handler in [(8001, Metadata), (8002, Metadata), (26101, Sdb)]:
        server = Server(("0.0.0.0", port), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("Fake TV ready: HTTP 8001, control 8002, SDB 26101", flush=True)
    threading.Event().wait()
