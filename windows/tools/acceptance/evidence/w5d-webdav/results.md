# w5d-webdav-gui-loop - GUI acceptance result

- started: 2026-10-14T08:13:41.2664488Z
- finished: 2026-10-14T08:15:19.1666654Z
- dpi aware process: True (scale 1.5)
- checks: pass=12 fail=2 not-verified=1 info=0

| id | result | input | assertion | evidence |
|----|--------|-------|-----------|----------|
| env.server | pass |  | a real WebDAV server (WsgiDAV) answers on the loopback address with the diagnostic shim disabled | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\webdav\start-server.json |
| env.app | pass |  | the client is up with a visible window and a clean trace | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\00-app-start.txt |
| add.dialog-open | pass |  | the add-source dialog opens with a media-source-type combo box | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\02-add-dialog.png |
| add.type-selected | pass |  | the dialog really holds WebDAV as its selected source type before submitting | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\03-type-selected.txt |
| add.submit-uses-webdav | pass |  | submitting the dialog makes the server see a WebDAV request (OPTIONS/PROPFIND) - NOT the MediaBrowser /System/Info/Public probe | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\05-after-add.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\05-after-add.txt |
| add.authenticated | pass |  | the WebDAV provider authenticates with a Basic-auth request that the server answers 2xx | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\05-after-add.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\05-after-add.txt |
| add.listed-online | pass |  | the new WebDAV source is listed with its own row | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\05-after-add.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\05-after-add.txt |
| add.persisted | pass |  | exactly one WebDAV source is persisted with the configured url and user name | C:\Users\55160\AppData\Local\MediaHub\settings.json |
| browse.shell-issues-requests | fail |  | visiting the browsing surfaces makes the client ask the WebDAV server for its contents (PROPFIND) | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\06-browse-surfaces.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\06-browse-surfaces.txt |
| browse.webdav-has-surface | fail |  | the WebDAV source appears somewhere the user can browse into its folders | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\06-browse-surfaces.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\06-browse-surfaces.txt |
| play.open-webdav-item | not-verified |  | a WebDAV item can be opened and played from the UI | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\06-browse-surfaces.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\06-browse-surfaces.txt |
| exit.graceful | pass |  | the app exits on WM_CLOSE without the harness force-killing it | - |
| restart.probes-server | pass |  | at startup the client re-probes the restored WebDAV source (OPTIONS with Basic auth) | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\07-after-restart.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\07-after-restart.txt |
| restart.source-online | pass |  | the restored WebDAV row reports an authenticated session instead of needing a login | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\08-sources-after-restart.png D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\08-sources-after-restart.txt |
| reconnect.succeeds | pass |  | reconnecting an already-added WebDAV source succeeds and reaches the server | D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w5d-webdav\09-reconnect.png |
