# AI_MEMORY.md: Wrecked Metal long-term memory

Shared memory for any AI agent (or human) picking up this project. It holds what the
repository docs do not: decisions and their reasons, gotchas, history, and open work. The
format is loosely based on Akita's [ai-memory](https://akitaonrails.com/2026/05/23/criei-sistema-memoria-agentes-codigo-ai-memory/)
idea (concepts / decisions / gotchas / sessions), kept as one markdown file. See
[AGENTS.md](AGENTS.md) for the rules, and update this file when you learn something durable.

Last updated: 2026-11-21 (v0.1.2-beta).

---

## 1. Concepts (durable knowledge)

### What the project is
- **Wrecked Metal** by **Costelinha Games**. It is a native Android port of Destruction Derby 64,
  built from the community decompilation plus a native PC port. It is **not** an emulator, and **not**
  N64Recomp or RT64.
- Stack: game C code from the decomp, SDL3 (3.4.x), the Fast3D renderer on **SDL_gpu → Vulkan**,
  and a Java launcher.
- Repos:
  - App: `github.com/dedecidao/Wrecked-Metal`, branch `main`. Earlier names were
    Destruction-Decomp-64-Android and Wrecked-Metal-64-DX; the old URLs redirect.
  - Engine fork: `github.com/dedecidao/Destruction-Decomp-64`, branch `port-pc`. It is a submodule
    at `external/dd64`. The name stays as is, to credit the decomp.
- App ID: `com.costelinhagames.wreckedmetal`. The debug build adds `.debug` and is labelled
  "Wrecked Metal Dev". The Java package and Gradle `namespace` stay `io.github.dedecidao.dd64`,
  because the JNI symbol names depend on them.
- BYOR: the user picks their ROM, which is checked by SHA1
  (`a2c0799e13566d1b723299592cfcac9387f25fa7` for .z64), converted if needed, and kept in
  private storage.

### How the 32-bit build runs (armeabi-v7a, the only playable ABI today)
- The game expects its RAM image at **0x80000000**, with fixed addresses and 4-byte pointers
  in structs and ROM data.
- In a 32-bit Android process, ART puts its JIT cache and linear-alloc shadow at
  0x7DA00000–0x83A00000 before our code runs. The fix is `wrap.sh`, which `LD_PRELOAD`s
  `libdd64reserve.so` to reserve that range first.
- **Android only runs `wrap.sh` for debuggable apps**, so the *release* APK is debuggable too.
  That is fine for GitHub APKs. The Play Store needs arm64.
- N64 function pointers become ARM jump stubs (`ldr pc, [pc, #-4]`). Symbols come from
  `symbols/dd64.syms`, exported from a PC build and holding no ROM data.
- minSdk 29 (Android 10): from API 29 down there is native ELF TLS. Below it, emulated TLS made
  track loading take *minutes* (`port/src/shadow.c` uses TLS on every memory access).

### Device compatibility (as of v0.1.1)
- Requirements: Android 10+, Vulkan 1.0.3+ with the SDL_gpu feature set (Adreno 6xx and newer
  are fine; Adreno 5xx is unverified), and **32-bit app support**.
- Works: Snapdragon 865/870/888 and 8 Gen 1/2 (Galaxy S20 FE 5G, S21–S23, Tab S7–S9).
- Doesn't install: 64-bit-only SoCs (Snapdragon 8 Gen 3, 8 Elite, 7+ Gen 3; Pixel 7 and newer).
  These need the arm64 port.
- Mali/Exynos/MediaTek are out of focus for now.
- If Vulkan fails the game falls back to OpenGL ES, but runs at about 3 fps. Not playable.

### Two modes (launcher)
- **PLAY WRECKED METAL**: the enhanced game, using `dd64.ini`.
- **Play Fidelity**: N64-faithful, with original HUD boxes, filtering, 22 kHz audio and the
  N64 touch layout (stick + 4 C buttons). It uses its own `dd64-fidelity.ini`.
- The save (`pak1.mpk`) is shared between the two.

### Config
- `[android] config_version` (currently 3): it applies the Android defaults once and then keeps
  the user's personal settings.
- Approved defaults (2026-11-20): output 720p, 3D 720p, VSync on, MSAA 2x, draw distance 8x,
  anisotropy 4x, bilinear filtering. The previous x16 / 16x / 3-point settings dropped the S23
  to 55 fps.

### The game's own cheats (hold R + letters on the Game Mode screen)
- `DUD`: all cars. `BRAD`: all tracks and difficulties. `BAR`: indestructible.
  `DAD`: no time limit. `LRLR`: standard damage. `BALD`: heavy car.
- Flags live at 0x801253F8, 0x801253F9 and 0x8012540C. Save unlocks live at 0x80097A0C and
  0x80097A14.
- The game's code for this: Game Mode screen = mode 0x13; `MainMenu_CheckOptions`
  (`src/auto/40244.c`); `Cheat_UpdateInput` (`src/auto/2E00C.c`).

---

## 2. Decisions (with reasons)

| Date | Decision | Why |
| --- | --- | --- |
| 11-18 | Long-term target is arm64-v8a; ship 32-bit first | 64-bit is the future and required by the store. 32-bit was faster to get running |
| 11-18 | arm64 plan = LP64 source conversion + load below 4 GB via `android_dlopen_ext` | ILP32 and `__ptr32` are not viable on aarch64 clang (docs/ARM64.md) |
| 11-18 | Always Vulkan; MSAA capped at 2x on Android | 4x MSAA gives black frames on Adreno 740 with SDL_gpu; OpenGL ES runs at a few fps |
| 11-19 | Android menus: interpolation OFF, edge fill ON, seamless sky ON; CPU unlockables in Advanced (off); drones removed; HD fonts and raphnet hidden | User testing (drones=2 caused "flying cars") |
| 11-19 | Touch: only C-Up among the C buttons; analog stick ↔ steering arrows option (launcher + menu R > controls) | User preference |
| 11-19 | Audio synth at 44.1 kHz; music replaceable via `Data/Music/song_XXXXXXXX.ogg/.mp3` | Quality; stb_vorbis + dr_mp3 |
| 11-20 | Rename to **Wrecked Metal** (final), studio Costelinha Games | Branding |
| 11-20 | Ko-fi donations only (`ko-fi.com/costelinha_games`); no paywall or early access | Legal safety (fan project, BYOR) |
| 11-20 | App ID switched to `com.costelinhagames.wreckedmetal` *before* the first public release; never change it again | Changing it later breaks updates |
| 11-20 | Release build: debuggable + `CMAKE_BUILD_TYPE=Debug` for native (the game's own files are still -O2) | `wrap.sh` needs debuggable; RelWithDebInfo doubled ~1.5% of frames vs 0.1% |
| 11-20 | Releases come from tags; the maintainer pre-approved: CI green + device test passing → merge and release | Testers always get the latest build |
| 11-21 | Customizations go solely into **Wrecked Metal** (`!gPortConfig.fidelity`) via `wm_mod.c` + `Data/wm_tuning.ini`, without menu toggles; PRs await manual approval after Android device test | Keeps Play Fidelity pure, avoids UI bloat and ensures review before landing on `port-pc`/`main` |

---

## 3. Gotchas (things that already bit us)

- **A 32-bit PIC `.so` is not the PC's fixed-address exe.** Tables accessed PC-relative broke
  the Z+Down crash fix. The fix was `-fvisibility=default` for the game objects.
- **clang -O2 on ARM loops forever in `inflate_codes`** (`src/auto/51980.c`), so that file
  builds at -O0.
- `-fsigned-char` is required; ARM's `char` is unsigned by default.
- clang emits no DWARF for externs, so `symbols/dd64_widths.c` comes from the PC build.
- **Mirrored track classes gave a black screen** (Professional/Legend): a viewport sign bug,
  fixed in `fast3d/gfx_pc.cpp`.
- **Time Trial record crash** after entering a name: `Time_Format` (`src/auto/EC94.c`)
  overflowed. It now formats into a large buffer and copies 8 bytes.
- **60→40 fps stutter**: the audio watchdog's u64 wrap produced false stalls. The watchdog now
  acts only when the window is focused, opens one stream at a time, and reopens AAudio after
  interruptions such as calls.
- **Audio stream opens are serialized** (`sOpenLock`): without it, at startup the watchdog
  opened a second AAudio stream and produced ~110 underruns/s.
- **Never create `files/Data` via adb before the app does**: the folder ends up owned by
  `shell` and the game can't write its log or save. Let the app create it first.
- **WSL on the maintainer's PC**: writing big files through `\\wsl.localhost` once truncated
  `android_main.c`, and passing commands from PowerShell to bash breaks on quotes and `$`.
  Use a `.sh` script instead.
- **The idle menu loses about 1 frame every 4 s.** This is still open; an ADPF performance hint
  is the candidate fix.
- **Cheats left on cause hangs that look like bugs.** Always disable them before a device test.
- **The USB cable/port on the maintainer's PC is flaky** (adb "offline", "write failed"). The rear
  motherboard port is stable.
- **Game libc reading an f64 through `(u32 *)&d`** gets the low word on ARM/x86. `frexp`
  (`src/libc/7B8B0.c`) did, so `log()`/`pow()` were wrong for every argument but 1.0. Symptom:
  the Baja Buggy (car type 12) and type 21, the only cars with offroad 3.0, spun off at 200+ m/s
  on contact (rear-tyre grip factor `x1B8` about -152). Fixed in engine PR #6.
- **CI debug APKs are signed with the release key** (when the secrets exist), so they install
  over the debug app on the S23 without an uninstall and keep its imported ROM.
- **Physics trace**: engine branch `debug/car-trace`, enabled by `DD64_CARTRACE=1` in the debug
  app's `Data/env.txt` (with `DD64_BOOT=track=4,type=1,car=N`, `DD64_FAKEGAS=200:1.0`). It logs
  per-stage velocity jumps, tyre forces past the grip cap and big suspension forces.
- Cloud agent environment (Claude Code on the web): the proxy blocks repo settings, rulesets,
  tag pushes and branch deletes. Those go through the maintainer's PC session or the GitHub UI.

---

## 4. Workflow facts

- The maintainer is André (GitHub `dedecidao`). He writes in Portuguese. Code comments and
  commits are in English.
- The test device is a Galaxy S23 Ultra (Snapdragon 8 Gen 2 / Adreno 740, Android 17), driven
  over adb from his Windows PC. The versioned deploy/debug script lives at `tools/test-run.ps1`
  (auto-detects `adb.exe` on `PATH` or `%USERPROFILE%\Android\platform-tools\adb.exe`, handles
  `[cheats]` backup/disable/restore, `Data/env.txt`, `dd64.log` + `logcat` + `screencap`, and
  plays a Windows alarm sound when done). See `DOCS_DEV_SETUP.md` for the full setup guide.
- He prefers minimal tethering: do everything possible without the device, then run one short
  adb test.
- Debug test hooks in the debug build (`touch.c`): `DD64_POKE`, `DD64_GOTO`, `DD64_SHOT=n`,
  `DD64_EXIT_AFTER=n`, `DD64_GFXLOG`, `DD64_MUSICDUMP=1`.
- CI (`.github/workflows/android.yml`):
  - `apk`: armeabi-v7a debug, plus a signed release when the secrets exist.
  - `arm64-check`: probe APK; the conversion build is informational.
  - `nightly`: rolling pre-release from `main`.
  - `release`: runs on a `v*` tag.
- Marketing, README and release copy are written by a separate "management/marketing" effort.
  Release notes end up in `.github/release-notes/`.

---

## 5. Session log (what has been done)

- **2026-11-18**: Android repo created; engine and SDL added as submodules; CI builds
  armeabi-v7a. Found that ART occupies 0x80000000, solved with `wrap.sh` +
  `libdd64reserve.so` (PR #1). First boot on the S23: menus and a race at about 60 fps.
- **2026-11-19**: Mobile mission in 5 phases (PR #2):
  - fixed the Z+Down crash on the Game Mode screen;
  - added `DOCS_INTERNALS_AND_OPTIONS.md` and `DOCS_ANDROID_PORT.md`;
  - Android menus with Advanced and Restore Defaults pages;
  - adaptive haptics (subtle for scraping, strong for impacts);
  - a Cheats page;
  - 44.1 kHz audio and music replacement.
- **2026-11-20 (morning)**:
  - Fixed the mirrored-class black screen and the Time Trial crash.
  - Added the audio watchdog (audio resumes after interruptions) and fixed the 60→40 fps stutter.
  - New lighter defaults, config_version 3.
  - Touch steering arrows.
  - App PRs #3 and #4; engine fork PRs #2 and #3.
- **2026-11-20 (midday)**:
  - Rebrand to Wrecked Metal, with the launcher's two modes (Play / Fidelity).
  - Fidelity profile (engine #4, app #5).
  - English README, Ko-fi and FUNDING.yml (PR #6); README in PT and ZH.
- **2026-11-20 (afternoon)**:
  - Release key created (RSA 4096, alias `wreckedmetal`, valid until 2054) and stored as CI secrets.
  - Signed release builds (PR #8), app ID changed.
  - Native build fix + "Wrecked Metal Dev" label + tag release job (PR #9).
  - **v0.1.0-beta published.**
- **2026-11-20 (evening)**: Tester tools (engine #5, app #11):
  - device sheet at the top of `dd64.log`: model, SoC, Android, GPU, Vulkan driver, OpenGL
    fallback, fps every 30 s;
  - "Share log" button;
  - "Device report" issue template.

  **v0.1.1-beta published.** Then added `AGENTS.md` + this file.

- **2026-11-21**: Baja Buggy "flies off" bug traced on the S23 with a new physics trace; root
  cause was the port's `frexp` byte order (engine PR #6). Debug APKs now signed with the release
  key. Added `DOCS_DEV_SETUP.md` and versioned ADB runner `tools/test-run.ps1`. Implemented
  Phase 0 (`wm_mod.h` / `wm_tuning.c` / `wm_mod.c`, `Data/wm_tuning.ini`) and Quick Win 1.1
  (rear-right wheel dust fix in `B940.c`, terrain-aware smoke/dirt/snow fogs, raised smoke pool).

---

## 6. Open work (next steps)

1. **arm64-v8a port**: LP64 conversion in the engine (under a `TARGET_PC64`-style macro) +
   load in the low 4 GB. It unlocks the Play Store and 64-bit-only phones.
2. ADPF performance hint for the idle menu frame drop.
3. Multi-device testing (S20 FE, Snapdragon tablets). Collect `dd64.log` via "Share log".
4. Optional: dump all 13 songs / seamless loop points / replace SFX and voice.
5. Logo and app icon.
