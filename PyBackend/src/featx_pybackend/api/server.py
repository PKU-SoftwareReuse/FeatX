"""Standard-library-only HTTP shell. Business operations deliberately fail closed."""
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

MAX_BODY_BYTES = 1024 * 1024


def is_business_get(path: str) -> bool:
    parts = path.strip('/').split('/')
    return (parts[:2] == ['v1', 'analyses'] and len(parts) in (3, 4)
            and bool(parts[2]) and (len(parts) == 3 or parts[3] == 'result'))


class RepoSummaryHandler(BaseHTTPRequestHandler):
    server_version = "FeatXRepoSummary/1-stub"
    protocol_version = "HTTP/1.1"
    timeout = 30

    def do_GET(self) -> None:  # noqa: N802
        path = urlsplit(self.path).path
        if path == "/health":
            self._respond(200, {"status": "ready", "version": 1, "mode": "stub",
                                "businessReady": False, "models": {}})
        elif is_business_get(path):
            self._not_implemented(path)
        elif path == '/v1/analyses':
            self._respond(405, {"error": "Method not allowed", "allow": "POST"})
        else:
            self._respond(404, {"error": "Unknown path", "path": path})

    def do_POST(self) -> None:  # noqa: N802
        path = urlsplit(self.path).path
        if path != '/v1/analyses':
            status = 405 if path == "/health" or is_business_get(path) else 404
            self._respond(status, {"error": "Method not allowed" if status == 405
                                   else "Unknown path", "path": path})
            return
        # Validate transport only. Never inspect paths, models, repositories or code.
        if self.headers.get("Transfer-Encoding"):
            self._respond(400, {"error": "Transfer-Encoding is not supported; use Content-Length"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0:
                raise ValueError("negative length")
        except ValueError:
            self._respond(400, {"error": "Invalid Content-Length"})
            return
        if length > MAX_BODY_BYTES:
            self._respond(413, {"error": "JSON body exceeds 1 MiB"})
            return
        try:
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("Incomplete request body")
            request = json.loads(raw.decode("utf-8")) if raw.strip() else {}
            if not isinstance(request, dict):
                raise ValueError("JSON request must be an object")
        except (ValueError, UnicodeError, OSError):
            self._respond(400, {"error": "Expected a complete UTF-8 JSON object"})
            return
        self._not_implemented(path)

    def _not_implemented(self, path: str) -> None:
        self._respond(501, {"error": {"code": "NOT_IMPLEMENTED",
                                      "message": "RepoSummary business logic is not implemented"}})

    def _respond(self, status: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Connection", "close")
            if status == 405:
                self.send_header("Allow", payload.get("allow", "GET"))
            self.end_headers()
            self.wfile.write(raw)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


def main() -> int:
    host = os.getenv("REPOSUMMARY_HTTP_HOST", "0.0.0.0")
    port = int(os.getenv("REPOSUMMARY_HTTP_PORT", "8091"))
    with ThreadingHTTPServer((host, port), RepoSummaryHandler) as server:
        print(f"[reposummary-http] STUB listening on {host}:{port}; business logic disabled", flush=True)
        try:
            server.serve_forever(poll_interval=0.25)
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
