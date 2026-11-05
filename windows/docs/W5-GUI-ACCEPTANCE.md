# W5-E - Windows GUI acceptance harness and results

Two deliverables: the harness itself (`windows/tools/acceptance/`) and what it observed.

**Headline.** Playback controls, fullscreen and lifecycle pass against the real client with measured
observables. One remote-playback release claim (the loopback relay) is **not verified** because the
remote item could not be started. The file-menu / drag-and-drop / queue area (`04`) was **not
implemented** in this round. Three harness-side defects were found and fixed during the work; they are
recorded here because two of them produced convincing but false product failures.

## 1. The harness

| file | role |
|------|------|
| `lib/MhUia.psm1` | shared module: DPI awareness, window geometry, UI Automation lookup/query, synthetic input, evidence capture, result recording, app lifecycle |
| `lib/audio-peak.ps1` | peak level of the client's Windows audio session (the only honest mute observable) |
| `media/make-fixtures.ps1` | builds the local fixtures under `D:\mh-media` (kept out of the repository) |
| `01-lifecycle.ps1` | minimise, restore, resize, leave the player, close, restart |
| `02-playback-controls.ps1` | play/pause, seek (button, key, slider), volume, mute, speed, audio track, subtitle |
| `03-fullscreen.ps1` | F, F11, ESC (both meanings), full-screen button |
| `05-resource-release.ps1` | what the app owns while running, and whether it is released on a normal exit |
| `run-all.ps1` | runs the areas sequentially and aggregates |
| `webdav/*`, `run-w5d-webdav-loop.ps1` | the W5-D WebDAV loop, reported separately in `W5-WEBDAV-GUI.md` |

Design rules encoded in the module:

* **DPI.** `SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2)` is attempted at import, then the
  thread-level override (which is what actually takes effect under `pwsh.exe`, measured awareness
  `2 = per-monitor-aware`, scale 1.5). A capture is the whole 3048x2032 virtual screen, then cropped.
  A capture that silently covered only part of the screen would have produced false negatives on a
  150% display - the exact failure mode this project already paid for once.
* **Assertions, not existence.** `Add-MhCheck` throws if the assertion string is empty: a check without
  a claim is not a check. Every check stores before-state, the input, after-state, the assertion, the
  evidence paths and a note, and is written to `evidence/<area>/results.json` and `results.md`.
* **Evidence is a file.** Screenshots are DPI-aware and window-cropped; UIA dumps carry AutomationId,
  class, offscreen flag and rectangle, so a failed lookup can be explained after the fact.
* **The app's own trace is evidence.** `%LOCALAPPDATA%\MediaHub\startup.log` is read for `layout:`, `nav:`
  and `playback:` lines - the client's own statement about what it did.

### Observable channels, and why these ones

| question | observable | why not the obvious one |
|----------|-----------|--------------------------|
| is it playing? | `PositionText` advancing over a wall-clock window | the play/pause glyph is a `FontIcon` glyph, not published to UI Automation |
| is it paused? | the same text frozen for 3 s | a screenshot cannot distinguish a paused frame from a still one |
| did the seek land? | `SeekSlider` RangeValue plus `PositionText` | "the slider moved" is not "the media moved" |
| is it muted? | peak level of the app's audio session (`audio-peak.ps1`) | the client publishes no mute state; measured 0.0886 -> 0.0000 -> 0.0886 |
| is the speed applied? | position advance rate over a fixed window | combo selection alone proves only that a combo changed |
| is full screen? | DWM frame == monitor rectangle **and** `WS_CAPTION` gone | maximised and full screen have the same width |
| is the video area right? | the engine's own child window (`class mpv`) rectangle | the XAML host panel only reserves space |
| did the app talk to the server? | the WebDAV request tap | UI state can lie; the wire cannot |

## 2. Results

### 2.1 Playback controls (`02`) - 19 pass / 3 fail

Run: `pwsh windows/tools/acceptance/02-playback-controls.ps1`
Fixture: `D:\mh-media\playback-180s.mp4` (180 s, 720p, two audio tracks `eng`/`chi`, sibling `.srt`).

| check | result | observed |
|-------|--------|----------|
| player opens, engine on the hardware path | pass | `1280x720 H.264 hwdec=d3d11va bt.1886 aac 25fps` - not the software fallback |
| position advances by itself | pass | +3 s over a 3 s wait |
| play/pause control stops the clock | pass | frozen for 3 s |
| second press resumes | pass | advances again |
| Space key | pass | 0:28 -> 0:28 - **but see the finding below** |
| seek forward button | **fail** | 0:30 -> 0:52: +22 s where the step is +10 s (7-16 s expected). The 10 s step exists but the observed jump was twice the step; the measurement window is bounded by the harness, so this is recorded as unconfirmed rather than a proven defect |
| seek back key (LEFT) | pass | about -10 s |
| seek slider click at 70% | pass | lands within +-8% of 70% of the duration |
| volume to 25 | pass | slider reads 25; audio peak falls |
| volume back to 100 | pass | reads 100 |
| mute | pass | session active (state 1) and peak 0.088561 -> 0.000000 while the position keeps advancing |
| unmute | pass | peak back to 0.088558 |
| speed options | pass | more than one rate offered |
| 2.0x doubles the rate | pass | measured 1.167x -> 2.167x |
| back to 1.0x | pass | rate back to about 1 |
| panel buttons reachable | pass | located by tooltip/HelpText (no AutomationId on either) |
| audio tracks listed | pass | `eng aac 1ch`, `chi aac 1ch` |
| audio track switch | pass | selection moves and stays |
| subtitle tracks listed | pass | `关闭字幕` + 1 track |
| external subtitle discovered | pass | `未知语言 subrip 外挂 srt` |
| select "关闭字幕" | **fail** | after clicking the row the selected entry is still the external subtitle |
| final state | not executed | the sweep crashed at the last evidence call on a harness bug (since fixed); the checks above are from that run |

**Finding (usability, not a crash).** The player opens with XAML keyboard focus on a Button
(`player.playpause` in this run, `player.back` in an earlier one). The tooltip advertises `空格` for
play/pause, but a Space press is consumed by the focused button first, so the shortcut does not reach
`PlayerPage.OnKeyDown`. It happened to produce the right outcome when the focused button was
play/pause and the wrong one - leaving the player - when it was the back button. A key that is
advertised in a tooltip should not depend on where the focus happened to land.

### 2.2 Fullscreen (`03`) - 7 pass / 0 fail

| check | result | observed |
|-------|--------|----------|
| baseline windowed | pass | 3048x1960 with caption |
| `F` enters | pass | 3048x2032 (the monitor), `WS_CAPTION` cleared, video 3048x1724 -> 3048x1830 |
| `F` leaves | pass | caption and windowed frame return |
| `F11` enters | pass | same as `F` |
| `ESC` leaves full screen, player stays | pass | frame restored, video surface still present |
| `ESC` windowed exits the player | pass | player torn down, shell back |
| shell restored | pass | navigation chrome visible |

### 2.3 Lifecycle (`01`) - 8 pass / 0 fail

| check | result | observed |
|-------|--------|----------|
| start | pass | one process, visible window, player open, rendering surface |
| minimise | pass | `IsIconic` true, process alive |
| restore | pass | not iconic, surface back |
| resize to 900x620 | pass | frame within 20 px of the request |
| video follows the resize | pass | engine surface re-placed to the new width |
| leave the player | pass | seek bar gone, navigation chrome back |
| close normally | pass | exits on `WM_CLOSE`, 0.3-0.7 s, no force kill |
| restart | pass | one process, shell shown, player NOT auto-opened |

### 2.4 Resource release (`05`) - 5 pass / 1 not verified

| check | result | observed |
|-------|--------|----------|
| resources while running | pass | engine child window present, audio session active (peak 0.0888) |
| loopback relay open | **not verified** | the remote continue-watching item could not be started, so the relay was never created; the local-file path owns no listener. This claim is the one the task asks about explicitly and it is NOT established |
| exit was normal | pass | `method=graceful`, so the release claims below are meaningful |
| no process remains | pass | 0 live `MediaHub` processes |
| no listener remains | pass | every port the app held is free; no socket attributed to the old pid |
| no engine remains | pass | no `mpv`-class window on the desktop, no `mpv`/`ffmpeg` process, audio session reports `NO_SESSION` |

The distinction the task demands is kept: the run above ended through a normal window close.
`Stop-MhAppGracefully` returns `graceful` / `still-running-after-timeout` and `Stop-MhAppForced` is a
separate call; a forced kill can never be reported as a pass because the release checks are skipped
by a failed `release.exit-was-normal`.

## 3. Harness defects found (and why they matter)

1. **Foreground was not enforced.** `SetForegroundWindow` from a background process is refused, and
   every synthetic keystroke then goes to whatever window is on top. A full run reported 5 product
   failures that were entirely a focus problem. `Set-MhForeground` now attaches to the foreground
   thread's input queue and retries; area 03 went 3/7 -> 7/7.
2. **`SetFocus` on the top-level window cleared XAML focus.** Added while fixing (1), it made the
   framework hand focus to the first focusable element - the menu bar. `SPACE` then opened the File
   menu instead of pausing. Removed; raising a window must not move focus inside it.
3. **`@(& $shot 'x' (Get-WinRect), (& $dump 'x'))` is one argument that is an array.** It killed two
   area sweeps mid-way and cost their evidence files. Evidence is now gathered by functions.

A fourth lesson is not a defect but belongs in the record: **an area script must never be run while
another foreground window is competing for input.** Area 02 was re-run in the background while other
tool calls opened windows, and produced nine consecutive false failures. `run-all.ps1` runs the areas
strictly one at a time for that reason.

## 4. What was NOT verified

- **Area 04 - file menu (`打开视频`, `打开文件夹`), drag and drop (single and multiple) and temporary queue order.**
  Not implemented in this round. The native file picker and the OLE drop source both need dedicated
  drivers; neither was written, so nothing about them is claimed.
- **Loopback relay release** after a remote playback (2.4) - the remote item could not be started.
- **Subtitle rendering on screen** - the harness reads the track list and the selection, not the pixels
  of the rendered subtitle; no OCR or pixel assertion was attempted.
- **Long-run behaviour**: nothing was run for more than a few minutes; no memory or handle growth was
  measured.
- **Multiple monitors, different DPI settings, and windowed mode at other sizes** - one 150% display
  and two window sizes only.
- **Playback over the network** (Jellyfin/Emby) - the areas use a local fixture; remote playback is
  covered by the Lead's W4 evidence, not here.

## 5. Reproducing

```powershell
pwsh windows/tools/acceptance/media/make-fixtures.ps1        # D:\mh-media, needs ffmpeg
pwsh windows/tools/acceptance/run-all.ps1                    # runs the areas one at a time
# results: windows/tools/acceptance/evidence/<area>/results.{json,md}
```
