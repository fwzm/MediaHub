# PACKAGING — distribution forms, deployment, association, update and signing

- Date: 2026-10-14 (W3-I). Author: license-dev.
- Status summary: **portable zip only, unsigned (SIGNING_BLOCKED), no installer, no MSIX, no file
  associations, no update mechanism, and blocked for release by the GPL obligation
  ([LICENCE-DECISION.md](LICENCE-DECISION.md)).**
- Every "current fact" below was re-measured this session rather than copied from an ADR.

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
installer: not-implemented
clean-machine-install: not-run
release-status: blocked-gpl-obligation
licence-files-in-package: staged-by-ci
```

The same six facts are described in prose in section 1; section 1 is the human reading of this
block, not a second source of truth.

## 1. Current state (verified)

| Fact | Measured value | Evidence |
| --- | --- | --- |
| Packaging form | **unpackaged, self-contained portable directory** | `MediaHub.App.csproj` lines 15–18: `WindowsPackageType=None`, `WindowsAppSDKSelfContained=true`, `SelfContained=true`, `RuntimeIdentifier=win-x64` |
| Windows App SDK deployment mode | **self-contained** (the runtime ships inside the package) | same lines; §3 |
| Publish produced by CI | **yes** — `.github/workflows/windows-ci.yml` lines 114–119 (`dotnet publish … -c Release -p:Platform=x64 -o ./artifacts/portable`) | file read |
| Smoke check | CI asserts `MediaHub.exe`, `libmpv-2.dll`, `MediaHub.dll`, `MediaHub.Net.dll`, `MediaHub.Providers.dll` exist and prints the size (lines 121–129) | file read |
| Artifact publication | `actions/upload-artifact@v4`, name `mediahub-windows-${sha}`, **retention 14 days** (lines 131–140) — a workflow artifact, not a release | file read |
| Signing step in CI | **none** — no `signtool`, no certificate secret anywhere in `.github/workflows/` | grep of `.github/workflows` for sign/certificate/signtool → only an unrelated Android comment |
| Signature status of the built binaries | `NotSigned` for both `MediaHub.exe` and `libmpv-2.dll` | `Get-AuthenticodeSignature` on the fresh publish |
| MSIX | **not implemented** — no `Package.appxmanifest` anywhere (`windows/**/*.appxmanifest` matches nothing). `EnableMsixTooling=true` only enables tooling | glob + csproj line 12 |
| Installer (EXE/MSI) | **not implemented** — no WiX/Inno/NSIS/Advanced Installer project in the repository | glob of `windows/**` |
| File associations | **not implemented** — no registry code and no manifest extension; grep for `fileTypeAssociation`, `SupportedTypes`, `ProgId`, `RegistryKey`, `HKCU` over `windows/` returns **0 matches** | grep |
| Update mechanism | **not implemented** — grep for updater/release-check code over `windows/` returns **0 matches** | grep |
| Measured portable package | **522 files, 347,972,263 bytes = 331.9 MB** | `dotnet publish` + `Measure-Object` |
| Measured zip of that tree | `MediaHub-portable-win-x64.zip` = 139,338,968 bytes (132.9 MB) | `Compress-Archive -CompressionLevel Optimal` |
| Zip SHA-256 | `002daf3559f5bdd37d172fe725ce38b6be203a56192a6fcbdf3ff7e75f92618e` | `Get-FileHash` |
| Licence/notice files in the package | **7** — six licence/notice/inventory documents plus a digest manifest, staged into `licences/` by `windows/tools/stage-licence-files.ps1` (W40; CI runs it right after publish and the smoke check asserts them) | see [SBOM.md](SBOM.md) §4.1 |

Size composition (measured; full table in [SBOM.md](SBOM.md)): engine 115.4 MB, Windows App SDK
natives incl. Windows ML ~93 MB, .NET 10 runtime 76.7 MB, SDK projections 25.6 MB, app 2.5 MB.

## 2. The three packaging forms, analysed

| | **A. Portable x64 zip (current)** | **B. EXE/MSI installer** | **C. MSIX** |
| --- | --- | --- | --- |
| What ships | the publish tree, zipped | same tree + installer payload | `.msix` package containing the tree |
| Install action | unzip | per-machine (HKLM) or per-user install, shortcut creation | `Add-AppxPackage` / double-click; per-user activation |
| App identity | none | none (unless a sparse package is added) | **package identity** (Name/Publisher/Version) |
| File associations | manual `HKCU\Software\Classes` writes by the app itself | installer writes `HKLM`/`HKCU` registry keys, removed on uninstall | **declarative** in the manifest; the OS registers/unregisters them on install/remove |
| Uninstall | "delete the folder" — leaves user data | installer removes its files/keys; user data handling is a design choice | OS removal; app's virtualised data is removed with the package |
| Update | re-download and replace the folder | installer re-run / custom updater | App Installer or Store auto-update (still needs a signed, versioned package) |
| Signing | not strictly required (but SmartScreen will warn) | **required** for a reasonable user experience | **required** — a package must be signed to install |
| Blockers today | GPL (§ LICENCE-DECISION); ~40 MB of unused ML runtime (the notices ship in the package since W40) | everything in A **plus** no installer authored **plus** no certificate | everything in A/B **plus** no manifest **plus** identity/versioning work |
| Effort | done | medium (authoring + install/uninstall test on a clean machine) | medium-high (manifest, identity, versioning, signing, clean-machine test) |

None of the three is *available for release* today, because the licence blocker is independent of the
packaging form: all three carry the same GPL engine.

### 2.1 Why the portable form was chosen (and where it stops being enough)

ADR-W004 records the measurements that drove the choice: the default framework-dependent publish
**failed at launch** with

> `Required components of the Windows App Runtime are missing. Version 1.8 (MSIX package version >= 8000.994.2142.0)`

while `WindowsAppSDKSelfContained=true` + `SelfContained=true` launched from a copied folder with no
runtime install. That is a real, reproduced observation and it still holds — this machine has no
Windows App Runtime installed.

Where it stops being enough: associations, uninstall, update, and trust. Those are exactly the MSIX
upside, and they are the reason the roadmap keeps MSIX as the second channel
(`windows/docs/ROADMAP-WINDOWS.md` W5: "便携包 + MSIX 双通道构建；代码签名；干净机安装实测" —
**not started**: no manifest, no certificate, no clean-machine test).

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

**State: NOT_IMPLEMENTED for all forms.** No registry code, no manifest extension
(grep evidence above). `windows/docs/ROADMAP-WINDOWS.md` W3 plans them ("文件类型关联（HKCU）+ 拖放打开");
nothing is built.

| Form | How registration would work | Who unregisters | Notes / risks |
| --- | --- | --- | --- |
| Portable zip | the app (or a first-run "set as default" action) writes per-user keys under `HKCU\Software\Classes`: a ProgId (e.g. `MediaHub.Video`) with `shell\open\command`, plus `Applications\MediaHub.exe\SupportedTypes` and `OpenWithProgids` entries. **Requires an executable path that stays valid** — a zip the user moves breaks it | the app's own "remove association" action; nothing else will do it | per-user only (no admin); Windows 10+ protects the *default* handler choice — the app may register as a *candidate* but the user picks the default in Settings; expect "cannot silently become the default" |
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
| Portable | there is no uninstaller; deleting the folder is the uninstall | `%LOCALAPPDATA%\MediaHub` **survives** — including the credential vault and playback history. Today there is no in-app "delete my data" action, and no documentation telling the user this |
| MSI/EXE installer | removes program files and registry entries | user data under `%LOCALAPPDATA%` **survives** unless the installer is explicitly built to offer removal (it must ask, not assume: silently deleting credentials is hostile, silently keeping them is undocumented) |
| MSIX | package removal by the OS | an MSIX app's writes to `%LOCALAPPDATA%` are redirected into the package's virtualised store and are **removed with the package**; a user who "uninstalls to clean up" loses settings and the vault — this is a behaviour change to document before shipping MSIX |

**Required work (not done):** an explicit, documented data story — where data lives, what uninstall
removes, and a supported way to erase it. The credential vault is DPAPI-bound to the Windows account,
so a leftover file is not readable by another user, but it must not be left as an unmanaged artefact.

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

- Produced and measured here: `MediaHub-portable-win-x64.zip` **139,338,968 bytes**,
  SHA-256 `002daf3559f5bdd37d172fe725ce38b6be203a56192a6fcbdf3ff7e75f92618e`
  (`Compress-Archive -CompressionLevel Optimal`). This hash is a property of *this* zip operation —
  zip bytes depend on entry order, timestamps and the compressor, so it identifies a file, not a
  build.
- The build-**identity** hash that matters is the engine's:
  `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9`, verified by
  `fetch-libmpv.ps1` and re-verified this session.
- **Gap:** CI uploads the artifact but publishes **no checksums file and no build provenance**
  (`windows-ci.yml` lines 131–140). A release process needs `SHA256SUMS` next to the artifact (and,
  ideally, a build-provenance attestation).

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

Additional consequence: signing `libmpv-2.dll` (a third-party binary) with our certificate would make
us the distributor of a signed GPL binary — a licence/trust decision that belongs with the licence
decision, not with packaging. Today it is simply left unsigned.

## 10. Gaps, blockers, and the order to fix them

| # | Gap | Severity | Owner |
| --- | --- | --- | --- |
| 1 | **GPL engine** — blocks release in every packaging form | **BLOCKER** | licence decision ([LICENCE-DECISION.md](LICENCE-DECISION.md)) → then the build |
| 2 | No licence/notice files in the package — **addressed (W40)**: `licences/` now carries six licence/notice/inventory documents plus a digest manifest, and a gate re-runs the staging script | resolved (W40); verbatim LGPL/GPL texts remain a licence decision | packaging + licence |
| 3 | No code-signing certificate — `SIGNING_BLOCKED` | **BLOCKER** for installer/MSIX; high for portable | product owner (procurement), then CI |
| 4 | No file associations | feature | app (W3) or a sparse/MSIX package |
| 5 | No uninstall/data story | high (user trust) | packaging + app |
| 6 | No version single-source-of-truth | high (MSIX identity, support) | `windows/Directory.Build.props` owner |
| 7 | No update mechanism | medium | packaging |
| 8 | No `SHA256SUMS`/provenance in CI | medium | CI owner |
| 9 | ~40 MB unused Windows ML runtime shipped | low (size) | `windows/src` owner |
| 10 | 6 `.pdb` files shipped; 83 locale folders shipped under `InvariantGlobalization=true` | low | packaging |

Recommended order: **1 → 2 → 3 → 6 → 5 → 8 → 4 → 7 → 9/10.** Items 4 and 7 should not start before
the packaging form is decided, because MSIX makes association registration free and updatable while
the portable form requires both to be written as application code.

## 11. UNKNOWN

| # | Unknown | Resolution needed |
| --- | --- | --- |
| U1 | Whether a clean machine (no .NET, no Windows App Runtime) runs the current package | the clean-machine test promised in ADR-W004 is still **NOT_RUN**; needs a VM/snapshot |
| U2 | Real-world size/time of an MSIX build for this app | not attempted; needs a `Package.appxmanifest` |
| U3 | Whether `EnableMsixTooling=true` with `WindowsPackageType=None` has any effect today | it is set in the csproj but no manifest exists; resolve when MSIX work starts |
| U4 | Certificate acquisition path (OV vs EV vs Trusted Signing) and cost | commercial decision |
| U5 | Whether Zip payload can be trimmed below ~330 MB without the ML runtime and without breaking WinUI | publish + launch test in the owner's scope |
| U6 | The exact behaviour of MSIX data virtualisation for this app's `%LOCALAPPDATA%\MediaHub` paths | requires an actual MSIX build and an uninstall test |
