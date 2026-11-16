# PACKAGING — distribution forms, deployment, association, update and signing

- Date: 2026-10-14 (W3-I), revised 2026-11-12 (release pass). Author: license-dev.
- Status summary: **portable self-contained tree plus a per-user PowerShell installer kit, unsigned
  (SIGNING_BLOCKED), x64 only, no MSIX, no code signing, no update mechanism. The GPL obligation no
  longer blocks release: since 2026-11-12 the kit carries the LGPL-2.1-or-later engine and the FFmpeg
  DLLs beside it ([LICENCE-DECISION.md](LICENCE-DECISION.md) §9). What still blocks a *trustworthy*
  install is the missing code-signing certificate, not a licence.**
- Every "current fact" below was re-measured this session rather than copied from an ADR; the
  2026-11-12 numbers come from `windows/artifacts/setup/MediaHub-Setup-win-x64-1.0.0`.

## 0. Machine-readable status (checked by CI)

The block below is read by `windows/tools/check-licence-surface.ps1` (check `packaging-claims`, W39)
and compared with the repository itself. If a signing step, a certificate, a `.appxmanifest`,
a WiX/Inno/NSIS project or a changed packaging property in `MediaHub.App.csproj` appears, the
gate fails until this block and section 1 are updated together - the document must not keep claiming
the old state while CI stays green.

```text
signing-status: blocked-no-certificate
packaging-form: portable-zip-self-contained
msix: not-implemented
installer: per-user-powershell-implemented
clean-machine-install: not-run
release-status: gpl-obligation-resolved-signing-still-blocked
licence-files-in-package: staged-by-ci
```

The same seven facts are described in prose in section 1; section 1 is the human reading of this
block, not a second source of truth.

## 1. Current state (verified)

| Fact | Measured value | Evidence |
| --- | --- | --- |
| Packaging form | **unpackaged, self-contained portable directory** | `MediaHub.App.csproj`: `WindowsPackageType=None`, `WindowsAppSDKSelfContained=true`, `SelfContained=true`, `RuntimeIdentifier=win-x64` |
| Windows App SDK deployment mode | **self-contained** (the runtime ships inside the package) | same lines; §3 |
| Installer (2026-11-12) | **per-user PowerShell installer** — `windows/installer/install.ps1`, `uninstall.ps1`, `install.cmd`. No elevation, no third-party installer runtime, no prerequisite install | §2.2; run recorded in §2.3 |
| Platform | **Windows 10 version 1809 (build 17763) or later, x64 only** | `TargetPlatformMinVersion=10.0.17763.0` and `RuntimeIdentifier=win-x64` in `MediaHub.App.csproj`; the only pinned engine asset is `x86_64-w64-mingw32` output; no arm64 build exists (§2.4) |
| Publish produced by CI | **yes** — `.github/workflows/windows-ci.yml` (`dotnet publish … -c Release -p:Platform=x64 -o ./artifacts/portable`) | file read |
| Smoke check | CI asserts `MediaHub.exe`, `libmpv-2.dll`, `MediaHub.dll`, `MediaHub.Net.dll`, `MediaHub.Providers.dll` exist and prints the size | file read |
| Artifact publication | `actions/upload-artifact@v4`, name `mediahub-windows-${sha}`, **retention 14 days** — a workflow artifact, not a release | file read |
| Signing step in CI | **none** — no `signtool`, no certificate secret anywhere in `.github/workflows/` | grep of `.github/workflows` |
| Signature status of the built binaries | `NotSigned` for both `MediaHub.exe` and `libmpv-2.dll` | `Get-AuthenticodeSignature` |
| MSIX | **not implemented** — no `Package.appxmanifest` anywhere. `EnableMsixTooling=true` only enables tooling | glob + csproj |
| Installer project (WiX/Inno/NSIS) | **none** — the installer is the PowerShell kit above, not an MSI/EXE bootstrap | glob; the gate looks for `.wixproj/.wxs/.iss/.nsi/.msix` |
| File associations | **implemented in the app** — `HKCU\Software\Classes`, candidate registration only, never writes `UserChoice`; the uninstaller removes those keys | `MediaHub.App/Services/FileAssociationService.cs`; §4 |
| Update mechanism | **not implemented** — a newer kit is installed over the old tree | §7 |
| Measured kit (2026-11-12, LGPL engine, rebuilt after the installer note was corrected) | **562 files, 248,000,998 bytes = 236.5 MiB**; zip `MediaHub-Setup-win-x64-1.0.0.zip` = 100,624,141 bytes (96.0 MiB) | `windows/artifacts/setup` |
| Zip SHA-256 | `4c369da549c69e752b648c57502111b707e0780ee471cccd7921787b11cdb235` | `Get-FileHash` |
| Engine inside the kit | `libmpv-2.dll` 3,327,274 bytes, SHA-256 `a3f901d6b5bae54a4b5b87cd9d9d973fd3af079aa70a61851560c30e74722a68` (LGPL-2.1-or-later, `mpv v0.41.0`, feature list without `gpl`) **plus 33 dependency DLLs** (FFmpeg `av*`/`sw*`, libass, libplacebo, libshaderc, libstdc++-6 …), 34 files / 62,153,850 bytes in total, all flat next to `MediaHub.exe` | `windows/tools/make-install-kit.ps1` output + `Get-ChildItem`/`Get-FileHash` on the staged tree |
| Licence/notice files in the package | **7** — six licence/notice/inventory documents plus a digest manifest, staged into `licences/` by `windows/tools/stage-licence-files.ps1` (W40; the kit runs it right after publish) | see [SBOM.md](SBOM.md) §4.1 |

Size composition (measured on the kit): the engine set is 59.3 MiB (62,153,850 B: the 3.2 MiB core plus
33 dependency DLLs); the remaining 177.2 MiB is the .NET 10 runtime, the self-contained Windows App SDK,
the SDK projections and the app. The Windows ML runtime is excluded by the kit (§2.5); the full publish
before that trim is ~331 MiB ([SBOM.md](SBOM.md)). The kit got 56.1 MiB smaller than the 2026-10-14 GPL
kit (292.6 MiB) because the single 115.4 MiB static engine was replaced by the dynamic set.

## 2. The three packaging forms, analysed

| | **A. Portable x64 zip (buildable)** | **B. Per-user unpackaged installer (shipped)** | **C. MSIX (not implemented)** |
| --- | --- | --- | --- |
| What ships | the publish tree, zipped | same tree + `install.ps1`/`uninstall.ps1`/`install.cmd` | `.msix` package containing the tree |
| Install action | unzip | per-user copy to `%LOCALAPPDATA%\Programs\MediaHub` + shortcut + HKCU uninstall entry; no elevation | `Add-AppxPackage` / double-click; per-user activation |
| App identity | none | none (unless a sparse package is added) | **package identity** (Name/Publisher/Version) |
| File associations | manual `HKCU\Software\Classes` writes by the app itself | the app writes them at runtime (candidate only); `uninstall.ps1` removes them | **declarative** in the manifest; the OS registers/unregisters them on install/remove |
| Uninstall | "delete the folder" — leaves user data | `uninstall.ps1` (also exposed in Settings > Apps); user data kept by default | OS removal; app's virtualised data is removed with the package |
| Update | re-download and replace the folder | re-run a newer kit over the old tree | App Installer or Store auto-update (still needs a signed, versioned package) |
| Signing | not strictly required (but SmartScreen will warn) | **not required to install**; SmartScreen warns until signed (§2.2) | **required** — a package must be signed to install |
| Blockers today | none for licence (unsigned, so SmartScreen warns) | no signature (SmartScreen warns); nothing licence-related | everything in A/B **plus** no manifest **plus** identity/versioning work |
| Effort | done | done (2026-11-12) | medium-high (manifest, identity, versioning, signing, clean-machine test) |

A and B are buildable and were installed and run (§2.3); C does not exist. The licence blocker that
used to apply to all three is gone — all three would now carry the same LGPL engine set — so what
remains between this tree and a *trustworthy* release is the signature, the clean-machine install and
the MSIX identity work, none of which depends on the packaging form being re-decided.

### 2.1 Why the portable form was chosen (and where it stops being enough)

ADR-W004 records the measurements that drove the choice: the default framework-dependent publish
**failed at launch** with

> `Required components of the Windows App Runtime are missing. Version 1.8 (MSIX package version >= 8000.994.2142.0)`

while `WindowsAppSDKSelfContained=true` + `SelfContained=true` launched from a copied folder with no
runtime install. That is a real, reproduced observation and it still holds — this machine has no
Windows App Runtime installed.

Where it stops being enough: update and trust. Associations and uninstall are now handled (the app
registers itself as a candidate; the installer kit unregisters it), but there is no update channel and
no signature. Those are exactly the MSIX upside, and they are the reason the roadmap keeps MSIX as the
second channel (`windows/docs/ROADMAP-WINDOWS.md` W5: "便携包 + MSIX 双通道构建；代码签名；干净机安装实测" —
**not started**: no manifest, no certificate, no clean-machine test).

### 2.2 Installer vs MSIX (decision, 2026-11-12)

| | **Per-user unpackaged installer (chosen)** | **MSIX (not chosen this round)** |
| --- | --- | --- |
| Build tooling | PowerShell 5.1 (in-box) + `WScript.Shell` COM for shortcuts + `Compress-Archive` for the zip | `MakeAppx`/`SignTool` from the Windows SDK, a `Package.appxmanifest`, a certificate |
| Runtime prerequisite | none — the payload is self-contained | none for the app, but the package **must be signed** |
| Install location | `%LOCALAPPDATA%\Programs\MediaHub`, per user, no elevation | package store, per user |
| Uninstall | `uninstall.ps1` + `HKCU\...\Uninstall\MediaHub` (Settings > Apps) | OS removal |
| Data on uninstall | `%LOCALAPPDATA%\MediaHub` kept by default (`-RemoveUserData` erases it) | virtualised data is removed **with the package** — settings/vault/history silently lost |
| Associations | written by the app at runtime (HKCU, candidate only) | declarative, OS-managed |
| Signing | not required to install; SmartScreen warns | **mandatory**, subject must match `Publisher` |
| Work remaining | done | manifest + identity/version + signing + clean-machine test |

Why the unpackaged installer: (1) it ships exactly the tree that was already verified to launch, so the
tested artifact and the delivered artifact are the same bytes; (2) MSIX data virtualisation would change
where settings, the DPAPI credential vault and history live, and would delete them on uninstall — a
behaviour change that needs its own design and test; (3) an MSIX cannot be installed at all without a
signing certificate, which this project does not have; (4) the app already writes its own HKCU
associations, so MSIX's main free win does not apply yet. Cost of this choice: no automatic update, no
package identity, and SmartScreen warnings until the binaries are signed.

**SmartScreen and certificate cost (unsigned kit today).** A downloaded zip carries the Mark-of-the-Web,
so the zip and the extracted `install.cmd` trigger *"Windows protected your PC"* until the user picks
*More info → Run anyway*. An unsigned binary has no reputation at all, and repacking resets whatever
reputation a previous download accumulated. The honest options:

- **OV code-signing certificate** — market range roughly US$200–500/year, a hardware token/HSM is
  required since the 2023 CA/B rules, and SmartScreen reputation still has to accumulate;
- **EV certificate** — roughly US$300–700/year, immediate SmartScreen trust for the signer, same
  hardware-token logistics;
- **Azure Trusted Signing** — subscription-based (single-digit US$/month at the entry tier), short-lived
  certificates, needs an Azure account and identity validation;
- **self-signed certificate** — free but useless for end users: they must first install the certificate
  into `Trusted People`/`Trusted Root`, which is worse than the warning itself.

Those prices are market ranges, **not quoted or verified in this session**; procurement is a product
decision. Signing the app no longer raises a licence question for the engine: the shipped
`libmpv-2.dll` is LGPL-2.1-or-later and is meant to be replaced by the user (LGPL §6(a)), so signing it
is an ordinary distribution act rather than a re-licensing decision (§9).

### 2.3 What was actually run (2026-11-12)

| Step | Result | Evidence |
| --- | --- | --- |
| `windows/tools/make-install-kit.ps1` | kit built twice on 2026-11-12: first 526 files / 292.6 MiB (GPL engine), then **rebuilt with the LGPL engine set — 562 files / 236.5 MiB, zip 96.0 MiB + `.sha256`** (that is the tree in §1); rebuilt once more the same day after the installer's engine note was corrected (payload unchanged, `libmpv-2.dll` still `a3f901d6…2a68`), which is the measurement in §1 | `windows/artifacts/setup` |
| `install.cmd` (double-click path) | copied the 526 payload files plus `uninstall.ps1` to `%LOCALAPPDATA%\Programs\MediaHub`, wrote the Start Menu shortcut and the HKCU uninstall entry, started the app | install log + registry read-back |
| Shortcut | `MediaHub.lnk` target/workdir/icon all point at the installed `MediaHub.exe`; launching the shortcut from the shell starts `MediaHub.exe` from the install directory (window title `MediaHub`) | COM read-back + process check |
| Icon | `MediaHub.exe` carries the four-size application icon (extracted 48×48); the shortcut uses `MediaHub.exe,0` | `ExtractAssociatedIcon` |
| First launch against a real server | 32 libraries listed (incl. Jellyfin Movies/TV Shows); DirectPlay of `Long Resume (2026)`; OSD 00:00:59.875 → 00:01:12.125; server logged `started playback of '"Long Resume (2026)"' ("MediaHub" "0.1.0")` | UIA + server log |
| Uninstall via `UninstallString` | program files, shortcuts and the HKCU keys removed; `%LOCALAPPDATA%\MediaHub` kept | filesystem + registry |
| Reinstall over the existing tree | succeeded (copy-in-place, not delete-then-copy) | install log |
| **562-file LGPL tree: install** | `install.ps1 -Launch` exited 0, reported `engine libmpv-2.dll 3327274 bytes sha256 a3f901d6…2a68`, `copied 560 file(s)`, wrote the Start Menu shortcut, the HKCU uninstall entry (`MediaHub`, 242175 KB) and started the app | install log + `Get-ChildItem`/`Get-FileHash`/registry read-back on the installed tree |
| **562-file LGPL tree: shortcut** | `MediaHub.lnk` target `…\Programs\MediaHub\MediaHub.exe`, workdir `…\Programs\MediaHub`, icon `MediaHub.exe,0`; the installed app was launched from it | COM read-back |
| **562-file LGPL tree: first playback** | from `媒体库 → 本地存储`, `bbb720.mp4` played with no `播放失败`/`loading failed`; info line `1280×720 · H.264 … · hwdec=d3d11va · bt.1886 · 30fps · 掉帧 1`, progress `0:09 / 0:10` | UIA (desktop agent) on the installed tree |
| **562-file LGPL tree: uninstall** | `uninstall.ps1` removed the program directory, `MediaHub.lnk` and `HKCU\…\Uninstall\MediaHub`; `%LOCALAPPDATA%\MediaHub\settings.json` survived as designed | filesystem + registry read-back |
| Installer robustness, 562-file tree | two attempts started from the desktop agent's shell failed on `vulkan-1.dll` (`access denied`) despite the built-in 10×1 s retry; the same payload installed cleanly from a normal shell twice, and a manual copy of that one file to the same target also succeeded — a transient handle/timing condition of that session, not a payload defect | install logs |
| Clean machine (no .NET, no Windows App Runtime) | **NOT RUN** | — |

The payload is self-contained, so the target machine needs no .NET and no Windows App Runtime. That was
verified by running the installed tree on this machine, which *has* both — it is **not** a clean-machine
proof, and it is recorded as not run above.

The install/shortcut/uninstall rows above were produced against the 526-file (GPL-engine) tree; the
562-file LGPL tree rows were added on 2026-11-12 and are the ones that describe what ships. The
installed tree was 560 files (the kit's two scripts are handled separately), and the engine was checked
by digest after the copy, not by trusting the log.

What a clean-machine test would still need: a Windows 10 1809+ / Windows 11 VM or a freshly reset
physical machine with **no** .NET SDK, no .NET desktop runtime, no Windows App Runtime installed, then
install from the zip and repeat the four steps above plus one local playback. Nothing on this machine
qualifies — it has the SDK, the runtimes and the app's own data — so it stays **NOT RUN** rather than
being inferred from a self-contained publish.

### 2.4 Platform floor: x64 only

The engine is a PE32+ `x86_64-w64-mingw32` build, the only pinned asset is x64, and the LGPL candidate is
x64 as well. `MediaHub.App.csproj` sets `RuntimeIdentifier=win-x64` and `Platforms=x64`, so this
repository cannot produce an arm64 build today — do not promise one. `TargetPlatformMinVersion` is
`10.0.17763.0`, i.e. Windows 10 version 1809 or later; the installer itself needs Windows PowerShell 5.1
(in-box on Windows 10/11). Neither the floor nor arm64 has been exercised on real hardware.

### 2.5 Windows ML runtime excluded from the kit

The Windows App SDK meta-package drags in `DirectML.dll` + `onnxruntime.dll` (~41 MB) that the app never
calls. `make-install-kit.ps1` removes those files from the staged tree after publish (it prints
`kit: excluded …`), which is why the kit is 236.5 MiB instead of ~331 MiB. The app was then launched from
the trimmed kit and played. The CI portable artifact still contains them — only the kit trims.

## 3. Windows App SDK runtime deployment: self-contained vs framework-dependent

| | **Self-contained (current)** | **Framework-dependent** |
| --- | --- | --- |
| Runtime location | inside the package (`Microsoft.WindowsAppRuntime*.dll`, `Microsoft.ui.xaml.dll`, `Microsoft.WinUI.dll`, …) | installed machine-wide as the Windows App Runtime MSIX framework package |
| Measured result here | **works**: app starts from a copied folder | **fails** on this machine: "Required components of the Windows App Runtime are missing. Version 1.8 (MSIX package version >= 8000.994.2142.0)" (ADR-W004, reproduced fact) |
| Package size | larger by ~90 MB (measured: Windows App SDK natives + managed ≈ 103 MB) | smaller; the framework is shared |
| Update of the runtime | ours: every release replaces it | Microsoft's: serviced independently |
| Prerequisite for the user | none | Windows App Runtime 1.8 must be present |
| Packaging interaction | works unpackaged; also allowed in MSIX | allowed in MSIX (framework dependency declared) |

The two decisions are independent — Microsoft's own guidance treats packaging model and Windows App
SDK deployment mode as separate choices
([Package and deploy Windows apps](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/),
[deployment guide for unpackaged apps](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/deploy-unpackaged-apps)).
Consequence for us: **self-contained is the only mode that meets the "no prerequisites" goal**, which
means every packaging form keeps paying the ~103 MB.

**Side finding:** the Windows App SDK meta-package also drags in the Windows ML runtime
(`onnxruntime.dll` 20.7 MB + `DirectML.dll` 18.7 MB) that the app never calls — see
[SBOM.md](SBOM.md) §4.2. Referencing the individual Windows App SDK components instead is the
candidate fix, owned by whoever owns `windows/src`, and must be validated by a publish + launch test.

## 4. File type association — per form

**State: implemented in the application** (`MediaHub.App/Services/FileAssociationService.cs`), for
every form that runs it. It writes `HKCU\Software\Classes\MediaHub.Video`, the
`Applications\MediaHub.exe` shell entry and `OpenWithProgids` candidates for the 18 recognised
extensions. It deliberately does not write `UserChoice`; `uninstall.ps1` removes the keys it created.
There is still **no manifest extension** (MSIX is not implemented).

| Form | How registration would work | Who unregisters | Notes / risks |
| --- | --- | --- | --- |
| Portable / installer kit | the app writes per-user keys under `HKCU\Software\Classes`: a ProgId (`MediaHub.Video`) with `shell\open\command`, plus `Applications\MediaHub.exe\SupportedTypes` and `OpenWithProgids` entries. **Requires an executable path that stays valid** — the installer fixes that path, a moved portable folder breaks it | the app's own "remove association" action and `uninstall.ps1` | per-user only (no admin); Windows 10+ protects the *default* handler choice — the app registers as a *candidate* and the user picks the default in Settings; it cannot silently become the default |
| EXE/MSI installer | the installer writes the same registry shape (per-machine `HKLM` or per-user `HKCU`) and can then invoke the shell to refresh associations | the installer's uninstall removes the keys it created | per-machine needs elevation; MSI/WiX models each key as a component so uninstall is clean |
| MSIX | declared in `Package.appxmanifest` (`uap:Extension Category="windows.fileTypeAssociation"` with `uap:SupportedFileTypes`) — see [Integrate your desktop app with Windows by using packaging extensions](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/desktop-to-uwp-extensions) | **the OS**, on package removal | declarative, no registry writes by the app; also the only form where associations are cleaned up for free; the app cannot change them at runtime |

## 5. Uninstall and data retention

### 5.1 Where user data lives today (read from the source)

| Data | Path | Evidence |
| --- | --- | --- |
| Credential vault (DPAPI `CurrentUser`, versioned, with a per-installation entropy salt beside it) | `%LOCALAPPDATA%\MediaHub\credentials\credentials.vault.json` (+ entropy file) | `MediaHub.Net/Security/CredentialVault.cs` lines 174, 200–205 |
| Settings (servers, preferences) | `%LOCALAPPDATA%\MediaHub\settings.json` | `MediaHub.App/Services/SettingsStore.cs` lines 13–16 |
| Playback history / "continue watching" | `%LOCALAPPDATA%\MediaHub\history.json` | `MediaHub.App/Services/PlaybackHistoryStore.cs` lines 25–27 |
| Artwork cache | `%LOCALAPPDATA%\MediaHub\artwork\*.img` | `MediaHub.App/Services/ImageCache.cs` lines 44–46 |
| Startup trace | `%LOCALAPPDATA%\MediaHub\startup.log` | `MediaHub.App/Services/StartupTrace.cs` lines 14–15 |

Nothing is written to `%APPDATA%`, `ProgramData`, `HKLM`, or the install folder (the engine's
`input.conf` is read from the install directory, not written there).

### 5.2 Per form

| Form | Uninstall behaviour | Data outcome |
| --- | --- | --- |
| Portable tree (unzip only) | there is no uninstaller; deleting the folder is the uninstall | `%LOCALAPPDATA%\MediaHub` **survives** — including the credential vault and playback history |
| **Per-user installer kit (shipped)** | `uninstall.ps1` removes program files, the Start Menu (and optional desktop) shortcut, the HKCU uninstall entry and the association keys it created, then deletes the install directory (3 retries) | `%LOCALAPPDATA%\MediaHub` **survives by default**; `-RemoveUserData` erases settings, vault, history and artwork. The kit's `install.ps1` never touches that directory |
| MSIX | package removal by the OS | an MSIX app's writes to `%LOCALAPPDATA%` are redirected into the package's virtualised store and are **removed with the package**; a user who "uninstalls to clean up" loses settings and the vault — a behaviour change to document before shipping MSIX |

The data story is now implemented for the kit (keep by default, erase on request) but still only
documented here: there is no in-app "delete my data" button, and the installer does not prompt. The
credential vault is DPAPI-bound to the Windows account, so a leftover file is not readable by another
user, but a user-facing explanation is still missing.

## 6. Application versioning

Current state — **three different version notions, none authoritative**:

| Notion | Value | Where |
| --- | --- | --- |
| Assembly/File version | `1.0.0.0` | SDK default; **no `<Version>`/`<FileVersion>` property exists anywhere in `windows/`** (grep) |
| Informational version | `1.0.0+b07b92146a6c2f7d967e1d10901e9821d2b49ee0` | `MediaHub.exe`/`MediaHub.dll` `ProductVersion`; the SDK injects the git revision |
| Application manifest identity | `0.1.0.0` | `MediaHub.App/app.manifest` line 3 |

Problems: the shipped binary advertises `1.0.0` while the manifest says `0.1.0.0`; neither reflects
the product's actual phase; and MSIX requires a four-part `Identity/@Version` that must increase with
every submission, which cannot be derived from anything that exists today. Also, a build from a tree
without `.git` silently loses the revision suffix (measured — [REPRODUCIBLE-BUILD.md](REPRODUCIBLE-BUILD.md) §3.3).

**Recommended (single source of truth, for the owner of `windows/Directory.Build.props`):** one
`<Version>` property, consumed by the assembly, the manifest, and the package identity; release
builds must come from a checkout so the revision suffix is present.

## 7. Update mechanism

**State: NOT_IMPLEMENTED** (no update code in `windows/`; grep for updater/release-check → 0 matches).

| Form | Available mechanism | What it needs |
| --- | --- | --- |
| Portable | none — the user re-downloads. (The repository's README advertises GitHub Releases for the *Android* APK; no Windows release exists) | a documented download location and published checksums |
| EXE/MSI | none built-in | an updater (custom, or a framework such as Squirrel/MSIX-based); each update re-verifies the engine digest |
| MSIX | App Installer / Store auto-update | a signed package with monotonically increasing version, an update endpoint, and a decision about the Store vs sideload channel |

Additional constraint for every option: an update replaces `libmpv-2.dll`, so the **pinned digest
check must run before the new engine is installed**, exactly as `fetch-libmpv.ps1` does at build time.

## 8. Artifact hashes

- **Install kit (2026-11-12, rebuilt with the LGPL engine):** `MediaHub-Setup-win-x64-1.0.0.zip`
  **100,624,025 bytes** (96.0 MiB), SHA-256
  `20cb0de5f4456597a609621ae6ac2420a3c187249dce39e5269a56f3ba3bb499`; the kit writes the same
  digest to `MediaHub-Setup-win-x64-1.0.0.zip.sha256` next to the zip. The hash is a property of *this*
  zip operation — zip bytes depend on entry order, timestamps and the compressor, so it identifies a
  file, not a build. (The earlier same-day GPL-engine zip was 122,194,799 bytes,
  `30592a0a…a5f0`; it is superseded.)
- Earlier portable zip (2026-10-14), kept for comparison: `MediaHub-portable-win-x64.zip`
  139,338,968 bytes, SHA-256 `002daf3559f5bdd37d172fe725ce38b6be203a56192a6fcbdf3ff7e75f92618e`.
- The build-**identity** hash that matters is the engine's:
  `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9`, verified by
  `fetch-libmpv.ps1` and re-verified on the staged kit this session.
- **Gap:** CI uploads the artifact but publishes **no checksums file and no build provenance**
  (`windows-ci.yml`). The kit does produce a `.sha256`, but a release process still needs `SHA256SUMS`
  attached to the release (and, ideally, a build-provenance attestation).

## 9. Code signing

**Status: SIGNING_BLOCKED — no certificate exists in this project. Do not imply otherwise.**

Evidence: `Get-AuthenticodeSignature` returns `NotSigned` for `MediaHub.exe` and `libmpv-2.dll`;
no signing step, no certificate secret and no `signtool` invocation exists anywhere in
`.github/workflows/`.

What is required, per form:

| Form | Requirement |
| --- | --- |
| Portable | not technically required to run, but unsigned binaries get SmartScreen warnings and no reputation; distribution at any scale wants an OV or EV certificate (or Azure Trusted Signing) with an RFC-3161 timestamp |
| EXE/MSI | signature of both the installer and the payload binaries, for the same trust reasons |
| MSIX | **mandatory**. The package must be signed with a certificate whose subject matches the manifest's `Publisher`, and the manifest must carry an `AppxManifest.xml` with a four-part version. A self-signed certificate is for testing only — users must install and trust it; production requires a certificate from a trusted source ([Create a certificate for package signing](https://learn.microsoft.com/en-us/windows/msix/package/create-certificate-package-signing), fetched) |

Additional consequence: the engine is now LGPL, so signing `libmpv-2.dll` with our certificate is
compatible with the licence (the LGPL asks that the user be able to replace it, not that it stay
unsigned). Today it is simply left unsigned, like the rest of the payload.

What signing would need here, concretely: a code-signing certificate that this machine does not have —
an **OV** certificate (identity-validated, ~1–2 weeks of organisation validation) or an **EV** one
(hardware token / HSM, immediate SmartScreen reputation), bought through a public CA such as DigiCert,
Sectigo or GlobalSign; or **Azure Trusted Signing**, which needs an Azure subscription, a validated
identity and a Trusted Signing account. Whichever is chosen, signing means `signtool sign /fd sha256
/tr <RFC-3161 URL> /td sha256` over `MediaHub.exe`, `libmpv-2.dll` and the installer payload, with the
private key in a token/HSM/Key Vault — never a certificate file committed to the repository. The test
certificate used for MSIX would not help: it only works after the user installs and trusts it.

## 10. Gaps, blockers, and the order to fix them

| # | Gap | Severity | Owner |
| --- | --- | --- | --- |
| 1 | **GPL engine** — **resolved 2026-11-12**: the kit, the default build and the CI publish carry the LGPL-2.1-or-later engine in `windows/engine/lgpl/dist` (FFmpeg dynamically linked) | resolved ([LICENCE-DECISION.md](LICENCE-DECISION.md) §9) | licence decision → build |
| 2 | No licence/notice files in the package — **addressed (W40)**: `licences/` now carries six licence/notice/inventory documents plus a digest manifest, and a gate re-runs the staging script | resolved (W40); verbatim LGPL/GPL texts remain a licence decision | packaging + licence |
| 3 | No code-signing certificate — `SIGNING_BLOCKED` | **BLOCKER** for a trustworthy install; otherwise every download warns | product owner (procurement), then CI |
| 4 | File associations | **implemented** (HKCU candidates); becoming the default remains the user's choice in Settings | app |
| 5 | Uninstall/data story | **implemented for the kit** (§5.2); no in-app erase button and no user-facing explanation yet | packaging + app |
| 6 | No version single-source-of-truth | high (MSIX identity, support) | `windows/Directory.Build.props` owner |
| 7 | No update mechanism | medium | packaging |
| 8 | No `SHA256SUMS`/provenance in CI | medium | CI owner |
| 9 | ~40 MB unused Windows ML runtime shipped | **trimmed from the kit** (§2.5); still present in the CI portable artifact | `windows/src` owner |
| 10 | 6 `.pdb` files shipped; 83 locale folders shipped under `InvariantGlobalization=true` | low | packaging |

Recommended order: **3 → 6 → 5 → 8 → 4 → 7 → 9/10.** Item 1 is closed. Items 4 and 7 should not start
before the packaging form is decided, because MSIX makes association registration free and updatable
while the portable form requires both to be written as application code.

## 11. UNKNOWN

| # | Unknown | Resolution needed |
| --- | --- | --- |
| U1 | Whether a clean machine (no .NET, no Windows App Runtime) runs the current package | the clean-machine test promised in ADR-W004 is still **NOT_RUN**; needs a VM/snapshot |
| U2 | Real-world size/time of an MSIX build for this app | not attempted; needs a `Package.appxmanifest` |
| U3 | Whether `EnableMsixTooling=true` with `WindowsPackageType=None` has any effect today | it is set in the csproj but no manifest exists; resolve when MSIX work starts |
| U4 | Certificate acquisition path (OV vs EV vs Trusted Signing) and cost | commercial decision |
| U5 | How much further the 236.5 MiB kit can be trimmed | the Windows ML runtime (~41 MB) was removed and the trimmed kit launched and played; deeper trimming (locale folders, `.pdb`) is untested |
| U6 | The exact behaviour of MSIX data virtualisation for this app's `%LOCALAPPDATA%\MediaHub` paths | requires an actual MSIX build and an uninstall test |
