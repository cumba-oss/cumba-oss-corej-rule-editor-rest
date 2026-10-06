#!/usr/bin/env python3
"""A fake Gitea for src/test/smoke.sh.

Two halves, one port:

* the forge API the login probe reads (GiteaClient): ``GET /api/v1/user`` and
  ``GET /api/v1/repos/acme/rules``, authenticated with ``Authorization: token <pat>``;
* the fixture repository over git's smart HTTP, ``/acme/rules.git/...``, delegated to
  ``git http-backend`` as a CGI — so the service's JGit clone, fetch and push, and the
  receive-pack advertisement probe (basic auth, ``info/refs?service=git-receive-pack``),
  all run against a real repository through the real transport.

Usage: smoke-forge.py <repo-root> <port> <token>

``<repo-root>`` holds ``acme/rules.git`` (a bare repository with ``http.receivepack`` on).
Unauthenticated requests get 401 with a Basic challenge, as a private Gitea repository
answers. No request is served outside the two halves.
"""

import base64
import json
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT, PORT, TOKEN = sys.argv[1], int(sys.argv[2]), sys.argv[3]

USER = {"id": 4711, "login": "smoke", "full_name": "", "email": "smoke@example.org"}
REPO = {
    "full_name": "acme/rules",
    "default_branch": "main",
    "private": True,
    "permissions": {"admin": False, "push": True, "pull": True},
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # JGit sends Expect: 100-continue; the base class answers it

    def log_message(self, fmt, *args):  # quiet: the service log is the one that matters
        sys.stderr.write("forge: " + (fmt % args) + "\n")

    def _authenticated(self):
        auth = self.headers.get("Authorization", "")
        if auth == "token " + TOKEN:
            return True
        if auth.startswith("Basic "):
            try:
                _, password = base64.b64decode(auth[6:]).decode().split(":", 1)
            except (ValueError, UnicodeDecodeError):
                return False
            return password == TOKEN
        return False

    def _json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _route(self):
        path, _, query = self.path.partition("?")
        if path.startswith("/acme/rules.git/"):
            return self._git(path, query)
        if not self._authenticated():
            # Gitea answers an unauthenticated API call with 401 — and an unknown token
            # equally; the probe treats both as "refused".
            return self._json(401, {"message": "token does not exist"})
        if path == "/api/v1/user":
            return self._json(200, USER)
        if path == "/api/v1/repos/acme/rules":
            return self._json(200, REPO)
        return self._json(404, {"message": "not found"})

    do_GET = _route
    do_POST = _route

    def _git(self, path, query):
        if not self._authenticated():
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="forge"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        env = dict(
            os.environ,
            GIT_PROJECT_ROOT=ROOT,
            GIT_HTTP_EXPORT_ALL="1",
            PATH_INFO=path,
            QUERY_STRING=query,
            REQUEST_METHOD=self.command,
            REMOTE_USER="smoke",
            REMOTE_ADDR="127.0.0.1",
            CONTENT_TYPE=self.headers.get("Content-Type", ""),
            CONTENT_LENGTH=str(length),
            HTTP_CONTENT_ENCODING=self.headers.get("Content-Encoding", ""),
        )
        result = subprocess.run(["git", "http-backend"], input=body, env=env, capture_output=True)
        head, _, data = result.stdout.partition(b"\r\n\r\n")
        status = 200
        headers = []
        for line in head.decode("latin-1").split("\r\n"):
            if line.lower().startswith("status:"):
                status = int(line.split()[1])
            elif ":" in line:
                name, value = line.split(":", 1)
                headers.append((name.strip(), value.strip()))
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("forge: listening on 127.0.0.1:%d" % PORT, flush=True)
    server.serve_forever()
