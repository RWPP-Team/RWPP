# RWPP OpenGL / hybrid GPU configuration

Validated on 2026-10-25 with an RTX 4050 Laptop GPU, driver 592.00, and the real
Steam installation at `D:\steam\steamapps\common\Rusted Warfare`.

The installed controller is UniversalDLSS5 v0.5.1, upstream commit
`369872ffb80a2bd8a4f8e3469f5b40e515fcc0f6`:
https://github.com/MotionflowOffical/UniversalDLSS5/tree/v0.5.1

`rwpp-opengl-hybrid-gpu.patch` fixes two upstream integration issues for this game:

- LWJGL's OpenGL game must own presentation. Internal NVIDIA/AWT DXGI presents
  must not claim native D3D11 ownership and suppress OpenGL processing.
- On NVIDIA OpenGL, select the NVIDIA DXGI adapter for interoperability. The
  laptop display is attached to Intel, so choosing by monitor alone initializes
  NGX on the wrong GPU and returns `0xBAD00001`.

The rebuilt bridge retains upstream ABI 21, settings version 10 and its filename.
It is installed only in the game's `tools/UniversalDLSS5` copy. The original is
preserved next to it as `UniversalDLSS5.Bridge.original-v0.5.1.dll`.
Installed bridge SHA256:
`dff7f0a2a99492daf43b576b11473e7f4752073ef28a26ede5e80b60269f24a2`.

Build with Visual Studio 2022 x64, CMake, the upstream v0.5.1 project, MinHook
v1.3.4 and NVIDIA NGX headers/import library. Apply the patch, set
`UDLSS_NGX_ROOT` to that SDK, disable `UDLSS_BUILD_TESTS` for a bridge-only build,
then build target `UniversalDLSS5.Bridge` in Release. The reproduction checkout,
inputs and compiler logs are in `build/network-room-dlss-complete`.

Runtime: the community RTX 40 patch from
https://github.com/RankFTW/rhi-repo/releases/tag/dlssnr-310.8.0-RTX40
is installed in `tools/UniversalDLSS5/runtime/nvngx_dlssnr.dll`.
Downloaded ZIP SHA256, verified against the release asset digest:
`46124cfaef532ad5f6da07494772ea8c1b3e719f934e254385697f38d1289e3f`.
This is a community-modified runtime; its NVIDIA Authenticode hash does not
validate after patching. Neither this DLL nor NVIDIA binaries are stored here.
An official `nvngx_dlss.dll` from Streamline 2.14.16 is also present in runtime.

Windows per-application GPU preferences are set to high performance for the game
executables, bundled JVM entry points and the JBR 25 used by validation. Prior
values are recorded in `build/network-room-dlss-complete/gpu-preferences-backup.json`.
No driver replacement, security-setting change, Compose instrumentation or
Computer Use was performed.

The client now uses the controller's shared-memory telemetry for the current
game PID, displays actual neural frame counts and GPU time, and exposes an
explicit connect/enable action. A running controller alone is never presented
as active neural rendering. Normal controller shutdown also supports a hidden
controller window, with executable and process-start identity checks.

Actual client API validation on PID 43944 observed 5738 -> 7136 neural frames,
failure stage 0 and approximately 3.09 ms GPU time, then normal controller exit
while the game remained alive. This is execution evidence, not a visual-quality
assessment or a frame-rate improvement claim. See
`build/network-room-dlss-complete/dlss-live4.log`.

After fully restarting the game, use the DLSS settings action to connect the
current game and enable processing. This also starts the controller when absent.
