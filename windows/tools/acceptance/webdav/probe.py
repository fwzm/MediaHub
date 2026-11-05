#!/usr/bin/env python3
"""Characterises the W5-D test server the way the client will use it.

Every claim made in the report about this server is produced here: PROPFIND shape, Basic auth
enforcement, and - the point of the whole exercise - which files answer Range with 206 and which
answer with a plain 200 that ignores it. Run it before the GUI loop and keep its JSON output.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse

import requests

RESULTS: list[dict] = []


def record(area: str, url: str, method: str, resp: requests.Response | None, note: str = "", **extra):
    row = {
        "area": area,
        "method": method,
        "url": url,
        "status": getattr(resp, "status_code", None),
        "headers": {
            k: v
            for k, v in (resp.headers.items() if resp is not None else [])
            if k.lower() in ("content-range", "accept-ranges", "content-length", "content-type", "dav", "www-authenticate")
        },
        "note": note,
    }
    row.update(extra)
    RESULTS.append(row)
    print(json.dumps(row, ensure_ascii=False))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8181/")
    ap.add_argument("--user", default="mediahub")
    ap.add_argument("--password", default="hub-test-pw")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    base = args.url.rstrip("/") + "/"
    auth = (args.user, args.password)
    body = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<d:propfind xmlns:d="DAV:"><d:prop>'
        "<d:displayname/><d:getcontentlength/><d:getcontenttype/><d:resourcetype/>"
        "<d:getlastmodified/>"
        "</d:prop></d:propfind>"
    )
    hdr = {"Depth": "1", "Content-Type": "application/xml; charset=utf-8"}

    # 1. auth is actually enforced: an anonymous PROPFIND must not be answered with 207.
    try:
        r = requests.request("PROPFIND", base, data=body, headers=hdr, timeout=10)
        record("auth", base, "PROPFIND", r, "anonymous")
    except Exception as exc:
        record("auth", base, "PROPFIND", None, "anonymous failed: %s" % exc)

    # 2. root listing.
    r = requests.request("PROPFIND", base, data=body, headers=hdr, auth=auth, timeout=10)
    record("browse", base, "PROPFIND", r, "root depth=1", entries=len(r.text.split("<D:response")))
    root_names = sorted(set(
        urllib.parse.unquote(x) for x in r.text.replace("<d:", "<D:").split("<D:href>")[1:]
    ))
    print("ROOT HREFS: " + json.dumps(root_names, ensure_ascii=False))

    # 3. the nested collection and the non-ASCII name, both of which must survive the round trip.
    # Note the full name: an earlier revision of this probe omitted the "Chinese " prefix and the
    # resulting 404 was misread as "the server cannot serve non-ASCII names". It can.
    for rel in ["Season 01/", "Chinese 中文 视频 空格.mp4", "Normal Movie.mp4"]:
        url = urllib.parse.urljoin(base, urllib.parse.quote(rel))
        rr = requests.request("PROPFIND", url, data=body, headers={"Depth": "0"}, auth=auth, timeout=10)
        record("browse", url, "PROPFIND", rr, "depth=0", href_ok=("<D:href>" in rr.text or "<d:href>" in rr.text))

    # 4. Range WITH support.
    url = urllib.parse.urljoin(base, urllib.parse.quote("Normal Movie.mp4"))
    r = requests.get(url, headers={"Range": "bytes=100-199"}, auth=auth, timeout=10)
    record("range", url, "GET", r, "Range: bytes=100-199", bytes=len(r.content))

    # 5. Range WITHOUT support, on the mount whose Range header is stripped.
    url = urllib.parse.urljoin(base, urllib.parse.quote("norange/NoRange 无范围.mp4"))
    r = requests.get(url, headers={"Range": "bytes=100-199"}, auth=auth, timeout=30)
    record("range", url, "GET", r, "Range: bytes=100-199 (must be ignored)", bytes=len(r.content))

    # 6. The sibling subtitle the provider has to discover by name.
    url = urllib.parse.urljoin(base, urllib.parse.quote("Subs Movie.srt"))
    r = requests.get(url, auth=auth, timeout=10)
    record("subtitle", url, "GET", r, "sibling .srt", bytes=len(r.content),
           utf8_ok=("Subs Movie cue 1" in r.content.decode("utf-8", "replace")))

    # 7. Wrong password must fail closed.
    r = requests.request("PROPFIND", base, data=body, headers=hdr, auth=(args.user, "wrong"), timeout=10)
    record("auth", base, "PROPFIND", r, "wrong password")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(RESULTS, fh, ensure_ascii=False, indent=2)
        print("WROTE " + args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
