# OSM Standard / OpenFreeMap + phone heading

## Scope and branch

User explicitly requested a PR, not a main merge, on 2026-11-26. Base main:
`bd62c7311096b18ea7709f022777e49b75abcd34`. Feature branch:
`feature/osm-standard-phone-heading`. No other basemap providers or keys added.
No changes to recording algorithms, SQLite schema, export formats, manifest or bundled UI assets.

## Map networking and privacy

- `MapBasemap` has two stable persisted IDs. Standard uses
  `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, raster tileSize=256, maxzoom=19.
- `MapPolicy` uses new v2 consent because OSM is an additional service/recipient.
  Old v1 consent is not silently reused. Cancel leaves choice unchanged. Offline mode
  uses a style with no remote sources, sprites or glyphs.
- `MapNetwork` installs a process-wide OkHttp 4.12.0 client (same version used by
  MapLibre 13.5.4), identifying StarTrace and its public repository. It limits host
  concurrency to 4, uses a 32 MiB HTTP cache in addition to MapLibre native resource
  storage and honours Cache-Control/Expires/ETag/Last-Modified. No request URLs logged.
- Revocation cancels queued/in-flight HTTP calls and the interceptor rejects further
  requests while consent is absent. Requests already received by a server cannot be undone.
- Every map sets prefetchZoomDelta=0 before loading style. No offline manager, region
  downloader, background map-fetch job or multiple zoom-stack prefetch is used.
- Always-visible clickable contributor attribution; OpenFreeMap/OpenMapTiles attribution
  additionally shown when that provider is selected. MapLibre attribution remains enabled.
- Provider switching calls setStyle on the same MapLibreMap, then rebuilds local overlays.
  Generation guards ignore obsolete style callbacks. Camera, follow mode, track and fix
  timestamps stay unchanged; no GNSS lifecycle method is called.
- Failed maps can be retried or explicitly switched; no silent requests to another provider.
  OSM public tiles have no service guarantee and must not be treated as unlimited hosting.

## Heading

Existing GnssEngine uses TYPE_ROTATION_VECTOR, remaps all four display rotations and
corrects magnetic azimuth with GeomagneticField for true north. MapHeading accepts only
fresh fixes, current sensor samples (0–2000 ms), a subscribed/available sensor and at least
medium accuracy. It never uses Location.bearing or saved track headings.

A Canvas-generated arrow bitmap is a SymbolLayer sourced solely from the live `position`
GeoJSON, above the original position dot. Rotation and pitch alignment are `map`, so true
azimuth stays aligned when the map is rotated/tilted. HeadingSmoother uses shortest-arc
interpolation across north. The 20 Hz heading loop does not rewrite track geometry or
recenter the camera. Pause removes the loop and disables compass sensing; GNSS/recorder
ownership remains independent. Unavailable/unreliable/stale data hide the arrow, with an
explicit UI explanation. Freshness is never faked by resetting sensor or location times.

## Validation and release gates

15 additional JVM tests cover provider IDs/style, heading eligibility/north crossing and
HTTP identity/cache/conditional requests/revoked consent against a local MockWebServer.
5 additional Android tests cover v1 consent migration, actual OSM/OpenFreeMap rendering
and camera/track/GNSS continuity, map-aligned phone heading distinct from course, stale
location hiding, foreground-only heading updates and live recording during style switches.
Existing 111 JVM tests (including 4 Java/Kotlin differential tests) and 31 Android tests remain.
Actual online rendering requests are limited to ordinary synthetic visible viewports.

No physical compass accuracy, magnetic interference, Redmi K70 battery or long screen-off
claims are made. Release stays a pre-release with the existing fixed certificate and two
attachments only: StarTrace.apk and README.md.

Branch signing requires owner workflow_dispatch + exact SHA input + same-repository open,
non-draft PR to main. PR-triggered builds never restore signing secrets. Publishing requires
the exact same SHA/branch, successful build and emulator jobs, 126/36 successful tests,
zero lint errors, APK digest/certificate/ABI checks, original README bytes and exact assets.
No pull_request_target workflow or automatic PR publish is added.

## Official references checked 2026-11-26

- https://operations.osmfoundation.org/policies/tiles/
- MapLibre Native tag android-v13.5.4, `platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapLibreMap.java` (prefetchZoomDelta)
- Same tag, `module/http/HttpRequestUtil.java` / `HttpRequestImpl.java` (custom Call.Factory and cache headers)
- Same tag, `platform/android/gradle/libs.versions.toml` (OkHttp 4.12.0)
- https://developer.android.com/develop/sensors-and-location/sensors/sensors_position
