import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USER = {"sub": "bench", "email": "bench@example.com", "name": "Bench User",
        "given_name": "Bench", "family_name": "User", "picture": "https://example.com/bench.png",
        "email_verified": True, "hd": "example.com"}


class Google(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.reply(USER)

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.reply({"access_token": "bench", "token_type": "Bearer", "expires_in": 3600, "scope": "openid email profile"})

    def reply(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("", 9000), Google).serve_forever()
