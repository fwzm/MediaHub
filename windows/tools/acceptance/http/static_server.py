#!/usr/bin/env python3
"""Static file server for the HTTP-source acceptance run.

Why this exists
---------------
The Http source is verified against a server whose behaviour is knowable and recorded: it supports
HEAD and single-range requests (the two things seeking needs), serves a real m3u playlist, and
appends every request to a JSONL tap. "The video played" then rests on the requests the server
actually received (GET, Range, 206) rather than on a screenshot alone.

Only files under --root are served; path traversal is refused. Everything is read-only.

Run:
    python static_server.py --root D:\\mh-http --port 8199 --log requests.jsonl --stdout server.log

--rate caps the body at N bytes per second. With a whole clip arriving in one blast, a seek can be
answered from the bytes the engine already holds, and "seeking works" proves nothing about the
network. Throttled, the buffer cannot contain the seek target, so a successful seek has to show up
as another Range request in the tap.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")

MIME_OVERRIDES = {
    ".m3u": "audio/x-mpegurl",
    ".m3u8": "application/vnd.apple.mpegurl",
    ".mp4": "video/mp4",
    ".mkv": "video/x-matroska",
    ".srt": "application/x-subrip",
}


class StaticHandler(BaseHTTPRequestHandler):
    server_version = "MediaHubStatic/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: A003 - BaseHTTPRequestHandler API
        # Deliberately silent: the JSONL tap is the record, and stderr would duplicate it.
        pass

    # ------------------------------------------------------------ request handling

    def do_HEAD(self):  # noqa: N802 - BaseHTTPRequestHandler API
        self._serve(include_body=False)

    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler API
        self._serve(include_body=True)

    def _resolve(self):
        path = self.path.split("?", 1)[0]
        relative = path.lstrip("/")
        candidate = os.path.normpath(os.path.join(self.server.root, relative))
        if not candidate.startswith(os.path.normpath(self.server.root) + os.sep) and \
                candidate != os.path.normpath(self.server.root):
            return None
        if not os.path.isfile(candidate):
            return None
        return candidate

    def _serve(self, include_body):
        started = time.time()
        target = self._resolve()
        if target is None:
            self._finish(404, "text/plain; charset=utf-8", b"not found", None, include_body, started)
            return

        size = os.path.getsize(target)
        content_type = MIME_OVERRIDES.get(
            os.path.splitext(target)[1].lower(),
            mimetypes.guess_type(target)[0] or "application/octet-stream")

        start, end, status = 0, size - 1, 200
        requested = self.headers.get("Range")
        if requested:
            match = RANGE_RE.match(requested.strip())
            if match:
                first, last = match.group(1), match.group(2)
                if first:
                    start = int(first)
                    end = int(last) if last else size - 1
                elif last:
                    # Suffix form: bytes=-N means the last N bytes.
                    start = max(0, size - int(last))
                    end = size - 1
                if start >= size:
                    self._finish(416, "text/plain; charset=utf-8", b"range not satisfiable",
                                 None, include_body, started)
                    return
                end = min(end, size - 1)
                if end >= start:
                    status = 206

        length = max(0, end - start + 1)
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()

        sent = 0
        if include_body and length > 0:
            # Announce the request before the body is written. A body that takes tens of seconds to
            # send would otherwise be invisible in the tap until it finished, which makes "the seek
            # asked for another range" impossible to read while the run is still going.
            self._tap(status, requested, 0, length, started, phase="begin", response_length=length)
            try:
                with open(target, "rb") as handle:
                    handle.seek(start)
                    remaining = length
                    rate = self.server.rate
                    body_started = time.time()
                    while remaining > 0:
                        chunk = handle.read(min(65536, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        sent += len(chunk)
                        remaining -= len(chunk)
                        if rate > 0:
                            # Pace the body so the client cannot outrun the wire: the point of the
                            # throttled run is that the bytes for a seek target are still unsent.
                            due = body_started + sent / rate
                            delay = due - time.time()
                            if delay > 0:
                                time.sleep(delay)
            except (BrokenPipeError, ConnectionResetError):
                # The engine abandons a range the moment it has what it needs (a seek cancels the
                # previous request); that is normal and must not kill the server.
                pass
        self._tap(status, requested, sent, length if include_body else 0, started)

    def _finish(self, status, content_type, body, extra, include_body, started):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if include_body:
            self.wfile.write(body)
        self._tap(status, self.headers.get("Range"), len(body) if include_body else 0, 0, started)

    def _tap(self, status, requested_range, sent, declared, started, phase="end", response_length=0):
        line = {
            "t": round(time.time(), 3),
            "phase": phase,
            "method": self.command,
            "path": self.path,
            "range": requested_range,
            "status": status,
            "sent": sent,
            "declared": declared,
            "length": response_length,
            "ms": round((time.time() - started) * 1000, 1),
            "ua": self.headers.get("User-Agent", ""),
        }
        with self.server.tap_lock:
            with open(self.server.tap, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, ensure_ascii=False) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--port", type=int, default=8199)
    parser.add_argument("--log", required=True)
    parser.add_argument("--stdout", default="")
    parser.add_argument("--rate", type=int, default=0, help="body bytes per second; 0 = unthrottled")
    args = parser.parse_args()

    if args.stdout:
        sys.stdout = open(args.stdout, "a", encoding="utf-8", buffering=1)
        sys.stderr = sys.stdout

    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), StaticHandler)
    httpd.root = os.path.abspath(args.root)
    httpd.tap = os.path.abspath(args.log)
    httpd.tap_lock = threading.Lock()
    httpd.rate = max(0, args.rate)
    print("listening on http://127.0.0.1:%d/ root=%s tap=%s rate=%s" %
          (args.port, httpd.root, httpd.tap, httpd.rate or "unlimited"))
    httpd.serve_forever()


if __name__ == "__main__":
    main()
