# HalaTuju — Execution Plan & Milestones

> Living checklist for Stage 1 (`v0.1.0`). Each milestone must compile, pass all tests, and record verification evidence in `docs/STATUS.md` before starting the next milestone.

## Milestones

- [x] **M0 — Repo, Modules, CI Green, Empty App Installs**
  - [x] Initialize Gradle Wrapper, `gradle/libs.versions.toml`, `settings.gradle.kts`, and modular structure (`:app`, `:core:model`, `:core:storage`, `:core:location`, `:core:map`, `:core:routing`, `:core:geocoding`, `:core:navigation`, `:core:voice`, `:feature:search`, `:feature:navigation`, `:feature:settings`)
  - [x] Configure Hilt, Compose BOM, Material 3 (dynamic color off, HalaTuju light & dark palette), Room, DataStore, OkHttp, `kotlinx.serialization`, Roborazzi
  - [x] Configure `.github/workflows/android.yml` (push/PR: build, lint, unit tests, screenshot tests, debug APK artifact; tag `v*`: release APK + GitHub Release with debug-signing fallback)
  - [x] Push initial commit to GitHub and verify M0 CI green

- [x] **M1 — Map, Light/Dark Theme, Location Puck, Attribution**
  - [x] Implement `TileSource` + `OpenFreeMapTileSource` (Liberty light + custom `halatuju_dark.json` with 3D `fill-extrusion` buildings + configurable tile URL)
  - [x] Implement `LocationSource` + `PlatformLocationSource` (`LocationManager` GPS+NETWORK) + runtime permission flow
  - [x] Render MapLibre Native Android (`android-sdk-opengl`) inside `:core:map` with 3D building extrusion, 60 fps interpolated puck, and permanent `"© OpenStreetMap contributors"` attribution
  - [x] Build About / Open-Source Licenses (ODbL) screen and basic Settings theme switcher (Light / Dark / System)
  - [x] Add unit & Roborazzi screenshot tests for M1 Light & Dark map shell

- [x] **M2 — Search (Photon), Nominatim Reverse Geocoding, From/To UI, Recents & Favorites**
  - [x] Implement `Geocoder` interface, `PhotonGeocoder` (search-as-you-type with KL location bias, 300 ms debounce, cache, `User-Agent`), `NominatimGeocoder` (reverse geocode, 1 req/s rate limit, cache)
  - [x] Implement Room entities/DAOs (`RecentPlace`, `FavoritePlace` Home/Work) and DataStore `SettingsStore`
  - [x] Build Home floating search card (`From` defaulting to `"My location"`, `Where to?`, swap button, recents & Home/Work favorites shown only when focused)
  - [x] Add unit tests with recorded KL Photon & Nominatim JSON fixtures + screenshot tests

- [x] **M3 — Routing, Preview, Alternatives, Add/Reorder Stops, Traffic Model**
  - [x] Implement `Router` interface + `ValhallaRouter` (OSRM format, `bannerInstructions`, `voiceInstructions`, `intersections[].lanes`, `annotation.maxspeed`, `alternates`, `costing_options.auto` avoid tolls/highways/ferries, `date_time`, response cache)
  - [x] Implement `TrafficProvider` + `KlPeakTrafficProvider` (documented in `docs/TRAFFIC.md`) + `docs/SELF_HOSTING.md` (Valhalla Docker + Geofabrik `malaysia-singapore-brunei`)
  - [x] Build Route Preview UI (ETA, distance, arrival clock time, "estimated" label, up to 3 alternatives selectable on map & sheet, Add/Reorder/Remove intermediate stops with auto-reroute, big Start button)
  - [x] Add unit tests with recorded KLCC → KLIA Valhalla JSON fixtures (including maxspeed, lanes, alternatives, avoid tolls) + screenshot tests

- [ ] **M4 — Navigation Session: Kalman, Snap, Bearing, Off-Route, Reroute, 2.5D Camera, Auto-Zoom, Wake Lock**
  - [ ] Implement `:core:navigation` (`EnuKalmanFilter`, `BearingSmoother`, `RouteSnapper`, `OffRouteDetector`, `DeadReckoner`, `AutoZoomController`, `NavigationEngine`, `NavigationSessionManager`)
  - [ ] Wire 2.5D camera (`45°–60°` pitch, course-up, lower-third target padding), active vs dimmed traveled route line, 60 fps puck interpolation, overview & recenter buttons
  - [ ] Handle intermediate stop arrival + prompt/auto-continue to next leg
  - [ ] Wire `FLAG_KEEP_SCREEN_ON` strictly while active navigation is visible on screen
  - [ ] Add comprehensive JVM unit tests (Kalman outlier rejection, 359°→0° wrap, parallel-road/overpass snap rejection, 5–8 s + N-fix off-route hysteresis, tunnel dead reckoning, auto-zoom hysteresis + 300 m maneuver zoom-in)

- [ ] **M5 — Banner, Lane Guidance, Speed Limit, Overspeed Chime, Voice**
  - [ ] Build Top Maneuver Banner (maneuver arrow, distance, street/exit name, signpost/exit number badge, `"Then ..."` chip, roundabout exit number + arrow)
  - [ ] Build Dynamic Vector Lane Guidance Strip (all indications + combinations, valid-lane highlight, hidden gracefully when OSM has no lane tags)
  - [ ] Build Speed Limit Badge (regulatory circle, hidden when unknown) + live speed + red overspeed tint & single-chime hysteresis (`+5 km/h` default tolerance)
  - [ ] Implement `:core:voice` (`AndroidTtsSpeaker` with distance/speed-scaled early/medium/final announcements, once-per-stage dedupe, transient ducking audio focus, Bluetooth support, mute toggle, BM-ready string keys)
  - [ ] Build Bottom Progress Strip (remaining time, distance, arrival clock time, End button)
  - [ ] Add unit tests (lane parser, maxspeed parser, TTS stage scheduler, overspeed hysteresis) + screenshot tests

- [ ] **M6 — Foreground Service + Android 16 Live Update Notification**
  - [ ] Implement `NavigationForegroundService` (`foregroundServiceType="location"`)
  - [ ] Implement Android 16 (`API 36+`) `ProgressStyle` + `setRequestPromotedOngoing(true)` + `POST_PROMOTED_NOTIFICATIONS` + `setShortCriticalText` Live Update notification, plus API `< 36` standard ongoing notification fallback
  - [ ] Handle runtime permissions (`ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, optional battery optimization hint) cleanly
  - [ ] Verify notification on both Android 16 (API 36) and Android 12–14 (API 34) emulators in CI + unit tests

- [ ] **M7 — Simulation, GPX Replay/Record, Debug Overlay**
  - [ ] Implement `SimulatedLocationSource` (1x/2x/4x speed, synthetic GPS noise/jitter, tunnel dropout, deliberate off-route trigger) driving the exact real pipeline
  - [ ] Implement GPX 1.1 parser & recorder + share-sheet export
  - [ ] Build full Settings screen (theme, units, voice/volume, overspeed tolerance, avoidances, endpoints, simulation & GPX, Developer toggle)
  - [ ] Build Developer Debug Overlay (raw GPS, Kalman output, snapped position, accuracy circle, off-route state, cross-track error, zoom/speed debug)

- [ ] **M8 — Multi-Screen Polish, Accessibility, Performance, Release `v0.1.0`**
  - [ ] Adaptive `WindowSizeClass` layouts for compact phones (`360×640`), large phones, foldables, tablets, portrait & landscape (side guidance panel)
  - [ ] Edge-to-edge, cutouts, gesture bars, `200%` font scaling, TalkBack semantics audit
  - [ ] Run Roborazzi screenshot suite across all device/theme/font configurations and store reviewed PNGs in `docs/screenshots/`
  - [ ] Add instrumented E2E smoke test (launch → search KLCC → add stop → route KLIA → start simulated nav → reroute/arrival → end) and verify on emulators (API 36 & API 34)
  - [ ] Run full local `./gradlew assembleDebug assembleRelease lint test` + CI checks, tag `v0.1.0`, confirm GitHub Release APK, and finalize `docs/STATUS.md`
