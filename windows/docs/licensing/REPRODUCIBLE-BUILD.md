# REPRODUCIBLE-BUILD — how to reproduce the shipped Windows artifact

- Date: 2026-10-14 (W3-H). Author: license-dev.
- Related: [SBOM.md](SBOM.md), [LICENCE-DECISION.md](LICENCE-DECISION.md), `windows/tools/fetch-libmpv.ps1`.

**Headline:** the **.NET half is reproducible in practice** (measured below, with caveats); the
**libmpv half is not reproducible at all** — it is a downloaded third-party binary that we can only
*verify by digest*, not rebuild. Those are two different guarantees and the record keeps them apart.

| Half | Status | Basis |
| --- | --- | --- |
| MediaHub assemblies, runtime, package layout | **REPRODUCIBLE** from a clean checkout with SDK 10.0.113 — byte-identical across two consecutive clean publishes | §3.2 |
| Engine `libmpv-2.dll` | **NOT REPRODUCIBLE here.** Binary identity is pinned and verified; provenance beyond the asset is partial | §4 |

---

## 1. Prerequisites (measured on this machine unless noted)

| Requirement | Value / how to check |
| --- | --- |
| OS / arch | Windows x64 (`win-x64` runtime identifier) |
| .NET SDK | **10.0.113** — `dotnet --version`. Also installed here: 9.0.313. CI pins `10.0.x` (`windows-ci.yml` line 34) |
| .NET runtime present in the package | `Microsoft.NETCore.App` **10.0.13** (self-contained; `MediaHub.runtimeconfig.json`) |
| PowerShell | 7.x (`pwsh`) — the fetch script and the CI steps use it |
| 7-Zip standalone extractor | `7zr.exe`, downloaded by the fetch script to `%TEMP%` if absent |
| Network | (a) NuGet restore; (b) the libmpv release asset |
| Disk | ~1 GB for a publish output (331.9 MB measured) plus caches |
| Native toolchain (mpv/FFmpeg) | **not required** and **not present** — see §4 |

## 2. Exact steps to reproduce the shipped package

```powershell
# 0. Source revision this record describes
git -C . rev-parse HEAD          # b07b92146a6c2f7d967e1d10901e9821d2b49ee0 on feature/windows-client

# 1. Engine: fetch + verify (no build). Both digests are checked BEFORE install.
pwsh -File windows/tools/fetch-libmpv.ps1
# measured output:
#   libmpv already present and verified: ...\windows\native\libmpv\libmpv-2.dll (120989696 bytes)

# 2. Restore / build / test -- exactly what CI does (windows-ci.yml lines 55-83)
cd windows
dotnet restore MediaHub.Windows.sln
dotnet build  MediaHub.Windows.sln -c Debug -p:Platform=x64 --no-restore
dotnet test   tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug -p:Platform=x64 --no-build

# 3. Publish the portable package (windows-ci.yml lines 114-119)
dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -o ./artifacts/portable

# 4. Smoke check (windows-ci.yml lines 121-129)
Test-Path ./artifacts/portable/MediaHub.exe, ./artifacts/portable/libmpv-2.dll, ./artifacts/portable/MediaHub.dll
```

Expected result (measured this session): **522 files, 347,972,263 bytes (331.9 MB)**;
`libmpv-2.dll` = 120,989,696 bytes.

### 2.1 Pinned engine identity

| Object | Pin | Verified here? |
| --- | --- | --- |
| Archive `mpv-dev-x86_64-20261014-git-ec38361c56.7z` | SHA-256 `325555b4e0b1d87fb7c92c96010776581599c64e4988052cb9d05e130b52f939` (31,562,418 bytes; 6 files, 2 folders — listed with `7zr l`) | source of the DLL; archive itself not re-hashed this session |
| Extracted `libmpv-2.dll` | SHA-256 `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9` | **yes** — `Get-FileHash` equals the pin, and `fetch-libmpv.ps1` re-verifies it and exits 0 |
| Download URL | `https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261014/mpv-dev-x86_64-20261014-git-ec38361c56.7z` | from `fetch-libmpv.ps1` lines 33–37 |

Note: `windows/docs/adr/ADR-W004` lines 54–61 still describe these digests as `PENDING`. That is
stale — the script pins and enforces both (lines 35–36, 69–71, 88–90). Reported to the Lead; not
edited here (outside this task's write scope).

## 3. Reproducibility measurements (this session, SDK 10.0.113, same commit)

### 3.1 In-repo publishes are *not* stable across runs

Three consecutive `dotnet publish` runs into different output directories, from the repository
working tree (which contained pre-existing `bin/`/`obj/` from earlier work):

| Assembly | publish #1 | publish #2 | publish #3 |
| --- | --- | --- | --- |
| `MediaHub.exe` | `3149af9f…bc05` | `3149af9f…bc05` | `3149af9f…bc05` |
| `MediaHub.dll` | `38a49c58…4d40` | `6aad1132…b7b4` | `6aad1132…b7b4` |
| `MediaHub.Domain.dll` | `eb0ae47f…fad2` | same | same |
| `MediaHub.Net.dll` | `b6b1c8fe…e1aa` | `7eddd792…9b79` | `7eddd792…9b79` |
| `MediaHub.Player.dll` | `90a47ba7…5f39` | same | same |
| `MediaHub.Player.Mpv.dll` | `cd01b876…ba8f` | same | same |
| `MediaHub.Providers.dll` | `63b0d93d…e1bb` | `087be1c2…a388` | `087be1c2…a388` |
| whole-tree manifest SHA-256 | `52621eb6…7a16` | `0cd00f87…cdd8` | — |

Byte-level diff of `MediaHub.dll` between publish #1 and #2: **72 differing bytes, first at offset
136**, which is the PE COFF `TimeDateStamp` (`FB9613C7` vs `7AF0B4CB`), plus the debug-directory
entries that embed build-path/PDB information. Runs #2 and #3 then agreed exactly.

**Cause: UNKNOWN.** It is consistent with incremental state in `obj/` inherited from builds made
before this session, but that was not proven, and it was not investigated further because clearing
`obj/` would disturb other work happening in the same working tree. **What would resolve it:**
compare against a CI run on a fresh checkout (which is what §3.2 did in a copy).

### 3.2 A clean checkout *is* reproducible

A pristine copy of `windows/` (123 files, excluding `bin/`, `obj/`, `artifacts/`, and with no
`.git`) was published twice:

| Assembly | clean run A | clean run B | A == B? |
| --- | --- | --- | --- |
| `MediaHub.exe` | `6b8dc5d5…be1b` | same | **yes** |
| `MediaHub.dll` | `051ef157…69d9` | same | **yes** |
| `MediaHub.Domain.dll` | `43f18766…92c5` | same | **yes** |
| `MediaHub.Net.dll` | `c713f284…188a` | same | **yes** |
| `MediaHub.Player.dll` | `a1795be5…51e9` | same | **yes** |
| `MediaHub.Player.Mpv.dll` | `bfc0481b…2b41` | same | **yes** |
| `MediaHub.Providers.dll` | `aa0e170c…c6ad` | same | **yes** |

So: **same tree, same SDK, same command ⇒ byte-identical output.**

### 3.3 ...but the clean-copy hashes differ from the in-repo hashes, and the reason is knowable

`(Get-Item MediaHub.dll).VersionInfo.ProductVersion`:

- in-repo publish: `1.0.0+b07b92146a6c2f7d967e1d10901e9821d2b49ee0`
- clean-copy publish: `1.0.0`

The SDK stamps the git revision into `InformationalVersion`; the clean copy had no `.git`. That
difference alone changes the assembly bytes. **Consequence for release engineering: a release must be
built from a git checkout (or with the revision supplied explicitly); a source tarball without
`.git` produces a different, revision-less binary.**

### 3.4 Statement we are entitled to make

- "The .NET part is reproducible": **yes, for identical inputs** — measured, two clean runs identical.
- "The released package is bit-identical to a re-run": **not yet claimable** — the in-repo runs were
  not stable (§3.1), and the release path (CI, fresh checkout) was not itself re-run twice here.
- If bit-identical artefacts across machines/time are ever required, the controls to add are
  `-p:ContinuousIntegrationBuild=true` (path mapping) and publishing from a fresh checkout only.
  **These were not tested here** — do not claim them.

## 4. The libmpv half: what we cannot reproduce, and what we can still do

### 4.1 Cannot reproduce the binary

Rebuilding `libmpv-2.dll` requires the whole mingw-w64 cross-build the asset's producer runs:

- `shinchiro/mpv-winbuild-cmake` at the **exact commit** used for release `20261014` — **UNKNOWN**
  (the binary proves the release tag and the GitHub Actions path `/__w/mpv-winbuild-cmake/`, not the
  commit);
- MSYS2 / `x86_64-w64-mingw32` toolchain, CMake, ninja, meson, and **~40 third-party packages**
  (see `packages/*.cmake` in that repository);
- the mpv revision `ec38361c5` and the FFmpeg revision `g2ec8998c3` (both read out of our DLL);
- hours of build time.

This environment cannot do it: `meson` and `ninja` are **not on `PATH`**; `cmake` exists only inside
Visual Studio 2022 Community; MSYS2 provides `gcc`/`pkg-config` but there is no mingw-w64 cross
toolchain; and installing one is explicitly out of scope for this task.

### 4.2 What we *can* do (and already do)

1. **Binary identity**: two SHA-256 digests, verified before install, refusing to proceed on mismatch
   (`fetch-libmpv.ps1`; §2.1). A swapped or re-uploaded asset is detected.
2. **Version forensics**: the DLL self-reports mpv/FFmpeg revisions and its complete build command
   (§1.3 of LICENCE-DECISION) — enough to identify exactly what it is.
3. **Licence truth**: the binary's own feature list and codec descriptors prove it is a GPL build
   (LICENCE-DECISION §1).
4. **Source obtainability** (needed only if OPTION 2 in LICENCE-DECISION is ever chosen): mpv and
   FFmpeg sources at the two revisions above are obtainable; the **builder commit (U1) must be
   resolved** before any "Corresponding Source" claim is made, because the GPL requires the source
   actually used, including the scripts that generate the binary.

### 4.3 One thing the current artifact does *not* prove

The FFmpeg `configure` line is **not embedded** in the DLL (raw scan for `--enable-gpl`,
`--enable-libx264`, `--enable-libx265`, `--target-os=mingw32`: not found). The FFmpeg build flags are
therefore evidenced by the builder's recipe, not by the shipped binary. A reproducible-build
requirement for the engine would include archiving that configure line next to the digests.

## 5. Post-build verification (the checks a release must pass)

```powershell
$out = './windows/artifacts/portable'
(Get-ChildItem -Recurse -File $out).Count                                   # expect 522
((Get-ChildItem -Recurse -File $out | Measure-Object Length -Sum).Sum)      # expect 347972263
(Get-FileHash -Algorithm SHA256 "$out/libmpv-2.dll").Hash.ToLowerInvariant()
#   expect 6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9
(Get-Item "$out/MediaHub.dll").VersionInfo.ProductVersion                   # expect 1.0.0+<40-hex revision>
Get-AuthenticodeSignature "$out/MediaHub.exe"                               # currently: NotSigned
Get-ChildItem -Recurse -File $out | Where-Object { $_.Name -match 'LICEN|NOTICE|COPYING' }  # currently: nothing
```

The last two lines are the release gaps tracked in [SBOM.md](SBOM.md) §4.1 and
PACKAGING.md (SIGNING_BLOCKED).

## 6. UNKNOWN

| # | Unknown | Resolution |
| --- | --- | --- |
| U1 | `shinchiro/mpv-winbuild-cmake` commit for release `20261014` | release page / CI build log for that tag |
| U2 | Whether the asset publisher's build is itself reproducible | their CI logs; not published in the asset |
| U3 | Cause of the publish #1 vs #2 divergence (§3.1) | clean-`obj` experiment in a throwaway tree, or a CI run comparison |
| U4 | Whether input pinning is complete: `dotnet restore` uses floating versions? | `windows/Directory.Packages.props` pins exact versions; `CentralPackageTransitivePinningEnabled=false` means transitive versions come from each package's own nuspec and are **not** pinned by this file — a `packages.lock.json` would close that. Not produced here. |
| U5 | Archive bytes re-hash (§2.1) | re-download the archive and compare — the DLL digest match already implies the reviewed content |

## 7. Reproducibility vs the licence blocker

Do not confuse the two: **being able to rebuild the .NET half does not help the licence problem, and
pinning the engine's digest does not make the engine LGPL.** The digests prove *which* GPL binary we
ship; only a different build (LICENCE-DECISION §2) changes *what* we ship.
