# Is Vulkan required? (W37, 2026-10-31)

verdict: not-required-by-the-product-path
capability-delta: lgpl-candidate-lacks-vulkan

Two questions hide behind "the LGPL engine has no Vulkan":

1. **Does MediaHub need Vulkan?** It pins `gpu-api=d3d11` and ships `gpu-context=d3d11` / `vo=gpu-next`, so the answer is decided by the player code, not by the engine build.
2. **Does the candidate lose a capability the GPL baseline had?** Yes - and that stays a maintainer decision, not something this assessment can wave away.

The first question is answered below with source review plus a headless probe of the committed binary.
The second is restated as an explicit delta. The licence guard pins both verdict lines so neither can drift
silently (`check-licence-surface.ps1`, check `vulkan-claims`).

## 1. What the product asks the engine for (SOURCE_REVIEWED)

| Where | Value | Notes |
| --- | --- | --- |
| `windows/src/MediaHub.Player.Mpv/MpvPlayer.cs:190` | `gpu-api=d3d11` | hard-wired; not a user option |
| `windows/src/MediaHub.Player.Mpv/MpvPlayerOptions.cs:16` | `GpuContext = "d3d11"` | default when no option is supplied |
| `windows/src/MediaHub.App/Services/SettingsStore.cs:159-164` | `vo=gpu-next`, `gpu-context=d3d11`, `hwdec=auto-safe` | defaults written on first run |
| `windows/src/MediaHub.App/Views/SettingsPage.xaml.cs:194` | only `hwdec` is editable in the UI | `gpu-context` appears only in the summary line, so there is no shipped path that selects a Vulkan context |
| `windows/src/MediaHub.Player.Mpv/MpvPlayer.cs:480-498` | reads back `hwdec-current` and `gpu-context` | the player surfaces what the engine actually negotiated |

No file under `windows/src` mentions Vulkan at all (the guard checks this): the product never asks for a
Vulkan API, a Vulkan context, or a Vulkan-specific filter. The renderer chain in use is D3D11 + libplacebo,
which the candidate provides.

## 2. What the committed candidate advertises (EXECUTED)

Probe (headless - mpv answers and exits while parsing the option, before any window or audio device exists):

`BT`text
pwsh -NoProfile -File windows/tools/probe-engine-capabilities.ps1
`BT`

Observed 2026-10-31 on the committed candidate (`windows/engine/lgpl/dist/mpv.exe`, sha256
`524f017fd49d99114456c7c51218d00a1e464fba1a33d5d8ca5562d014c8e8e6`):

`BT`text
engine version  : mpv v0.41.0-UNKNOWN (built Oct 14 2026, libplacebo v7.360.1, FFmpeg 8.1.1)
gpu-api         : auto, d3d11, opengl
gpu-context     : auto, d3d11, win, dxinterop
vo              : gpu-next, gpu, libmpv, null, image, tct, kitty
hwdec           : d3d11va, d3d11va-copy, d3d12va, d3d12va-copy, auto, no, auto-safe, auto-copy (+3 more)
features        : d3d11=True d3d-hwaccel=True libplacebo=True vulkan=False   (28 tokens)
required        : PASS  gpu-api=d3d11 | gpu-context=d3d11 | vo=gpu-next | hwdec=d3d11va | features d3d11, d3d-hwaccel, libplacebo
vulkan          : absent from this build (gpu-api, gpu-context and features)
RESULT: PASS (every product-required capability is advertised)
`BT`

So on the path MediaHub ships, the candidate is not degraded: every option the player sets has the value
it needs. Vulkan is absent, and would be reported as absent rather than silently substituted.

## 3. What the GPL baseline had (recorded, not re-measured)

The GPL baseline binary is not in the repository, so its capability set cannot be probed here. What is on
record:

- `windows/docs/licensing/LICENCE-DECISION.md:86` - the baseline build command passes `-Dvulkan=enabled`.
- `windows/docs/licensing/W5-LGPL-BUILD.md:181` - "Vulkan context | yes | no | not a licence issue".
- `windows/docs/W13-LGPL-ENGINE-PROBE.md:65-73` - the GPL baseline lists 60 features including `vulkan`; the candidate lists 28 and does not.

The candidate build **requested** Vulkan (`windows/engine/lgpl/build-mpv-lgpl.sh:39` passes `-Dvulkan=enabled`, and the
recorded meson configuration repeats it) but mpv compiled the feature out because the meson header probe
`symbol VK_VERSION_1_3 in vulkan/vulkan_core.h` answered NO (`windows/engine/lgpl/mpv-meson-setup.log:153-155`). That is the
whole cause: a build-environment probe, not a licence term.

## 4. Verdict

- **Product necessity: no.** The shipped player fixes `gpu-api=d3d11`, defaults `gpu-context=d3d11`, uses `gpu-next`
  (libplacebo) and `hwdec` values on the D3D11 family. Vulkan is not on any shipped path, and no source file
  under `windows/src` names it.
- **Capability delta: real but latent.** A user who edits the settings JSON by hand (`gpu-context=winvk`) or a future
  feature that wants a Vulkan render path would find the option refused; the refusal is visible, because
  `MpvPlayer.ApplyOptions` records every rejected option in `OptionErrors` instead of ignoring it.
- **Consequently the W5 note stays open:** "vulkan is a capability the GPL build had and the LGPL build does not,
  and it should be closed before this engine replaces the baseline". This assessment does not close it; it
  bounds it. Whether to close it is decision B below.

## 5. Decision list for the maintainer

| Option | Meaning | Cost |
| --- | --- | --- |
| A - accept and document | Ship the candidate as the non-default flavour; Vulkan absence is documented (this file + W5 §3) and surfaced by the probe | none now; the delta stays |
| B - fix the probe and rebuild | Investigate the `VK_VERSION_1_3` header probe (vulkan-headers / `-Dvk-proc-addr`), rebuild the candidate with Vulkan, re-run W13 + this probe | a build round in the MSYS2 environment; new hashes for every claim site |
| C - keep the GPL baseline default | Do nothing; the LGPL candidate stays a side flavour | the LGPL path stays unshipped, which is where the project already is |

Nothing in this file decides A, B or C. Per the task boundaries, switching the shipped engine flavour is a
maintainer decision, and the LGPL candidate stays non-default.

## 6. Reproduce

`BT`text
pwsh -NoProfile -File windows/tools/probe-engine-capabilities.ps1            # probe the committed candidate
pwsh -NoProfile -File windows/tools/probe-engine-capabilities.ps1 -Json      # same observation as JSON
pwsh -NoProfile -File windows/tools/probe-engine-capabilities.ps1 -EnginePath <any mpv.exe>
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath <report>   # includes vulkan-claims
`BT`

`probe-engine-capabilities.ps1` exits 0 when every product-required capability is advertised, 1 when one is
missing, 2 on a usage error and 3 when the engine binary is absent.

## 7. Not done here

- The GPL baseline binary was not probed (not tracked in the repository); its capability set is quoted from
  the recorded W13 probe and the build command.
- No Vulkan render path was exercised, because the product has none. HDR through `gpu-next` on D3D11 was
  measured elsewhere (W13); a Vulkan HDR path is hypothetical and would need option B first.
- The candidate was not rebuilt; fixing the meson probe is option B, not part of this assessment.
