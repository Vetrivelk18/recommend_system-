"""
Tiny local HTTP service wrapping fastembed, so Java can get a 384-d embedding for
search-box text without running Python itself.

    python3 embed_service.py [--port 8001]

Exists because Java cannot run BGE directly - fastembed is Python/onnxruntime, the
same reason the two-tower model needed a Python export rather than running in Java.
Unlike the user tower, a search query cannot be precomputed: it is arbitrary text
typed at request time, so this has to run live rather than as a batch job.

No framework (Flask/FastAPI) - one endpoint, stdlib http.server is enough and keeps
this as dependency-free as fastembed already is. The model loads once at startup and
is shared by every request; loading per-request would dominate the response time.
"""

import argparse
import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from fastembed import TextEmbedding

MODEL_NAME = "BAAI/bge-small-en-v1.5"
EXPECTED_DIM = 384

model = None  # set once in main(); every request reuses it

# Shared secret the caller must present. Empty disables the check, which is right for a
# laptop and wrong anywhere reachable: without it this is a free public embedding API that
# anyone can point load at, on a free tier with a CPU quota.
API_KEY = os.environ.get("EMBED_API_KEY", "")


class Handler(BaseHTTPRequestHandler):
    def _authorised(self):
        """compare_digest, not ==, so a wrong key cannot be recovered by timing."""
        if not API_KEY:
            return True
        return hmac.compare_digest(self.headers.get("X-Embed-Key", ""), API_KEY)

    def do_GET(self):
        """
        Health check. Render polls the service over HTTP to decide whether a deploy came
        up, and before this existed there was no do_GET at all - every probe got
        "501 Unsupported method" and the deploy would be marked failed.

        Deliberately unauthenticated and deliberately not loading anything: it answers
        only once the model is in memory, because main() does not bind the socket until
        the startup probe has passed.
        """
        if self.path in ("/health", "/"):
            self._json(200, {"status": "ok", "model": MODEL_NAME, "dim": EXPECTED_DIM})
        else:
            self._json(404, {"error": "not found"})

    def _read_body(self):
        """
        Java's RestClient (SimpleClientHttpRequestFactory, i.e. HttpURLConnection)
        streams POST bodies chunked rather than sending Content-Length, so reading
        exactly Content-Length bytes silently reads zero and the request looks
        empty - which is what broke the very first call from Java. http.server does
        not decode chunked request bodies itself, so it is done here.
        """
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()  # trailing CRLF after the terminating chunk
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)  # CRLF after each chunk's data
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        if self.path != "/embed":
            self._json(404, {"error": "not found"})
            return

        if not self._authorised():
            self._json(401, {"error": "bad or missing X-Embed-Key"})
            return

        raw = self._read_body()
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self._json(400, {"error": "invalid JSON body"})
            return

        text = (body.get("text") or "").strip()
        if not text:
            self._json(400, {"error": "text is required"})
            return

        # query_embed and embed are byte-identical for this model in this fastembed
        # version (verified 2026-09-01) - kept as query_embed so intent stays correct
        # if a future version starts applying BGE's query instruction prefix.
        vector = next(iter(model.query_embed([text])))
        self._json(200, {"vector": [float(v) for v in vector]})

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # default logs every request to stderr - too noisy for a hot path


def main():
    global model
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    # Render (and most platforms) assign a port and inject it as $PORT; binding anything
    # else means the service starts, passes nothing, and is marked unhealthy. The flag
    # still wins when given, so local runs are unchanged.
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8001)))
    args = parser.parse_args()

    print(f"auth: {'required' if API_KEY else 'DISABLED (no EMBED_API_KEY set)'}", flush=True)
    print(f"loading {MODEL_NAME}...", flush=True)
    model = TextEmbedding(model_name=MODEL_NAME)

    probe = next(iter(model.query_embed(["startup check"])))
    if len(probe) != EXPECTED_DIM:
        raise SystemExit(f"expected {EXPECTED_DIM}-d, got {len(probe)}-d")

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"listening on :{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
