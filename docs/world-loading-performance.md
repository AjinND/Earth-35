# World loading

Deployed with all current changes on 18 October 2026. See the [deployment record](../artifacts/all-changes-20261018/deployment/deployment.json) for production builds, live browser verification and saved-world preservation.

The island, coastline, river, trails and streets use their original surveys and
terrain detail. Loading avoids repeated full-survey scans and repeated geometry
construction:

- Static segment indexes prune distant sections before computing exact nearest
  distances. Equal-distance river segments retain survey order.
- Latitude bands restrict island containment checks to coastline edges that can
  cross the ray, using the original polygon test.
- Map and first-person scenes share terrain, coast, trail, river and vegetation
  geometry. Materials, animation state and cameras remain local to each scene.
- Geometry is retained while either scene uses it. After the last release, the
  cache keeps at most one unused entry for up to 30 seconds, then disposes it.
  Vegetation is keyed by building and object placement, so construction and moved
  objects still regenerate planting.
- The dashboard canvas stops its frame loop during first-person exploration and
  resumes on return without discarding its camera or scene.
- The renderer download starts alongside the world request. Hovering or focusing
  the entry button preloads the first-person module.

## Verification

`web/tests/world-loading.spec.ts` compares indexed queries with brute-force
surveys, checks shoreline boundary behavior, exercises cache ownership/disposal,
and verifies walking, live world updates, repeated entry and dashboard resume.
Existing landscape, river-mouth, planting and habitat regressions also pass.

An instrumented local Chromium run against the development server, using the
same city-growth fixture (geography version 4, clear noon, no residents), measured:

| Operation | Before | After |
| --- | ---: | ---: |
| Initial browser load | 65.9 s | 6.0 s |
| Page reload | 62.6 s | 7.9 s |
| First-person entry | 60.4 s | 4.4 s |
| First-person re-entry | 55.3 s | 3.9 s |

These are individual local development measurements with CPU profiling enabled,
not production latency guarantees. API responses were supplied by a fixed browser
fixture to isolate client loading; network/server latency was not measured.
The separate geometry benchmark retained all 170,760 terrain vertices, 304,409
terrain triangles and 5,318 plants. Production compilation and TypeScript checks
passed. The local benchmark scripts, raw timings, CPU profiles and screenshots
are in the ignored `artifacts/loading-performance/` directory.

## Saved-city deployment

The frontend was rebuilt and deployed to `http://localhost:3000` on 11 October
2026. The API, database and embeddings kept their existing containers and remained
healthy. Live verification found all five residents and 174 places present.
Desktop and mobile map controls, first-person entry, return and re-entry passed
without browser or rendering errors.

The deployed production app measured 5.1 seconds for initial load and reload,
2.6 seconds for first-person entry, and 2.2 seconds for mobile re-entry in one live
Chromium run. These timings include the live world API and differ from the
development fixture measurements above. Deployment records and screenshots are
in `artifacts/loading-performance/deployment/`. The prior frontend image is
retained as `earth35-web:before-loading-20261011` for rollback.
