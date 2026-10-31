# W5-D - WebDAV GUI loop against a real WebDAV server

Status: **partially verified.** The source can be added, authenticated and restored through the GUI;
browsing and playback of WebDAV content through the GUI is **unreachable**, not untested - the shell
has no surface that consumes a Browse-only source. One open defect is recorded below.

Everything in this report was produced by driving the real client
(`D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe`, rebuilt from
`feature/windows-client`) with UI Automation, against a real WebDAV server on the loopback address.
No application source was modified by this task.

## 1. The server under test

| item | value |
|------|-------|
| product | WsgiDAV |
| version | 4.3.3 (with cheroot 11.1.2, Python 3.13.2) |
| obtained from | PyPI, `python -m pip install "wsgidav==4.3.3" cheroot` |
| launcher | `windows/tools/acceptance/webdav/start-server.ps1` |
| server | `windows/tools/acceptance/webdav/webdav_server.py` |
| address | `http://127.0.0.1:8181/` |
| credentials | `mediahub` / `hub-test-pw` (Basic) |
| request tap | `evidence/webdav/requests.jsonl` - every request/response, method, path, Range header, auth scheme, status and content-range |

The server is a stock WsgiDAV instance behind one small middleware that (a) records every request and
(b) strips the `Range` header for paths under `/norange/`, which is exactly the observable behaviour of
a WebDAV share that does not implement Range: the request is answered with `200` and the whole entity.

### Content tree (`D:\mh-webdav\root`, built by `webdav/make-tree.ps1` with ffmpeg)

| path | purpose |
|------|---------|
| `Normal Movie.mp4` (90 s) | normal video, Range-capable |
| `Chinese 中文 视频 空格.mp4` (90 s) | non-ASCII name with spaces (percent-encoding round trip) |
| `Subs Movie.mp4` + `Subs Movie.srt` | external sibling subtitle discovery |
| `Season 01/Episode 01.mp4` + `.srt` | nested collection |
| `norange/NoRange 无范围.mp4` (90 s) | served WITHOUT Range support |

### Server characterisation (`webdav/probe.py`, output in `evidence/webdav/probe.json`)

| check | request | observed |
|-------|---------|----------|
| no credentials | `PROPFIND /` | `401` + `WWW-Authenticate: Basic realm="/"` |
| wrong password | `PROPFIND /` | `401` |
| root listing | `PROPFIND /` Depth 1 | `207` with all six entries plus the collection |
| nested collection | `PROPFIND /Season%2001/` Depth 0 | `207` |
| non-ASCII name | `PROPFIND /Chinese%20%E4%B8%AD%E6%96%87...mp4` Depth 0 | `207` |
| Range | `GET Normal Movie.mp4` `Range: bytes=100-199` | `206`, `Content-Range: bytes 100-199/10444706`, 100 bytes |
| no Range support | `GET /norange/NoRange...mp4` `Range: bytes=100-199` | `200`, no `Content-Range`, full 10444707 bytes |
| sibling subtitle | `GET Subs Movie.srt` | `200`, 1296 bytes, decodes as UTF-8 |

## 2. The loop, step by step

Driver: `windows/tools/acceptance/run-w5d-webdav-loop.ps1`. Raw result: `evidence/w5d-webdav/results.json`
(and `results.md`). Last run: **12 pass, 2 fail, 1 not verified**. The two failures are the open defect.

| # | step | assertion | result | observable |
|---|------|-----------|--------|------------|
| 1 | launch | client up, visible window, cleared trace | pass | `MainWindow:hwnd=...` in `startup.log` |
| 2 | open the add-source dialog | type combo present | pass | UIA dump `03-type-selected.txt` |
| 3 | pick the source type | the dialog really holds `WebDAV` | pass | UIA selection read-back before submit |
| 4 | submit | server sees a WebDAV request, not the MediaBrowser probe | pass | tap: `OPTIONS / -> 200 OK auth=Basic` |
| 5 | authenticate | a Basic-auth request answered 2xx | pass | tap, same line |
| 6 | source listed | a `WebDav` row exists | pass | sources page texts |
| 7 | persist | one WebDAV entry with url + user name | pass | `settings.json` |
| 8 | browse | visiting every shell surface makes the client ask the server for contents | **fail** | tap recorded **0 requests** |
| 9 | browse entry point | the WebDAV source appears somewhere browsable | **fail** | libraries page lists only Jellyfin libraries |
| 10 | open/play/seek a WebDAV item | - | **not verified** | unreachable from the UI |
| 11 | normal exit | exits on `WM_CLOSE`, no force kill | pass | `method=graceful`, 0.4-0.7 s |
| 12 | restart | the client re-probes the restored source | pass | tap: `OPTIONS / -> 200 OK auth=Basic` at startup |
| 13 | restored row | reports an authenticated session | pass | row reads `Cheroot/11.1.2 \| WebDav \| http://127.0.0.1:8181/ \| 已连接：mediahub \| Auth / Detail / Browse / Playback / Subtitle` |
| 14 | reconnect | 重新登录 with the same password succeeds | pass | InfoBar `重新登录成功` + tap `OPTIONS / -> 200 OK auth=Basic` |

## 3. Findings

### 3.1 Fixed during this task - the add-source probe ignored the selected type

**Symptom.** With `WebDAV` visibly selected in the dialog, `测试连接` and `登录并添加` sent the **MediaBrowser** probe; the server tap showed
`GET /emby/System/Info/Public -> 401` and the dialog said `添加失败 / 登录状态已过期，请重新登录`. Adding a WebDAV source was impossible.

**Cause.** `MediaServerProbe.ProbeMediaBrowserAsync` builds an Emby or Jellyfin dialect from the type and
always calls `GET /System/Info/Public`; there was no WebDAV branch, and every add-source path called it.

**Fixed by the Lead** (commit `3a1743e`): `ServerManager.TestConnectionAsync`/`AddAsync` now branch to
`WebDavServerProbe.ProbeAsync`, using credentials for the add path because an auth-requiring WebDAV server
answers `401` to an anonymous `OPTIONS`. **Re-verified here**: the wire now shows only `OPTIONS /` requests
with `auth=Basic -> 200`, and the source is added, persisted and usable.

### 3.2 Fixed during this task - a restored WebDAV source could not be used again

**Symptom.** After a normal restart the row read `需要登录`, the tap showed no startup request at all, and
`重新登录` with the correct password answered `重新登录失败 / 该媒体源不存在`. The only recovery was delete + re-add.

**Cause.** `ServerManager.RestoreAsync` treated an empty `Token` as "no credential". WebDAV never carries a
token (`WebDavProvider.CurrentSession` returns `SessionMaterial(null, user, user)`), so the password saved at
add time was ignored and no handle was built; `ReconnectAsync` then bailed out on the missing handle.

**Fixed by the Lead** (follow-up commit): a token **or** a password is now usable, and `ReconnectAsync`
rebuilds the handle from the vault. **Re-verified here** (steps 12-14), including the startup probe on the wire.

### 3.3 OPEN - no GUI surface browses a WebDAV source (reported, not fixed)

After the source is added and online, the shell never asks it for anything. Visiting `首页`, `媒体库`, `搜索`,
`继续观看` produced **zero** requests in the tap. `LibrariesPage` filters
`OnlineHandles.Where(h => h.Library is not null)`; the WebDAV descriptor declares
`Auth | Browse | Detail | Playback | SubtitleDiscovery` and no library, so it can never appear as a tile.
`SearchPage` requires `ProviderCapability.Search` (correctly not declared), and the only folder browser is
`LocalLibraryPage` for local folders.

Consequence: `WebDavProvider.ListFolderAsync` (`IProviderBrowse`) has no consumer in the shell. A user
cannot see, open or play a single file from a WebDAV source. **Browsing and playback of WebDAV content
through the GUI is unreachable** - which is stronger and more useful than "not tested".

The Lead has confirmed this is a real missing entry point and is reporting it as an open defect rather than
claiming W5-D complete.

## 4. Diagnostic shim, and why it is off

`webdav_server.py --emby-info-shim` makes the server answer `GET /System/Info/Public` with a Jellyfin-shaped
payload. It exists for exactly one purpose: to isolate finding 3.1 by letting everything *after* the broken
probe run, so it could be shown that the probe - and only the probe - was broken. It is **off** in every run
reported above (the loop script refuses to run if it is on). It is not a claim that a WebDAV server is a
media server, and it was never used to make a failure look like a pass.

## 5. Harness lessons worth keeping

1. **A UIA read-back is not the app's state.** `SelectionItemPattern.Select()` on the type combo's item made
   `SelectionPattern.GetSelection()` report `WebDAV` while the app still used its Emby default - the wire
   proved it. The dialog is now driven with a real click on the dropdown item, and the wire is the assertion.
2. **A window that is not foreground silently swallows every synthetic keystroke.** A whole area run can
   report product failures when the app was simply never focused. `Set-MhForeground` now attaches to the
   foreground thread's input queue and retries, and area 03 went from 3/7 to 7/7 once that was fixed.
3. **`ShowWindow(SW_SHOW)` on a WinUI 3 window is not a raise.** It left the window non-visible while the
   process kept running, which every later step read as "the app is gone". The harness no longer calls it.
4. **The server's request tap is the cheapest ground truth in this codebase.** UI state can lie; the tap
   cannot. Every claim above that mentions a protocol has a tap line behind it.

## 6. What was NOT verified

- **Browsing a WebDAV source** - unreachable (3.3).
- **Opening, playing, seeking, subtitle and audio-track behaviour of WebDAV content** - unreachable.
- **WebDAV playback over TLS (`https://`)** - the fixture server is plain HTTP only.
- **A WebDAV server that fails mid-transfer, or returns malformed multistatus** - not exercised.
- **The no-Range mount through the client.** The server was characterised (`200`, no `Content-Range`), and
  the provider's range probe (`206`/`416` supported, `200` not) is unit-tested in the repository, but no GUI
  path can reach a WebDAV file to observe the client's `NotSupported` branch end to end (3.3 blocks it).
- **Multiple WebDAV sources, delete-then-readd through the UI, and credential rotation** - out of scope here.

## 7. Reproducing

```powershell
# 1. build the fixtures and start the server (records pid/url/version in start-server.json)
pwsh windows/tools/acceptance/webdav/make-tree.ps1
pwsh windows/tools/acceptance/webdav/start-server.ps1 -Restart
python windows/tools/acceptance/webdav/probe.py --out evidence/webdav/probe.json

# 2. drive the client through the whole loop (closes and relaunches the app itself)
pwsh windows/tools/acceptance/run-w5d-webdav-loop.ps1

# 3. read the results
#    windows/tools/acceptance/evidence/w5d-webdav/results.json
#    windows/tools/acceptance/evidence/w5d-webdav/results.md
```
