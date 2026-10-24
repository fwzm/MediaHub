# W5-F — official Emby 4.10.1.0: deployment and response-shape comparison against Jellyfin 12.2

- Date: 2026-10-14 (W5-F). Author: engine-dev.
- Status: **Emby is installed, running, wizard-completed, logged in, and PlaybackInfo verified.**
  Item ids, /Users/Me behaviour and header handling were measured, not assumed.
- Scripts: windows/engine/emby/. Transcripts: emby-api-transcript.txt,
  emby-playbackinfo-transcript.txt (every request and response verbatim).

---

## 1. Artifact

| Fact | Value |
| --- | --- |
| URL | https://github.com/MediaBrowser/Emby.Releases/releases/download/4.10.1.0/embyserver-win-x64-4.10.1.0.7z |
| Size | 133,879,144 bytes (127.7 MiB) |
| SHA-256 | FBC951E1583310E1385E3D0779DE8823F5E9809F5BBCDAC4D7AC8F4033BD01FD |
| Extract | bsdtar (tar.exe) reads 7z; 1,676 entries; EmbyServer.exe lives at <install>\system\EmbyServer.exe |
| FileVersion | 4.10.1.0 (ProductVersion 4.10.1.0+ac2d75c0da35a6a11306c80ae815ac82f11ddfa7) |
| Install dir | D:\mh-servers\emby-install\app\system |
| Data dir | D:\mh-servers\data\emby (isolated, -programdata) |
| Port | **8097** (8096 is taken by the Jellyfin 12.2 instance that is still running) |

W4 failed this download on bandwidth (~0.2 MB/s). It succeeded here on the first attempt with
resumable curl: **~836 KB/s sustained, 127.6 MB in 2 m 36 s**, about 4x the W4 rate. No retry was
needed. Command:

    curl -L -C - --retry 20 --retry-all-errors --retry-delay 5 --connect-timeout 30 -o embyserver-win-x64-4.10.1.0.7z <URL>

## 2. Two operational traps

1. **The -port switch is ignored.** EmbyServer.exe was started with '-programdata <dir> -port 8097'
   and bound **8096** anyway, colliding with Jellyfin (Emby ended up IPv6-only on ::8096). The port
   must be set in <programdata>\config\system.xml (PublicPort and HttpServerPortNumber) with the
   server stopped. windows/engine/emby/setup-emby.ps1 does exactly that.
2. **First run reaches the internet.** Emby pulls and installs plugins (OpenSubtitles, TheAudioDb,
   Webhooks, XmlTV) during startup and logs 'Emby Server needs to be restarted'. Setup on an
   air-gapped machine will behave differently.

## 3. First-run setup via API

The wizard was driven entirely over HTTP; system.xml now shows IsStartupWizardCompleted=true and
the user 'mh' exists. The endpoints are /Startup/Configuration, /Startup/User, /Startup/RemoteAccess,
/Startup/Complete. **Once the wizard is complete, every /Startup/* endpoint returns 401** ('Access
token is invalid or expired.'), so this sequence is one-shot and must be run before the wizard closes.

## 4. THE COMPARISON — Emby 4.10.1.0 vs Jellyfin 12.2

This is the part that matters for the W4 fixes, because several of them encode Jellyfin's shape.

| Aspect | Jellyfin 12.2 (W4) | Emby 4.10.1.0 (measured here) | Consequence |
| --- | --- | --- | --- |
| AuthenticateByName with X-Emby-Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=… | **REJECTED** (400; server log ArgumentNullException on request.App) | **ACCEPTED → HTTP 200** | The header is portable; Jellyfin is the outlier. A client that only sends Authorization: will still work on Emby. |
| AccessToken location in the login response | top level | **top level** | same |
| **Item id shape** | 32-hex GUID | **short numeric string: '5', '6', '7'** | **Any client code that parses or validates item ids as GUIDs will break on Emby.** |
| GET /Items/{shortId} | n/a | **HTTP 404** 'The file /Items/5 could not be found.' | The item-detail route does not accept Emby's short ids even though /Items returns them. |
| **GET /Users/Me** | works | **HTTP 500 'Unrecognized Guid format.'** — reproduced with X-Emby-Token, with Authorization: … Token=…, and with ?api_key= | **Do not depend on /Users/Me for Emby.** Use GET /Users/{userId} with the id from the login response — HTTP 200, full profile. |
| GET /Users/{userId} | works | works (HTTP 200) | the portable path |
| UserData (Played / PlaybackPositionTicks) | present on items | present on items via /Users/{userId}/Items | same shape |
| POST /Items/{id}/PlaybackInfo | works | **works with the short id '5' → HTTP 200** | PlaybackInfo accepts what /Items/{id} rejects |
| **PlaySessionId location in PlaybackInfo** | top level, sibling of MediaSources | **top level, sibling of MediaSources** | **No change needed** — the W4 assumption holds on Emby too. |
| Library creation | n/a | POST /Library/VirtualFolders?...&refreshLibrary=true → HTTP 204; POST /Library/Refresh → 204 | works headlessly |

PlaySessionId raw evidence (note the closing bracket of MediaSources before it):

    {"MediaSources":[{…,"ItemId":"5","MimeType":"video/mp4"}],"PlaySessionId":"585613b9e7154cb69166ea74d4fd044e"}

Item ids raw evidence:

    {"Items":[{"Name":"bbb720","ServerId":"1271d0a1040046db8c0089cc328bbbf1","Id":"5",…
              {"Name":"jelly1080","ServerId":"…","Id":"6",…
              {"Name":"sintel1080","ServerId":"…","Id":"7",…}]

### 4.1 What to change in MediaHub

1. **Item ids must be treated as opaque strings**, never parsed as Guid. This is the highest-risk
   finding: it is a silent type assumption that happens to hold on Jellyfin.
2. **Resolve the current user via /Users/{id}**, falling back from /Users/Me. On Emby, /Users/Me
   returns 500; a client that only implements /Users/Me cannot even show the user name.
3. **PlaySessionId parsing needs no change** — it is top-level on both servers.
4. Sending X-Emby-Authorization is safe on both (Emby accepts, Jellyfin rejects it — so keep sending
   the Jellyfin-compatible form, or send both).

## 5. Residual notes

- The Emby instance is left **running** on 8097 with data in D:\mh-servers\data\emby, user mh /
  mh-test-pass, and a library 'MHTest' over .tools\media. Stop it with: Get-Process EmbyServer |
  Stop-Process -Force.
- Emby was NOT tested for playback (streaming, progress write-back, transcoding). Only the API
  shapes above were verified; a full Emby playback pass is still outstanding.
- The library scan picked up 3 items (bbb720, jelly1080, sintel1080).
