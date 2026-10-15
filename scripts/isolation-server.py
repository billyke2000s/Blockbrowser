#!/usr/bin/env python3
"""Local test website for scripts/private-isolation-check.sh.

Simulates "logging in" to a site and records what the browser sends back, so the test can tell whether
normal and private browsing share cookies, localStorage or IndexedDB.

  /login?who=NAME   sets a persistent cookie (bbsession=NAME), localStorage and IndexedDB to NAME,
                    then reports /stored?who=NAME once storage writes finish
  /check?step=STEP  records the Cookie header the browser sent, then reports what localStorage and
                    IndexedDB contain as /report?step=STEP&ls=...&idb=...

Results are written as JSON to the file given as the second argument: {"steps": {...}, "stored": [...]}.
Usage: isolation-server.py PORT RESULTS_JSON
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1])
RESULTS = sys.argv[2]
lock = threading.Lock()
state = {"steps": {}, "stored": []}


def save():
    with open(RESULTS, "w") as f:
        json.dump(state, f, indent=1)


STORE_JS = """
function idbOpen(){return new Promise(function(res,rej){var r=indexedDB.open('bbtest',1);
  r.onupgradeneeded=function(){r.result.createObjectStore('kv')};r.onsuccess=function(){res(r.result)};r.onerror=function(){rej(r.error)};});}
function idbGet(){return idbOpen().then(function(db){return new Promise(function(res){var t=db.transaction('kv','readonly');
  var g=t.objectStore('kv').get('who');g.onsuccess=function(){res(g.result||'')};g.onerror=function(){res('error')};});}).catch(function(){return 'error'});}
function idbPut(v){return idbOpen().then(function(db){return new Promise(function(res){var t=db.transaction('kv','readwrite');
  t.objectStore('kv').put(v,'who');t.oncomplete=function(){res()};t.onerror=function(){res()};});}).catch(function(){});}
"""


def page(title, script):
    return ("<!doctype html><html><head><meta name=viewport content='width=device-width'><title>%s</title></head>"
            "<body><h1>%s</h1><script>%s%s</script></body></html>" % (title, title, STORE_JS, script)).encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("server: " + (fmt % args) + "\n")

    def send(self, body, cookie=None):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query, keep_blank_values=True).items()}
        cookies = self.headers.get("Cookie", "")
        if url.path == "/login":
            who = q.get("who", "")
            script = ("localStorage.setItem('bbtest',%s);idbPut(%s).then(function(){fetch('/stored?who='+%s)});"
                      % (json.dumps(who), json.dumps(who), json.dumps(who)))
            self.send(page("Logged in as " + who, script), "bbsession=%s; Path=/; Max-Age=86400" % who)
        elif url.path == "/check":
            step = q.get("step", "")
            with lock:
                state["steps"].setdefault(step, {})["cookie"] = cookies
                save()
            script = ("idbGet().then(function(i){fetch('/report?step='+encodeURIComponent(%s)+'&ls='"
                      "+encodeURIComponent(localStorage.getItem('bbtest')||'')+'&idb='+encodeURIComponent(i))});" % json.dumps(step))
            self.send(page("Check " + step, script))
        elif url.path == "/report":
            with lock:
                entry = state["steps"].setdefault(q.get("step", ""), {})
                entry["ls"] = q.get("ls", "")
                entry["idb"] = q.get("idb", "")
                entry["done"] = True
                save()
            self.send(b"ok")
        elif url.path == "/stored":
            with lock:
                state["stored"].append(q.get("who", ""))
                save()
            self.send(b"ok")
        else:
            self.send(page("Block Browser test page", ""))


save()
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
