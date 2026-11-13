# Self-Hosting HalaTuju Backends (Valhalla, Tiles & Geocoding)

HalaTuju defaults to public, zero-key community servers (`valhalla1.openstreetmap.de`, `tiles.openfreemap.org`, `photon.komoot.io`, `nominatim.openstreetmap.org`) with caching, request debouncing, and fair-use rate limiting. For production deployments, heavy testing, or full data autonomy in Malaysia / Klang Valley, you can self-host any or all endpoints and point the app to them in **Settings → Service endpoints**.

---

## 1. Self-Hosting Valhalla with Geofabrik `malaysia-singapore-brunei`

### Prerequisites
- Docker + Docker Compose v2
- Recommended host specs for `malaysia-singapore-brunei` (~150–250 MB `.osm.pbf`):
  - **RAM:** 4 GB+ (tile building peak; serving uses <1.5 GB)
  - **Disk:** 10 GB free
  - **CPU:** 2+ cores

### Quick Start (`docker run`)

```bash
mkdir -p valhalla_data/custom_files

docker run -dt \
  --name halatuju-valhalla \
  -p 8002:8002 \
  -v "$PWD/valhalla_data/custom_files:/custom_files" \
  -e tile_urls=https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf \
  -e build_admins=True \
  -e build_time_zones=True \
  -e build_elevation=False \
  -e build_tar=True \
  -e serve_tiles=True \
  -e server_threads=4 \
  ghcr.io/nilsnolde/docker-valhalla/valhalla:latest
```

### Production `compose.yaml`

```yaml
services:
  valhalla:
    image: ghcr.io/nilsnolde/docker-valhalla/valhalla:latest
    container_name: halatuju-valhalla
    restart: unless-stopped
    ports:
      - "8002:8002"
    volumes:
      - ./valhalla_data/custom_files:/custom_files
    environment:
      tile_urls: "https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf"
      build_admins: "True"
      build_time_zones: "True"
      build_elevation: "False"
      build_tar: "True"
      serve_tiles: "True"
      update_existing_config: "True"
      server_threads: "4"
```

Start the stack and follow tile extraction logs:

```bash
docker compose up -d
docker logs -f halatuju-valhalla
```

First-time graph construction typically takes 3–8 minutes depending on CPU. Wait until you see `valhalla_service` listening on port `8002`.

### Verify Routing, Lane Data, and Speed Limits (KLCC → KLIA)

```bash
curl -sSf -X POST "http://localhost:8002/route" \
  -H "Content-Type: application/json" \
  -d '{
    "locations": [
      {"lat": 3.1579, "lon": 101.7116},
      {"lat": 2.7456, "lon": 101.7099}
    ],
    "costing": "auto",
    "format": "osrm",
    "shape_format": "polyline6",
    "banner_instructions": true,
    "voice_instructions": true,
    "alternates": 2,
    "language": "en-US",
    "filters": {
      "attributes": [
        "shape_attributes.speed_limit",
        "shape_attributes.speed",
        "shape_attributes.time",
        "shape_attributes.length"
      ],
      "action": "include"
    }
  }' | jq '.routes | length'
```

Expected output: `2` or `3` routes, with populated `legs[].annotation.maxspeed`, `steps[].intersections[].lanes`, `bannerInstructions`, and `voiceInstructions`.

### TLS & Reverse Proxy (Caddy Example)

HalaTuju enforces HTTPS via [`app/src/main/res/xml/network_security_config.xml`](../app/src/main/res/xml/network_security_config.xml): public internet hosts always require TLS (`cleartextTrafficPermitted="false"`), while cleartext HTTP is allowed strictly for local loopback and emulator host debugging (`localhost`, `127.0.0.1`, `10.0.2.2`). Expose remote self-hosted services behind TLS (for example, via Caddy):

```caddyfile
valhalla.yourdomain.example {
    reverse_proxy 127.0.0.1:8002
}
```

Then configure in HalaTuju **Settings → Service Endpoints → Valhalla base URL**:
`https://valhalla.yourdomain.example`

---

## 2. Enabling Time-Dependent Costing (`traffic.tar`)

By default, Valhalla supports time-of-day speeds from historical/predicted speed tiles packaged as `traffic.tar` inside `/custom_files`.

1. Enable `build_time_zones=True` (included above) so Valhalla resolves local Malaysia time (`Asia/Kuala_Lumpur`, UTC+8) when HalaTuju sends `"date_time"`.
2. Mount a custom `traffic.tar` built with `valhalla_ways_to_edges` + observed speed histograms (see [`docs/TRAFFIC.md`](TRAFFIC.md)).
3. Ensure `mjolnir.traffic_extract` in `valhalla_data/custom_files/valhalla.json` points to `/custom_files/traffic.tar` (managed automatically when `traffic_name` is left at default).

---

## 3. Optional Self-Hosted Vector Tiles (Protomaps PMTiles / OpenMapTiles)

HalaTuju loads map tiles via the `TileSource` interface:
- **Light style URL:** defaults to `https://tiles.openfreemap.org/styles/liberty`
- **Dark TileJSON URL:** defaults to `https://tiles.openfreemap.org/planet` (fed into our custom bundled [`halatuju_dark.json`](../core/map/src/main/assets/halatuju_dark.json) MapLibre style with 3D building extrusion)

To self-host Malaysia tiles with zero runtime tile server (static HTTPS + HTTP Range requests via Protomaps) or with Martin/TileServer-GL:
1. Extract a `malaysia.pmtiles` region with `pmtiles extract https://build.protomaps.com/<date>.pmtiles malaysia.pmtiles --bbox=99.5,0.8,119.5,7.5` or serve OpenMapTiles via [Martin](https://martin.maplibre.org/).
2. Update **Light style URL** and **Dark TileJSON URL** in HalaTuju **Settings → Service Endpoints**.

---

## 4. Optional Self-Hosted Geocoding (Photon & Nominatim)

- **Photon** search-as-you-type: extract a Malaysia search index or run `komoot/photon` importing an OSM extract or prebuilt Photon dump.
- **Nominatim** reverse geocoding: deploy `mediagis/nominatim` using the same Geofabrik `malaysia-singapore-brunei-latest.osm.pbf` file.
- Update **Photon URL** and **Nominatim URL** in HalaTuju **Settings → Service Endpoints**.
