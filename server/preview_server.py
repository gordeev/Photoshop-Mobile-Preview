#!/usr/bin/env python3
"""Mobile Preview relay: Photoshop plugin -> this server -> phone browser.

Run it on the computer with Photoshop, then open the printed address on the phone
(same Wi-Fi). No dependencies beyond Python 3.
"""
import base64
import os
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8765"))
LONG_POLL_SECONDS = 25
MAX_BODY = 80 * 1024 * 1024
HERE = os.path.dirname(os.path.abspath(__file__))

with open(os.path.join(HERE, "viewer.html"), "rb") as f:
    VIEWER_HTML = f.read()
with open(os.path.join(HERE, "cutouts.json"), "rb") as f:
    CUTOUTS_JSON = f.read()

frame = {"version": 0, "jpeg": b""}
frame_changed = threading.Condition()


def lan_addresses():
    addrs = set()
    # Ask the OS which local address it would use for each route: the LAN one,
    # and Tailscale's (100.100.100.100 is its built-in DNS, routed via the tailnet).
    for probe in ("10.255.255.255", "100.100.100.100"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((probe, 1))
            addrs.add(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addrs.add(info[4][0])
    except OSError:
        pass
    return sorted(a for a in addrs if not a.startswith("127."))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send_body(self, status, body, content_type, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        if self.path.split("?")[0] != "/frame":
            return self.send_body(404, b"not found", "text/plain")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            self.close_connection = True
            return self.send_body(413 if length > MAX_BODY else 400, b"bad length", "text/plain")
        self.connection.settimeout(15)
        data = self.rfile.read(length)
        if len(data) != length:
            self.close_connection = True
            return self.send_body(400, b"incomplete image", "text/plain")
        if not data.startswith(b"\xff\xd8"):  # not raw JPEG -> base64 text from the plugin
            text = data.strip()
            if b"," in text[:100]:
                text = text.split(b",", 1)[1]
            try:
                data = base64.b64decode(text, validate=True)
            except ValueError:
                return self.send_body(400, b"bad image", "text/plain")
        if not data.startswith(b"\xff\xd8"):
            return self.send_body(400, b"bad image", "text/plain")
        with frame_changed:
            frame["version"] += 1
            frame["jpeg"] = data
            frame_changed.notify_all()
        print(f"  frame #{frame['version']}  {len(data) // 1024} KB", flush=True)
        self.send_body(200, b"ok", "text/plain")

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path in ("/", "/index.html"):
            return self.send_body(200, VIEWER_HTML, "text/html; charset=utf-8")
        if path == "/cutouts.json":
            return self.send_body(200, CUTOUTS_JSON, "application/json; charset=utf-8")
        if path == "/frame":
            # Long poll: wait until there's a frame newer than ?v=N.
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            try:
                seen = int(params.get("v", "0"))
            except ValueError:
                seen = 0
            with frame_changed:
                frame_changed.wait_for(lambda: frame["version"] > seen, timeout=LONG_POLL_SECONDS)
                version, jpeg = frame["version"], frame["jpeg"]
            if version <= seen or not jpeg:
                return self.send_body(204, b"", "text/plain", {"X-Version": str(version)})
            return self.send_body(200, jpeg, "image/jpeg", {
                "X-Version": str(version),
                "Access-Control-Expose-Headers": "X-Version",
            })
        self.send_body(404, b"not found", "text/plain")


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print("\n  Mobile Preview server is running\n")
    print(f"  Photoshop plugin address:  localhost:{PORT}\n")
    addrs = lan_addresses()
    if addrs:
        print("  Open in the phone browser (same Wi-Fi network):")
        for a in addrs:
            print(f"      http://{a}:{PORT}")
    else:
        print("  No LAN address found. Check the Wi-Fi connection.")
    print("\n  Close this window to stop the server.\n", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
