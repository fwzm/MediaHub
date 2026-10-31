"""A WebDAV endpoint whose only job is to fail in specific, named ways.

WsgiDAV serves the happy path. This server exists because "the folder could not be read" and "the
folder is empty" are different facts, and a client that renders the first as the second is wrong in a
way no happy-path test can see. Each child below fails one way, so the check can name what it proved:

    /ok/     207, one file      - the control: a listing that works on the same server
    /gone/   404 Not Found
    /denied/ 403 Forbidden
    /broken/ 500 Internal Server Error
    /secure/ 401 + WWW-Authenticate: Basic
    /slow1/  207 after 3 s      - two slow siblings, for navigation-race observation
    /slow2/  207 after 3 s

Every request is appended to fault-requests-<port>.jsonl next to this file (method, path, status,
seconds), so a check can assert what the server actually received rather than only what the UI
showed. The log carries the port because two instances are run at once (an offline-then-online
retry test needs a second port), and a single shared file made the second start truncate the
first instance's evidence.
"""
import json
import os
import sys
import time
import threading
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIServer, WSGIRequestHandler, make_server

DAV = "DAV:"
HERE = os.path.dirname(os.path.abspath(__file__))
LOG = ""  # set in main() from the port, see the note about two concurrent instances
_lock = threading.Lock()


def _record(method, path, status, started):
    rec = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "method": method,
        "path": path,
        "status": status,
        "seconds": round(time.time() - started, 3),
    }
    with _lock:
        with open(LOG, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")


def _response(href, *, collection=True, length=None):
    """One <D:response>. Kept minimal on purpose: only the properties the parser is allowed to trust."""
    kind = "<D:collection/>" if collection else ""
    props = [
        "<D:displayname>%s</D:displayname>" % href.strip("/").rsplit("/", 1)[-1],
        "<D:resourcetype>%s</D:resourcetype>" % kind,
    ]
    if length is not None and not collection:
        props.append("<D:getcontentlength>%d</D:getcontentlength>" % length)
        props.append("<D:getcontenttype>video/mp4</D:getcontenttype>")
    return (
        "<D:response><D:href>%s</D:href><D:propstat><D:prop>%s</D:prop>"
        "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    ) % (href, "".join(props))


def _multistatus(hrefs):
    body = "".join(hrefs)
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<D:multistatus xmlns:D="DAV:">%s</D:multistatus>' % body
    )


TREE = {
    "/": [
        _response("/", collection=True),
        _response("/ok/", collection=True),
        _response("/gone/", collection=True),
        _response("/denied/", collection=True),
        _response("/broken/", collection=True),
        _response("/secure/", collection=True),
        _response("/slow1/", collection=True),
        _response("/slow2/", collection=True),
    ],
    "/ok/": [
        _response("/ok/", collection=True),
        _response("/ok/inner.mp4", collection=False, length=4096),
    ],
    "/slow1/": [
        _response("/slow1/", collection=True),
        _response("/slow1/a.mp4", collection=False, length=1024),
    ],
    "/slow2/": [
        _response("/slow2/", collection=True),
        _response("/slow2/b.mp4", collection=False, length=1024),
    ],
}


def app(environ, start_response):
    started = time.time()
    method = environ.get("REQUEST_METHOD", "")
    path = environ.get("PATH_INFO", "") or "/"
    if not path.endswith("/") and "." not in path.rsplit("/", 1)[-1]:
        path = path + "/"

    def send(status, body=b"", headers=None):
        hdrs = [("Content-Type", "application/xml; charset=utf-8"), ("Content-Length", str(len(body)))]
        hdrs.extend(headers or [])
        start_response(status, hdrs)
        _record(method, path, status, started)
        return [body]

    if method == "OPTIONS":
        return send("200 OK", b"", [("DAV", "1,2"), ("Allow", "OPTIONS, PROPFIND, GET")])

    if method != "PROPFIND":
        return send("404 Not Found", b"")

    if path == "/gone/":
        return send("404 Not Found", b"")
    if path == "/denied/":
        return send("403 Forbidden", b"")
    if path == "/broken/":
        return send("500 Internal Server Error", b"")
    if path == "/secure/":
        return send("401 Unauthorized", b"", [("WWW-Authenticate", 'Basic realm="fault"')])
    if path in ("/slow1/", "/slow2/"):
        time.sleep(3.0)

    children = TREE.get(path)
    if children is None:
        return send("404 Not Found", b"")
    return send("207 Multi-Status", _multistatus(children).encode("utf-8"))


class Threaded(ThreadingMixIn, WSGIServer):
    daemon_threads = True


class Quiet(WSGIRequestHandler):
    def log_message(self, fmt, *args):  # the tap file is the log; stderr noise helps nobody
        pass


def main():
    global LOG
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8182
    LOG = os.path.join(HERE, "fault-requests-%d.jsonl" % port)
    if os.path.exists(LOG):
        os.remove(LOG)
    with make_server("127.0.0.1", port, app, server_class=Threaded, handler_class=Quiet) as srv:
        print("fault server on http://127.0.0.1:%d/ log=%s" % (port, LOG), flush=True)
        srv.serve_forever()


if __name__ == "__main__":
    main()
