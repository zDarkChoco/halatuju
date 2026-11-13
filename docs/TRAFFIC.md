# Traffic-Aware ETA & Congestion Modeling in HalaTuju

## 1. Honest Open-Data Reality Check

No free, global, real-time road traffic speed feed exists in open data. OpenStreetMap encodes **free-flow road attributes** (road class, surface, `lanes`, `maxspeed`, turn restrictions, signal penalties), not live congestion or incidents.

Rather than claiming live traffic accuracy that public open APIs cannot guarantee, HalaTuju separates **routing**, **time-of-day prior estimation**, and **in-trip live adaptation** behind a clean `TrafficProvider` interface in `:core:routing`.

Every ETA produced using HalaTuju's congestion model is **explicitly labeled as `"estimated"`** in the UI.

---

## 2. `TrafficProvider` Architecture (`:core:routing`)

```kotlin
interface TrafficProvider {
    suspend fun estimateRouteEta(
        route: NavRoute,
        departureTimeEpochMs: Long,
        userHomeRegion: RegionHint = RegionHint.DEFAULT
    ): TrafficEstimate

    fun adaptRemainingEta(
        route: NavRoute,
        currentDistanceAlongRouteMeters: Double,
        baseRemainingSeconds: Double,
        traveledObservedSeconds: Double,
        traveledExpectedSeconds: Double
    ): TrafficEstimate
}
```

### Layer 1 — Valhalla Base ETA (`date_time`)
- HalaTuju passes `date_time` in Valhalla requests (`{"type": 0}` for current time, or ISO local timestamp when scheduled) so any Valhalla instance populated with `traffic.tar` / `predicted_speeds` uses time-dependent costing automatically.
- **Empirical server note (`valhalla1.openstreetmap.de` v3.9.1):** The public FOSSGIS demo server has no proprietary live traffic tiles loaded, and sending `date_time` suppresses alternate route generation (`alternates > 0` collapses to 1 route). Therefore, HalaTuju fetches alternate routes cleanly for preview when `alternates > 0` and evaluates `TrafficProvider` consistently across all candidate routes.

### Layer 2 — Configurable Time-of-Day Congestion Model (`KlPeakTrafficProvider`)
HalaTuju ships a transparent, configurable weekday peak-hour congestion prior tuned for **Kuala Lumpur / Klang Valley** (`Asia/Kuala_Lumpur`, `UTC+8`), active when a route starts or intersects the region (or whenever congestion modeling is enabled):

| Window (Mon–Fri, local time) | Peak Regime | Congestion Multiplier | Example Impact (30 min base) |
|---|---|---|---|
| `07:00 – 09:30` | Morning peak (inbound Federal Hwy, LDP, MEX, AKLEH, Jalan Tun Razak) | `1.32x` | `~40 min (estimated)` |
| `12:00 – 14:00` | Midday arterial buildup | `1.10x` | `~33 min (estimated)` |
| `17:00 – 20:00` | Evening peak (outbound Klang Valley expressways) | `1.38x` | `~41 min (estimated)` |
| Other hours / Sat–Sun | Off-peak / free-flow | `1.00x – 1.05x` | `~30–32 min (estimated)` |

- Smooth 20-minute cosine ramps at window edges prevent discontinuous ETA jumps across `06:59 → 07:00` or `09:29 → 09:30`.
- Outside peak corridors / worldwide, the baseline factor remains near `1.00x` unless historical profile overrides are enabled or a regional traffic plugin is supplied.

### Layer 3 — Live In-Trip Adaptation
Once active guidance begins, `NavigationEngine` tracks rolling traveled progress:
1. For each route edge/segment traversed while snapped on-route, accumulate `expectedSeconds` (from Valhalla `annotation.duration` or step duration ratio) and actual `observedSeconds` (wall clock while speed `>= 1.5 m/s` or actively progressing, bounded to avoid infinite spikes at long traffic lights).
2. Compute a damped speed ratio:
   $$\text{paceRatio} = \text{clamp}\!\left(\frac{\text{traveledObservedSeconds}}{\max(15.0,\, \text{traveledExpectedSeconds})},\; 0.78,\; 1.75\right)$$
3. Blend prior expectation with observed trip pace once enough distance (`>= 600 m` / `>= 45 s`) has been driven:
   $$w_{\text{obs}} = \min\!\left(0.65,\; \frac{\text{traveledDistanceMeters}}{4000.0}\right)$$
   $$\text{adjustedRemaining} = \text{baseRemaining} \times \Big((1 - w_{\text{obs}})\cdot \text{priorFactor} + w_{\text{obs}}\cdot \text{paceRatio}\Big)$$

This lets ETA adapt smoothly when Federal Highway moves faster than average on a public holiday or crawls unexpectedly during a downpour.

---

## 3. UI Transparency Rules
1. Route preview displays `"ETA <duration> · estimated"` (or `"ETA <duration> · estimated (peak traffic)"` during active KL peak windows) alongside base clock arrival time.
2. Active bottom progress bar reflects live-adapted ETA & clock arrival time, smoothly updating without wild 1-second flicker.

---

## 4. Future Extension Points
Because `TrafficProvider` is bound via Hilt `@Binds` in `:core:routing`:
1. **Self-Hosted Valhalla `traffic.tar`:** Populate predicted/live speed tiles on your Valhalla server (see [`SELF_HOSTING.md`](SELF_HOSTING.md)); when server timestamps reflect traffic-aware durations, reduce client-side prior multiplier accordingly.
2. **Open / Municipal Feeds:** Wrap open GTFS-RT bus-probe speeds (e.g., RapidKL / Prasarana GTFS-RT vehicle speeds), crowd-sourced Floating Car Data (FCD), or open regional congestion APIs inside an `OpenTrafficFeedProvider : TrafficProvider` without modifying `:core:navigation` or `:feature:*`.
