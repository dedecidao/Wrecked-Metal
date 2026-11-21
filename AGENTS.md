# AGENTS.md

Instructions for AI coding agents (Claude, Codex, Gemini, Cursor, Copilot, etc.) and
human contributors working on **Wrecked Metal**: a native Android port of an N64
demolition racer, rebuilt from the community decompilation. By Costelinha Games.

## Read first

1. **[AI_MEMORY.md](AI_MEMORY.md)**: the project's long-term memory. It covers decisions,
   gotchas, history and open work. Read it before proposing architecture or touching the
   engine.
2. [DOCS_ANDROID_PORT.md](DOCS_ANDROID_PORT.md): how the Android port works, plus a
   diagnostics table.
3. [DOCS_INTERNALS_AND_OPTIONS.md](DOCS_INTERNALS_AND_OPTIONS.md): game internals,
   options and cheats.
4. [DOCS_DEV_SETUP.md](DOCS_DEV_SETUP.md): minimum dev environment setup, local/CI builds,
   `tools/test-run.ps1`, ADB deploy & debug variables (`DD64_*`).
5. [docs/ARM64.md](docs/ARM64.md) and [docs/PLANO.md](docs/PLANO.md): the 64-bit plan and
   the original port plan.

## Ground rules (non-negotiable)

- **Never commit ROM data or copyrighted assets.** The app is Bring Your Own ROM. The
  `symbols/` files hold only names, addresses and sizes.
- **Never commit secrets.** That includes keystores (`*.jks`, `*.p12`, `*.keystore`),
  `signing.properties` and passwords. The release key lives only on the maintainer's PC
  and in the repo secrets `WM_KEYSTORE_BASE64` / `WM_KEYSTORE_PASSWORD`.
- **Never change `applicationId`** (`com.costelinhagames.wreckedmetal`). Changing it breaks
  updates for everyone who installed the app.
- **Root cause first.** Research the existing code and similar ports (SoH, sm64 ports,
  etc.) before changing anything. No hacks that hide symptoms. Ask when an architectural
  choice is unclear.
- **Keep the PC build working.** Android-only engine changes go under `#ifdef __ANDROID__`
  (or `TARGET_*` macros) in the engine fork.
- **Wrecked Metal enhancements live under `!gPortConfig.fidelity`.** Gameplay, visual and
  audio customizations are centralized in `port/game/wm_mod.c` + `port/src/wm_tuning.c`
  (`Data/wm_tuning.ini`), leaving **Play Fidelity** 100% N64-pure and avoiding bloated menus.
- **Manual PR approval after Android testing.** Never auto-merge customizations straight
  to `port-pc` or `main`: open a branch/PR, verify the CI APK on the Android device first,
  and leave the PR pending manual maintainer approval.
- **Disable all cheats before device tests.** Cheats such as no time limit cause hangs that
  look like bugs. Restore the user's `dd64.ini` afterwards.
- **Public text is in English** (README, release notes, issues). The original game's name
  appears only in the ROM section and the legal disclaimer. Support is Ko-fi donations
  only: no paywalls, no paid early access.
- The maintainer writes in **Portuguese**. Reply in Portuguese in chat; code comments and
  commits are in English.

## Repository layout

| Path | What |
| --- | --- |
| `external/dd64` | Engine submodule: `dedecidao/Destruction-Decomp-64`, branch `port-pc` (decomp + PC port, SDL3, Fast3D) |
| `external/SDL` | SDL 3.4.x submodule |
| `app/src/main/cpp` | Android native glue (`android/*.c`, CMake) |
| `app/src/main/java/io/github/dedecidao/dd64` | Launcher, settings, touch controls (Java package name stays as is) |
| `app/src/debug/resources/lib/*/wrap.sh` | Preloads `libdd64reserve.so` to reserve 0x80000000 (32-bit) |
| `symbols/` | `dd64.syms` and widths, exported from a PC build (no ROM data) |
| `.github/workflows/android.yml` | CI: APK (armeabi-v7a), arm64 probe, nightly, tagged release |
| `.github/release-notes/<tag>.md` | Notes for a tagged release (required by CI) |

## Build and test

- CI builds everything on every push and PR. For a local build: `gradle assembleDebug`
  (or `assembleRelease`, signed only when the key is configured).
- The engine change goes in first: open a PR into `port-pc` in the engine fork, merge it,
  then bump the submodule in this repo.
- Device test: the reference device is a Galaxy S23 Ultra (Adreno 740), driven over adb.
  The minimum bar is a full race with cheats off, no crash, and a steady 60 fps. Every
  session writes a log to
  `Android/data/com.costelinhagames.wreckedmetal/files/Data/Logs/dd64.log`, with the device
  sheet and an fps summary every 30 s.

## Releasing

- Pushing a `v*` tag makes CI build, sign and publish a GitHub Release. The notes come
  from `.github/release-notes/<tag>.md`, whose first `# ` line is the title. A tag with a
  `-` (like `v0.1.1-beta`) is published as a pre-release.
- The maintainer's rule (2026-11-21): test custom features on Android first via branch/CI
  APK, and leave every PR pending manual approval before merging or tagging. If anything
  fails, report instead.

## Reviewing incoming pull requests

Check each PR against this list. Request changes for anything marked **block**.

1. **block** Any ROM bytes, extracted assets, audio or textures from the game, or binary
   blobs of unclear origin.
2. **block** Secrets, keystores, tokens or personal paths.
3. **block** A change to `applicationId`, signing config or the release workflow without a
   clear reason the maintainer agreed to.
4. **block** It breaks the PC build: engine changes outside `#ifdef __ANDROID__` that
   alter shared behaviour, or code that assumes 64-bit pointers in 32-bit paths.
5. **block** CI is red: the APK job (armeabi-v7a) must pass. The arm64 conversion step is
   informational until the 64-bit port lands.
6. It fixes the root cause and explains it. The description says what was wrong and why
   the fix is right, and gives evidence: logs, fps numbers, before/after.
7. It respects the decisions in [AI_MEMORY.md](AI_MEMORY.md), such as defaults, removed
   options, MSAA ≤ 2x and minSdk 29. If it reverses one, it says why.
8. It was tested on a real device with cheats off, and the device model is named.
9. Scope stays small. Unrelated refactors belong in separate PRs.
10. Docs are updated: `DOCS_*` for behaviour changes, and `AI_MEMORY.md` for new
    decisions or gotchas.
11. Public-facing text is in English and makes no claims about features that don't exist.

## Keeping memory up to date

When you finish a piece of work, add what a future agent would otherwise have to
rediscover to [AI_MEMORY.md](AI_MEMORY.md). That means decisions with their reasons,
gotchas and dead ends, and a dated entry in the session log. Keep entries short and
factual. Correct or remove entries that turn out wrong; don't append contradictions.
Transient noise (a single failed test run, command output) does not belong there.
