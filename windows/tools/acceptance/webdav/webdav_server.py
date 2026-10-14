#!/usr/bin/env python3
"""W5-D WebDAV test server: WsgiDAV + a request tap + a deliberate no-Range mount.

Why this exists
---------------
The WebDAV GUI loop needs a server whose behaviour is *knowable* and *recorded*:

* every request is appended to a JSONL tap, so "the client browsed the folder" is proven by the
  requests the server actually received, not by a screenshot of a list that might have come from
  somewhere else (rule 1 of this project: a green step is not a tested thing);
* one subtree answers Range requests as if it did not understand them (200 + full body, no
  Content-Range), which is what a cheap NAS or a CGI-backed WebDAV share really does. The
  provider is supposed to detect that and drop the resume position instead of pretending to seek.

The Range behaviour is switched by stripping the `Range` request header for a path prefix *before*
WsgiDAV sees it. That is exactly the observable contract of a server without Range support: the
header is ignored and the whole entity is returned with 200.

Run:
    python webdav_server.py --root D:\\mh-webdav\\root --port 8181 --log tap.jsonl
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time

from cheroot import wsgi
from wsgidav.wsgidav_app import WsgiDAVApp


class MediaBrowserInfoShim:
    """
    Diagnostic-only stand-in for the Emby/Jellyfin "who are you" endpoint.

    Why it exists: MediaHub's "add a media source" flow probes EVERY source type with the
    MediaBrowser protocol (MediaHub.Providers/MediaBrowser/MediaBrowserFactories.cs
    ProbeMediaBrowserAsync builds an Emby or Jellyfin dialect from the type and always GETs
    /System/Info/Public). For a WebDAV source that request goes to a WebDAV server, which answers
    401, and the add fails before the WebDAV provider is ever constructed.

    Serving a Jellyfin-shaped /System/Info/Public is how the harness isolates that one broken step:
    everything after it - the OPTIONS probe, the Basic authentication, PROPFIND browsing, Range
    playback - is the real WebDAV provider talking to the real WebDAV server. It is OFF by default
    (--emby-info-shim) precisely so the clean run stays clean, and the shim is never a claim that
    the server is a media server.
    """

    def __init__(self, app, server_id: str, server_name: str):
        self.app = app
        self.server_id = server_id
        self.server_name = server_name
        self.payload = json.dumps(
            {
                "Id": server_id,
                "ServerName": server_name,
                "Version": "10.9.0",
                "ProductName": "MediaHub WebDAV fixture",
                "OperatingSystem": "Windows",
            }
        ).encode("utf-8")

    def __call__(self, environ, start_response):
        path = environ.get("PATH_INFO", "").rstrip("/")
        if environ.get("REQUEST_METHOD") == "GET" and path == "/System/Info/Public":
            start_response(
                "200 OK",
                [
                    ("Content-Type", "application/json; charset=utf-8"),
                    ("Content-Length", str(len(self.payload))),
                ],
            )
            return [self.payload]
        return self.app(environ, start_response)


class TapMiddleware:
    """Records every request/response pair to a JSONL file, optionally stripping Range."""

    def __init__(self, app, log_path: str, strip_range_prefix: str | None, verbose: bool = True):
        self.app = app
        self.log_path = log_path
        self.strip_range_prefix = strip_range_prefix
        self.verbose = verbose
        self._lock = threading.Lock()
        os.makedirs(os.path.dirname(os.path.abspath(log_path)), exist_ok=True)

    # -- logging ---------------------------------------------------------------
    def _record(self, rec: dict) -> None:
        line = json.dumps(rec, ensure_ascii=False)
        with self._lock:
            with open(self.log_path, "a", encoding="utf-8") as fh:
                fh.write(line + "\n")
        if self.verbose:
            print(
                "[tap] {method} {path} range={rng} -> {status} bytes={n}".format(
                    method=rec.get("method"),
                    path=rec.get("path"),
                    rng=rec.get("range") or "-",
                    status=rec.get("status"),
                    n=rec.get("bytes"),
                ),
                flush=True,
            )

    # -- WSGI ------------------------------------------------------------------
    def __call__(self, environ, start_response):
        method = environ.get("REQUEST_METHOD", "")
        path = environ.get("PATH_INFO", "")
        query = environ.get("QUERY_STRING", "")
        raw_range = environ.get("HTTP_RANGE")
        auth = environ.get("HTTP_AUTHORIZATION") or ""
        # The credential value is never recorded - only the scheme - so the tap file can be pasted
        # into a report without leaking the password it was authenticating with.
        auth_scheme = auth.split(" ", 1)[0] if auth else ""

        stripped = False
        if (
            self.strip_range_prefix
            and raw_range
            and path.startswith(self.strip_range_prefix)
        ):
            environ.pop("HTTP_RANGE", None)
            env_key = "wsgidav.range"
            if env_key in environ:
                environ.pop(env_key, None)
            stripped = True

        rec = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "t": round(time.time(), 3),
            "method": method,
            "path": path,
            "query": query,
            "range": raw_range,
            "range_stripped": stripped,
            "depth": environ.get("HTTP_DEPTH"),
            "auth": auth_scheme,
            "user_agent": environ.get("HTTP_USER_AGENT", ""),
            "peer": environ.get("REMOTE_ADDR", ""),
        }

        captured: dict = {}
        state = {"written": 0}

        def _start_response(status, headers, exc_info=None):
            # The tap wraps the *server's* start_response; it must not replace it. Returning a
            # private write callable here was the first version of this file and it broke every
            # response ("WSGI write called before start_response") because cheroot never saw the
            # status line at all.
            captured["status"] = status
            captured["headers"] = headers
            outer_write = start_response(status, headers, exc_info)

            def _write(data: bytes):
                state["written"] += len(data or b"")
                return outer_write(data) if outer_write else None

            return _write

        try:
            app_iter = self.app(environ, _start_response)
        except Exception as exc:  # the tap must not hide a server-side failure
            rec["status"] = "EXC:" + type(exc).__name__
            rec["bytes"] = 0
            self._record(rec)
            raise

        return self._wrap(app_iter, captured, rec, state)

    def _wrap(self, app_iter, captured, rec, state):
        total = state["written"]
        try:
            for chunk in app_iter:
                total += len(chunk)
                yield chunk
        finally:
            try:
                close = getattr(app_iter, "close", None)
                if close:
                    close()
            except Exception:
                pass
            headers = captured.get("headers") or []
            hmap = {k.lower(): v for k, v in headers}
            rec["status"] = captured.get("status")
            rec["bytes"] = total
            rec["content_range"] = hmap.get("content-range")
            rec["accept_ranges"] = hmap.get("accept-ranges")
            rec["content_length"] = hmap.get("content-length")
            rec["content_type"] = hmap.get("content-type")
            self._record(rec)


def build_app(
    root: str,
    user: str,
    password: str,
    log_path: str,
    strip_range_prefix: str,
    info_shim: bool = False,
    shim_id: str = "9d1f7c4ab2e34f6d8a05c1e73b6f2048",
):
    config = {
        "provider_mapping": {"/": root},
        "http_authenticator": {
            "domain_controller": "wsgidav.dc.simple_dc.SimpleDomainController",
            "accept_basic": True,
            "accept_digest": False,
            "default_to_digest": False,
        },
        "simple_dc": {"user_mapping": {"*": {user: {"password": password}}}},
        "verbose": 1,
        "logging": {"enable_loggers": []},
        "property_manager": True,
        "lock_storage": True,
        "dir_browser": {"enable": False},
    }
    app = WsgiDAVApp(config)
    if info_shim:
        app = MediaBrowserInfoShim(app, shim_id, "MediaHub WebDAV fixture")
    return TapMiddleware(app, log_path, strip_range_prefix)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8181)
    parser.add_argument("--log", required=True)
    parser.add_argument("--user", default="mediahub")
    parser.add_argument("--password", default="hub-test-pw")
    parser.add_argument(
        "--norange-prefix",
        default="/norange",
        help="path prefix whose Range headers are stripped (simulates a server without Range support)",
    )
    parser.add_argument(
        "--emby-info-shim",
        action="store_true",
        help="answer GET /System/Info/Public with a Jellyfin-shaped payload (diagnostic workaround "
             "for the MediaBrowser-probe defect in the add-source flow)",
    )
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument(
        "--stdout",
        default="",
        help="append stdout/stderr here instead of inheriting the launcher's pipes",
    )
    args = parser.parse_args()

    if args.stdout:
        # Inheriting the launcher's stdout made every "start the server" step hang until the
        # harness gave up: the detached process keeps the pipe's write handle open, so the reader
        # never sees EOF. The server owns its own log file instead.
        os.makedirs(os.path.dirname(os.path.abspath(args.stdout)), exist_ok=True)
        stream = open(args.stdout, "a", encoding="utf-8", buffering=1)
        sys.stdout = stream
        sys.stderr = stream

    if not os.path.isdir(args.root):
        print("root does not exist: " + args.root, file=sys.stderr)
        return 2

    app = build_app(
        args.root, args.user, args.password, args.log, args.norange_prefix, args.emby_info_shim
    )
    server = wsgi.Server((args.host, args.port), app, numthreads=10)
    server.start()
    print(
        "PID=" + str(os.getpid()),
        "READY wsgidav={ver} root={root} url=http://{host}:{port}/ norange_prefix={nr} shim={shim}".format(
            shim=bool(args.emby_info_shim),
            ver=__import__("wsgidav").__version__,
            root=args.root,
            host=args.host,
            port=args.port,
            nr=args.norange_prefix,
        ),
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
