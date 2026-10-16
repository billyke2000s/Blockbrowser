#!/usr/bin/env python3
"""Local test website for scripts/stream-download-check.sh.

Serves the HLS test streams made by scripts/make-test-streams.sh and records every request, so the test can
check what the browser fetched and which cookies it sent.

  /login                 sets a cookie (bbsession=normal), like signing in to a video site
  /watch?s=NAME&t=TITLE  a page titled TITLE whose script fetches stream NAME's playlist, the way a web player
                         would (NAME is ts, fmp4, single, enc or live; prefix p/ to serve it under /p/ for private-window tests)
  /streams/...           the stream files (Range requests supported)
  /p/streams/...         the same files under another path, for the private-window test

Results are written as JSON to the second argument: {"requests": [{"path": ..., "cookie": ..., "range": ...}]}.
Usage: stream-server.py PORT RESULTS_JSON STREAMS_DIR
"""
import html
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1])
RESULTS = sys.argv[2]
STREAMS = os.path.realpath(sys.argv[3])
lock = threading.Lock()
state = {"requests": []}
PLAYLISTS = {"ts": "ts/master.m3u8", "fmp4": "fmp4/master.m3u8", "single": "single/index.m3u8", "enc": "enc/index.m3u8", "live": "live/index.m3u8"}
TYPES = {".m3u8": "application/vnd.apple.mpegurl", ".ts": "video/mp2t", ".m4s": "video/iso.segment", ".mp4": "video/mp4", ".bin": "application/octet-stream"}


def save():
    with open(RESULTS, "w") as f:
        json.dump(state, f, indent=1)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("server: " + (fmt % args) + "\n")

    def body(self, code, data, ctype="text/html; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        with lock:
            state["requests"].append({"path": url.path, "cookie": self.headers.get("Cookie", ""),
                                      "range": self.headers.get("Range", ""), "agent": self.headers.get("User-Agent", "")})
            save()
        if url.path == "/login":
            self.body(200, b"<!doctype html><title>Signed in</title><h1>Signed in</h1>",
                      extra={"Set-Cookie": "bbsession=normal; Path=/; Max-Age=86400"})
        elif url.path == "/watch":
            name = q.get("s", "ts")
            prefix = "/p" if name.startswith("p/") else ""
            playlist = prefix + "/streams/" + PLAYLISTS.get(name.split("/")[-1], PLAYLISTS["ts"])
            title = html.escape(q.get("t", "Stream test"))
            page = ("<!doctype html><html><head><meta name=viewport content='width=device-width'><title>%s</title></head>"
                    "<body><h1>%s</h1><p>A web video player loads this stream with a script.</p>"
                    "<script>fetch(%s).then(function(r){return r.text()}).then(function(t){document.body.insertAdjacentHTML('beforeend','<p>playlist loaded</p>')})</script>"
                    "</body></html>" % (title, title, json.dumps(playlist))).encode()
            self.body(200, page)
        elif url.path.startswith("/streams/") or url.path.startswith("/p/streams/"):
            rel = url.path.split("/streams/", 1)[1]
            path = os.path.realpath(os.path.join(STREAMS, rel))
            if not path.startswith(STREAMS + os.sep) or not os.path.isfile(path):
                self.body(404, b"not found", "text/plain")
                return
            with open(path, "rb") as f:
                data = f.read()
            ctype = TYPES.get(os.path.splitext(path)[1], "application/octet-stream")
            rng = self.headers.get("Range", "")
            if rng.startswith("bytes="):
                start, _, end = rng[6:].partition("-")
                start = int(start)
                end = int(end) if end else len(data) - 1
                part = data[start:end + 1]
                self.body(206, part, ctype, {"Content-Range": "bytes %d-%d/%d" % (start, start + len(part) - 1, len(data)), "Accept-Ranges": "bytes"})
            else:
                self.body(200, data, ctype, {"Accept-Ranges": "bytes"})
        else:
            self.body(200, b"<!doctype html><title>Stream test site</title><h1>Stream test site</h1>")


save()
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
