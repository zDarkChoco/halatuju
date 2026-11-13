# HalaTuju — Technical Specification (Stage 1 / v0.1.0)

**App Name:** HalaTuju  
**Package ID:** `com.mbk.halatuju`  
**Primary Test Region:** Kuala Lumpur / Klang Valley, Malaysia (works worldwide)  
**Target Platform:** Android (`minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`)  
**License & Data Compliance:** 100% Open-Source stack & OpenStreetMap data (ODbL 1.0)

---

## 1. Hard Constraints & Open-Data Compliance

1. **Zero Proprietary Location/Map/Routing SDKs:** No Google Maps SDK, no Mapbox proprietary SDKs, no HERE, no TomTom, no closed API keys.
2. **Map Rendering:** MapLibre Native Android (`org.maplibre.gl:android-sdk-opengl`) with vector tiles, 2.5D camera pitch (`45°–60°`), course-up rotation, and 3D building extrusion (`fill-extrusion`).
3. **Map Tiles & Styles:**
   - **Default Light Style:** OpenFreeMap Liberty (`https://tiles.openfreemap.org/styles/liberty`, includes `building-3d` `fill-extrusion` layer, no API key).
   - **Default Dark Style:** Custom bundled `halatuju_dark.json` MapLibre style targeting OpenFreeMap's `openmaptiles` vector source (`https://tiles.openfreemap.org/planet`), because OpenFreeMap's upstream `dark` style omits 3D building extrusion (`fill-extrusion`). Our custom dark style preserves full 3D extruded buildings, high-contrast expressway/arterial geometry, and legible dark-mode typography.
   - **Configurability:** Tile/style URLs are configurable in Settings (and routed through `TileSource`), allowing drop-in replacement with a self-hosted Protomaps PMTiles or OpenMapTiles server.
4. **Routing:** Valhalla (`https://valhalla1.openstreetmap.de/route` default, configurable in Settings).
   - Requests use `format: "osrm"`, `shape_format: "polyline6"`, `banner_instructions: true`, `voice_instructions: true`, `language: "en-US"`, `filters` including `shape_attributes.speed_limit`, `shape_attributes.speed`, `shape_attributes.time`, `shape_attributes.length`.
   - Extracts `routes[].legs[].steps[].bannerInstructions`, `voiceInstructions`, `intersections[].lanes`, `legs[].annotation.maxspeed`, `legs[].annotation.speed`, and `legs[].annotation.distance`/`duration`.
   - Requests `alternates: 2` (up to 3 total routes) on preview when alternates are supported, and sends `date_time` for time-dependent costing where supported (note: empirical testing against `valhalla1.openstreetmap.de` v3.9.1 confirmed `date_time` disables alternates in a single query, so preview fetches alternates cleanly and time-dependent ETA is queried/applied per `docs/TRAFFIC.md`).
   - Supports `costing_options.auto` (`exclude_tolls`, `exclude_highways`, `exclude_ferries` — critical for Malaysia's tolled expressways like AKLEH, MEX, ELITE, LDP, PLUS).
5. **Geocoding:**
   - **Forward / Search-as-you-type:** Photon (`https://photon.komoot.io/api`) with viewport/GPS location bias (`lat`, `lon`), ~300 ms debounce, LRU response cache, configurable endpoint.
   - **Reverse Geocoding:** Nominatim (`https://nominatim.openstreetmap.org/reverse?format=jsonv2`) strictly rate-limited to `<= 1 request/second` via a token-bucket/mutex rate limiter + spatial cache, configurable endpoint.
6. **HTTP Etiquette:** Every outbound HTTP call sends a descriptive `User-Agent: HalaTuju/<version> (Android; +https://github.com/zDarkChoco/halatuju)` header, caches responses, and respects fair-use limits.
7. **Location Provider:** Android platform `LocationManager` (`GPS_PROVIDER` + `NETWORK_PROVIDER`) behind `LocationSource`, working identically on de-Googled AOSP/GrapheneOS/LineageOS devices without Google Play Services. Optional `FusedLocationProvider` adapter interface is provided separately without hard GMS runtime dependency.
8. **Attribution & ODbL:** Always displays `"© OpenStreetMap contributors"` on the map surface (never hidden or auto-collapsed) and includes an About / Open-Source Licenses screen covering OSM ODbL 1.0, OpenFreeMap, Valhalla, Photon, Nominatim, and MapLibre.

---

## 2. Navigation Core Evaluation: Ferrostar vs. Custom Engine

We evaluated **Stadia Maps Ferrostar** (`com.stadiamaps.ferrostar:core:0.59.0`, BSD-3, Rust UniFFI + Kotlin/Compose + MapLibre):

| Requirement | Ferrostar (`0.59.0`) Behavior | Verdict for HalaTuju |
|---|---|---|
| ENU Constant-Velocity Kalman Filter (accuracy-weighted noise + outlier rejection) | Not built-in; passes raw `UserLocation` into Rust `NavigationController` | **Gap** |
| Circular Bearing Smoothing + Hold `< 2 m/s` | Uses raw `course_over_ground` or segment bearing without velocity threshold or circular filtering | **Gap** |
| Monotonic Forward-Only Snap-to-Route (`max(25m, 2×acc)` & `45°` heading agreement) | Uses global Euclidean closest-segment lookup (`index_of_closest_segment_origin`); vulnerable to opposite-carriageway/overpass snaps | **Gap** |
| Off-Route Hysteresis (`N` consecutive fixes AND `5–8s`) | Single-fix `StaticThreshold` check in `deviation_detection.rs` | **Gap** |
| Tunnel / GPS-Loss Dead Reckoning | None; suppresses or freezes updates | **Gap** |
| Speed-Based Auto-Zoom with Hysteresis + 300 m Maneuver Zoom-in | Fixed zoom (`NavigationActivity.Automotive(zoom = 16.0)`) | **Gap** |
| Valhalla `annotation.maxspeed` & `intersections[].lanes` Parsing | Exposed only as raw JSON blobs (`annotation_json`); no typed per-segment speed-limit badge or lane-indication icons | **Gap** |
| Android 16 `ProgressStyle` Live Update Notification | Simple `ForegroundServiceManager` hook only | **Gap** |
| Multi-Stop Reordering & Arrival Leg Advancement | Partial (`break`/`via` waypoints); opaque UniFFI Rust state machine complicates custom ENU state injection | **Partial** |

**Decision:** Build a clean, pure-Kotlin navigation engine in `:core:navigation` (inspired by Ferrostar's clean trait boundaries for `LocationSource`, `Router`, and `SimulatedLocationSource`). This avoids JNA/native-Rust binary coupling, makes 100% of navigation math (Kalman, snapping, bearing wrap, off-route hysteresis, dead reckoning, auto-zoom, speed limits, lanes) directly unit-testable on the JVM, and surfaces full internal diagnostics to the Developer Debug Overlay.

---

## 3. Modular Architecture

- `:app` — Application, Hilt DI graph, `MainActivity` (edge-to-edge, runtime permissions, `FLAG_KEEP_SCREEN_ON` lifecycle, NavHost), About/ODbL screen.
- `:core:model` — Immutable Kotlin domain types (`LatLng`, `GeoBounds`, `Waypoint`, `LocationFix`, `FilteredLocation`, `NavRoute`, `RouteLeg`, `RouteStep`, `Maneuver`, `BannerInstruction`, `VoiceInstruction`, `LaneInfo`, `LaneIndication`, `SegmentSpeedLimit`, `TrafficEstimate`, `NavigationProgress`, `NavigationUiState`, `AppSettings`, `RecentPlace`, `FavoritePlace`).
- `:core:storage` — Room database (`RecentPlaceEntity`, `FavoritePlaceEntity`), DataStore preferences (`SettingsStore`), and repository implementations.
- `:core:location` — `LocationSource` interface, `PlatformLocationSource` (`LocationManager`), optional `FusedLocationAdapter` interface, `SimulatedLocationSource` (1x/2x/4x speed, synthetic Gaussian noise, GPS dropout mode, deliberate off-route injection), `GpxCodec` (import/export GPX 1.1), `GpxRecorder`, `GpxReplayLocationSource`.
- `:core:map` — `TileSource` interface, `OpenFreeMapTileSource` (Liberty + custom `halatuju_dark.json` with 3D buildings), `MapController` & `MapCanvasView` (MapLibre Native Android OpenGL wrapper with 60 fps `Choreographer` puck interpolation, active/traveled route lines, 2.5D camera with lower-third padding, overview/recenter controls, always-visible OSM attribution, Developer Debug Overlay).
- `:core:routing` — `Router` interface, `ValhallaRouter` (OSRM + `bannerInstructions` + `voiceInstructions` + `intersections[].lanes` + `annotation.maxspeed`, `User-Agent`, OkHttp disk/memory cache), `TrafficProvider` interface, `KlPeakTrafficProvider` (Valhalla `date_time` + KL weekday 07:00–09:30 / 17:00–20:00 curve + live observed/expected ratio adaptation).
- `:core:geocoding` — `Geocoder` interface, `PhotonGeocoder` (search-as-you-type with bias + cache), `NominatimGeocoder` (reverse geocode with 1 req/s token-bucket limiter + cache).
- `:core:navigation` — `EnuKalmanFilter`, `BearingSmoother`, `RouteSnapper`, `OffRouteDetector`, `DeadReckoner`, `SpeedLimitTracker`, `OverspeedDetector`, `AutoZoomController`, `NavigationEngine` + `NavigationSessionManager`.
- `:core:voice` — `VoiceGuidanceController`, `AndroidTtsSpeaker` (early/medium/final speed+distance scheduling, once-per-stage deduplication, transient ducking audio focus, Bluetooth support, overspeed chime, mute toggle, resource strings structured for future Bahasa Melayu localization).
- `:feature:search` — Home floating search card (`From` + `Where to?` + swap), focused-only Recents & Home/Work Favorites, Photon search suggestions, map-tap reverse geocode, Route Preview sheet (up to 3 alternatives, ETA/distance/arrival time, "estimated" traffic indicator, Add/Reorder/Remove intermediate stops, Start CTA).
- `:feature:navigation` — Active guidance UI (top maneuver banner + signpost/exit badge + `"Then ..."` chip + roundabout exit badge, vector lane-guidance strip, regulatory speed-limit badge + overspeed red tint, bottom progress strip + End button, mute/overview/recenter FABs, intermediate stop arrival prompt + continue to next leg, GPS weak / Rerouting indicators, adaptive landscape/tablet side-rail layout, foreground service + Android 16 Live Update `ProgressStyle` notification + API <36 fallback).
- `:feature:settings` — Settings screen (Theme, Units, Voice, Overspeed tolerance, Avoid tolls/highways/ferries, Service endpoints, Simulation & GPX controls, Developer debug overlay toggle, About/Licenses link).

---

## 4. Technical Algorithms & Contracts

### 4.1 ENU Kalman Filter (`EnuKalmanFilter`)
- State vector in local East-North-Up tangent plane (meters relative to trip anchor): $\mathbf{x} = [e, n, v_e, v_n]^T$.
- Constant-velocity transition over $\Delta t$: $e' = e + v_e \Delta t$, $n' = n + v_n \Delta t$.
- Process noise $Q$ modeled as piecewise white-noise acceleration ($\sigma_a \approx 1.8\,\text{m/s}^2$ for road vehicles).
- Measurement noise $R = \text{diag}(\sigma_p^2, \sigma_p^2)$ where $\sigma_p = \max(4.0, \text{accuracyMeters})$.
- **Outlier rejection:** Reject fixes requiring implied speed $> 55\,\text{m/s}$ ($198\,\text{km/h}$) or Mahalanobis distance $d^2 > 25.0$ when prior covariance is tight, avoiding impossible GPS teleports.

### 4.2 Circular Bearing Smoothing (`BearingSmoother`)
- Only trust GPS bearing when speed $\ge 2.0\,\text{m/s}$ (or Kalman speed $\ge 2.0\,\text{m/s}$).
- Below $2.0\,\text{m/s}$, hold the last reliable bearing to prevent stationary spinner jitter.
- Smooth angles via shortest-arc circular interpolation / unit-vector EMA: $(\sin \theta, \cos \theta)$ blended with $\alpha \in [0.25, 0.45]$ so $359^\circ \to 0^\circ$ wraps seamlessly without passing through $180^\circ$.

### 4.3 Monotonic Heading-Gated Snap-to-Route (`RouteSnapper`)
- Corridor threshold: $d_{\max} = \max(25.0\,\text{m},\; 2.0 \times \text{accuracyMeters})$.
- Heading agreement threshold: $|\Delta \theta| \le 45^\circ$ between smoothed vehicle heading and candidate route segment bearing (if vehicle has valid heading).
- Monotonic progress constraint: search candidate segments within a forward look-ahead window starting at `max(0, lastSnappedDistance - 15m)` (only permitting tiny backward jitter $\le 15\,\text{m}$ for GPS settlement, never jumping backward across loops or snapping to opposite carriageways/overpasses).
- Returns snapped coordinate, distance along route, lateral cross-track error, and current shape segment index (for `maxspeed` lookup).

### 4.4 Off-Route Hysteresis (`OffRouteDetector`)
- Fix is "outside corridor" when no route segment ahead passes the `max(25m, 2*accuracy)` corridor OR heading diverges $> 45^\circ$ while lateral error $> 15\,\text{m}$ (unless accuracy $> 65\,\text{m}$, indicating noisy urban canyon).
- Trigger reroute **only** after `consecutiveOffRouteCount >= 5` AND elapsed off-route time $\ge 6.0\,\text{s}$ (within the 5–8 s spec), with minimum cooldown `6.0 s` between reroute requests.
- Enters `Rerouting` state while `Router` recomputes from current smoothed position/heading through remaining waypoints.

### 4.5 Tunnel / GPS-Loss Dead Reckoning (`DeadReckoner`)
- If no fresh GPS fix arrives within `2.5 s` while navigating (or signal marked stale/lost in tunnel), extrapolate forward along the active route polyline at `lastGoodSpeedMps` (clamped and gently decayed after 15 s) and show a subtle `"GPS signal weak"` status pill until live fixes resume.

### 4.6 Auto-Zoom by Speed with Hysteresis & Maneuver Zoom-In (`AutoZoomController`)
- Baseline zoom curve (automotive):
  - Speed $\le 35\,\text{km/h}$ (`9.72 m/s`) $\to$ `zoom = 17.6` (city street detail)
  - Speed $\ge 80\,\text{km/h}$ (`22.22 m/s`) $\to$ `zoom = 14.6` (highway horizon)
  - Smooth cosine/smoothstep interpolation between `35 km/h` and `80 km/h`.
- **Anti-breathing hysteresis:** Effective speed used for zoom uses a deadband ($\pm 4\,\text{km/h}$) + asymmetric slew-rate limiting (`<= 0.35 zoom/s`) so minor speed oscillations never cause zoom "breathing".
- **Maneuver zoom-in:** Within `300 m` of an upcoming maneuver, smoothly bias target zoom toward `17.8` (ramping up from `300 m` down to `80 m`), then smoothly restore to speed-based zoom once past the maneuver.

### 4.7 Speed Limit Badge & Overspeed Chime
- Lookup `leg.annotation.maxspeed[segmentIndex]`.
- If `unknown` or `null` or `none`, **hide** the speed-limit badge completely (never guess).
- When known (e.g., `60`, `80`, `90`, `110 km/h`), render a Vienna-convention round regulatory badge (white circle, thick red ring, bold black numerals; converted to mph only if user picks imperial units, keeping source unit clear).
- **Overspeed hysteresis:** When `currentSpeedKmh > limitKmh + toleranceKmh` (default tolerance `+5 km/h`), tint speed readout red and play a non-intrusive chime **once**. Do not replay until `currentSpeedKmh <= limitKmh` (drops back under the limit).

### 4.8 Live Updates (Android 16 API 36+) & Foreground Service
- Foreground service (`foregroundServiceType="location"`) with `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` + `POST_NOTIFICATIONS` + `POST_PROMOTED_NOTIFICATIONS`.
- On **API 36+ (Android 16)**: Build with `NotificationCompat.Builder` using `NotificationCompat.ProgressStyle` (showing traveled vs remaining route progress segments & tracker point), `setOngoing(true)`, `setRequestPromotedOngoing(true)`, `setShortCriticalText(etaShort)` (e.g., `"18 min"`), `contentTitle` = next maneuver & distance, `contentText` = street name & arrival time, and an `End` action `PendingIntent`. No `RemoteViews`, no `setColorized(true)`, no group summary, channel importance `DEFAULT` (never `IMPORTANCE_MIN`).
- On **API 26–35 (Android 8–15)**: Standard ongoing navigation notification with maneuver icon, distance, street name, ETA, progress bar (`setProgress`), and `End` action.
- Updates throttled to `<= 1 Hz` (or significant maneuver/distance change) to protect SystemUI responsiveness.

---

## 5. Multi-Screen, Accessibility & Quality Standards

- Adaptive layouts using `WindowSizeClass` (Compact / Medium / Expanded):
  - Portrait phones (`360×640 dp`, `412×915 dp`): top maneuver/lane banner, bottom progress card, speed badge + controls anchored cleanly inside safe system-bar & cutout insets.
  - Landscape & Tablets (`840×412 dp`, foldable unfolded, `1280×800 dp`): left guidance rail (`340–380 dp`) housing maneuver banner, lane strip, speed limit, progress, and End action; right area dedicated to unobstructed 2.5D map + attribution.
- Edge-to-edge (`enableEdgeToEdge`), `displayCutout` & gesture bar `WindowInsets` padding, `200%` font-scale resilience (scrollable/flexible containers, `maxLines` + `TextOverflow.Ellipsis` where bounded, min `48 dp` touch targets), full `semantics { contentDescription }` TalkBack coverage.
- Screen wake lock strictly via `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` (never `PARTIAL_WAKE_LOCK`) active **only** while active navigation UI is visible on screen, cleared immediately on End, Arrival, or `ON_STOP` backgrounding.
