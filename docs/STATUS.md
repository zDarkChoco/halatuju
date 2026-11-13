# HalaTuju — Build & Verification Status

> Updated continuously after each verified milestone. Only verified facts, actual command outputs, and real limitations belong here.

## Environment & Toolchain Verified
- **JDK:** Eclipse Temurin `21.0.13+9-LTS` (`/home/antigravity/jdk-21`)
- **Gradle:** `9.6.1` (`./gradlew`)
- **Android Gradle Plugin:** `9.5.1` (Built-in Kotlin + KSP `2.3.13` + Hilt `2.60.1`)
- **SDK:** `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37` (Android 17 stable platform + Build Tools `37.0.0` / `36.1.0` + Android 16 platform `36` installed)
- **Emulator / Hardware Acceleration Limitation in Local VM:** The dedicated remote Linux VM has no `/dev/kvm` device node; therefore, full hardware-accelerated x86_64 Android 16 (API 36) and Android 14 (API 34) emulator instrumentation tests + Live Update notification checks run on GitHub Actions `ubuntu-latest` (KVM-enabled), with screenshots and logs downloaded and visually inspected from CI artifacts. Local UI screenshots are rendered and verified via Roborazzi + Robolectric Native Graphics mode (`API 36`).

## Live Endpoint Verification (Pre-implementation API Contract Checks)
- **OpenFreeMap:**
  - `https://tiles.openfreemap.org/styles/liberty` verified containing `building-3d` `fill-extrusion` (`minzoom: 14`, `render_height`/`render_min_height`).
  - `https://tiles.openfreemap.org/styles/dark` verified lacking 3D building extrusion layers -> custom [`halatuju_dark.json`](../core/map/src/main/assets/halatuju_dark.json) authored using the `openmaptiles` source and `Noto Sans` glyphs with `building-3d` `fill-extrusion` and configurable `__TILE_SOURCE_URL__`.
- **Valhalla (`https://valhalla1.openstreetmap.de/route` v3.9.1):**
  - Verified live responses saved in `core/routing/src/test/resources/fixtures/` for KLCC → KLIA (with alternates), `exclude_tolls: true` (`Lebuhraya Kuala Lumpur–Seremban, B48` vs `E20, E6`), and multi-stop (`KLCC → KL Sentral → KLIA`, 2 legs).
  - Verified empirical requirement: `filters` with `shape_attributes.speed_limit`, `speed`, `time`, `length` is required alongside `format: "osrm"` to populate `annotation.maxspeed`, while keeping `bannerInstructions`, `voiceInstructions`, and `intersections[].lanes`.
- **Photon & Nominatim:**
  - Verified live responses saved in `core/geocoding/src/test/resources/fixtures/` (`photon_klcc.json`, `nominatim_reverse_klcc.json`).

## Milestone Progress
- **M0 (Repo, Modules, CI Green, Empty App Installs):** Local verification **PASSED** (`./gradlew recordRoborazziDebug verifyRoborazziDebug testDebugUnitTest lintDebug assembleDebug assembleRelease`; 0 lint errors; `app-debug.apk` 61 MB, `app-release.apk` 42 MB R8-shrunk; screenshots [`m0_shell_light.png`](screenshots/m0_shell_light.png) & [`m0_shell_dark.png`](screenshots/m0_shell_dark.png) visually inspected). Pushing initial `main` commit to verify GitHub Actions CI.
- **M1–M8:** Pending.

## Known Open-Data & Platform Limitations
- **Live Traffic:** No free global live traffic speed feed exists in open data. ETA uses Valhalla base costing (`date_time` when supported) + KL weekday peak congestion model (`docs/TRAFFIC.md`, explicitly labeled `"estimated"`) + live observed/expected pace adaptation during active trips.
- **Lane & Speed-Limit Coverage:** Lane strip and speed-limit badge accurately reflect OSM tags returned by Valhalla; UI hides lane strip or speed-limit badge whenever OSM lacks data rather than guessing.
