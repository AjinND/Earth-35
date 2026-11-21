# Phase 4: built environment

Completed and deployed to the saved main city on 9 October 2026 after validation in the separate production preview. Phase 4 redesigns New Eden's 46 houses, 64 apartment addresses and 11 offices within their existing land and navigation reservations. It is a frontend change: geography remains version 4, with no database migration or changes to resident homes, ownership, employment, private interiors, accepted actions or world history.

## Forms and materials

Five house forms use hip, gable, flat courtyard-terrace and shed roofs, with one- and two-story silhouettes, shutters, chimneys and shallow verandas. Five apartment forms use two to four floors, setbacks, narrow upper wings, balconies and roof terraces. Three office forms use two to four floors, larger glazing, facade piers, prominent doors and roof service cores. Districts share restrained plaster, timber, stone and roof palettes; address-based variation is deterministic across reloads.

Ground-floor walls are axis-aligned at 3.35 map units from each building center. This fills the existing server navigation envelope (3.5 units) consistently. The first-person viewer retains its 3.9-unit clearance. Roofs, balconies and entrance details stay inside that clearance; the redesign moves no building or entrance. Floor heights are 2.7–3.4 map units, proportioned to the existing resident models. These are stylized map dimensions, not a change to the island's physical scale.

Doors sit on the front wall immediately behind the authoritative entrance at `z + 3.6`. Thresholds, framed glazing, handles, address plaques and shared-clock exterior lights make the entrance visible. The existing street-to-door paths and admission/exit protocol remain in use. All four elevations have supported windows. Windows retain a deterministic mixture of dark and illuminated panes at night, using the same material and world-clock lighting as the rest of the city.

## Roof correction

The earlier villa used an offset, rotated cone with a mismatched side wing and facade details. The new hip and gable meshes have explicit, closed surfaces matched to their supporting wall footprints. Shed roofs also use a closed wedge, and flat roofs have supported parapets and service cores. No roof depends on a rotated primitive fitting a different wall width. Villas and bungalows use a single veranda canopy, avoiding overlapping coplanar awnings.

Regression checks raycast the entire roof footprint, including eaves and corners, for all thirteen building forms. They also inspect outward wall faces, window backing, the entrance approach, and lateral walking clearance. Close-up review includes all five house roof styles, alongside desktop/mobile day, night and first-person views.

## Rendering and compatibility

Opaque building surfaces are merged into one vertex-colored geometry; illuminated windows and door lights use one additional geometry and the existing shared lighting material. A detailed building therefore needs two material draw calls, excluding its optional HTML label and shadow pass. Distant models remove small frames, shutters and balcony railings while retaining roof silhouettes, entrances and lit windows. Geometry is memoized by address, type, district, entrance and detail level and is disposed when replaced.

There is no new decorative animation. Reduced-motion preferences continue to govern camera transitions and weather. Main, overview and first-person views share the same building renderer. Completed resident-built houses and offices receive the same designs; construction progress and admission remain server-owned. Legacy geography versions 0–1 keep their existing renderer.

## Validation and preview

The saved main city is available at <http://localhost:3000>, and the production preview remains at <http://localhost:3002> with its separate, disposable landscape-preview database. The main city and its provider runtimes were stopped during preview validation, then resumed with the user's main-city deployment request.

All **14 distinct frontend checks passed** in staged runs, along with TypeScript and the production build. `web/tests/built-environment.spec.ts` covers existing addresses, geometry, bounded detail, desktop/mobile entrance clearance, shared-clock night lighting and five house roof close-ups. Existing map, environment, immersive-view and indoor-occupant tests provide integration regression coverage. The entry test now waits for the asynchronously loaded scene before checking avatar visibility. The initial run lost the preview service near its end; the remaining exploration and map tests passed after restarting it. The exact runs and production image are recorded in `artifacts/phase4/verification.json`; screenshots are under `web/test-results-phase4-final/`, `web/test-results-phase4-resumed/` and `web/test-results-phase4-entry/`.

All **429 distinct backend tests passed across staged runs**. The unchanged backend was checked against its existing build image: all 134 Java, SQL, JSON and YAML source files matched the workspace. The initial full suite passed 428 tests, while `OrganicIslandTest.vehiclesStayOnTheRenderedCurvedRoadsAndExportPreview` exceeded its five-second travel-observation threshold; an immediate rerun reproduced it. After resuming in an otherwise idle environment, all five `OrganicIslandTest` cases passed with the original threshold. No backend code or test threshold was changed. The residential-entry, privacy, workplace and other behavior checks passed in the full run.

To rerun the frontend checks from `web/` against the preview:

```powershell
$env:E35_WEB_URL="http://127.0.0.1:3002"
# Set PLAYWRIGHT_CHROMIUM to an installed Chromium executable if necessary.
npm.cmd run typecheck -- --incremental false
npm.cmd run test:e2e -- tests/built-environment.spec.ts tests/building-entry.spec.ts tests/environment.spec.ts tests/immersive.spec.ts tests/map.spec.ts --workers=1
```

## Main-city deployment

The rollout reused the exact validated frontend image, `sha256:9bbf4ce0445d3e0c8723ef579117d5b58c446dcf362dc285f5c2a0a8a2373b4e`, and preserved the previous frontend as `earth35-web:before-phase4-20261009`. A custom-format PostgreSQL backup was taken before the switch. All 13 public tables had identical row counts and content fingerprints before and after replacing the frontend while the API remained stopped. The existing API container, its configuration, and the `earth35_world-data` volume were reused. Geography remains version 4, with 174 places, five residents and 25 applied migrations; resident IDs and home assignments were verified after resuming the clock.

Live browser checks used the actual saved main city at its configured `http://localhost:3000` origin without route mocks: house, apartment and office views on desktop and mobile, both island overviews, first-person house arrival, walking and wall clearance on both viewports, and incoming WebSocket world updates. No browser rendering errors, page errors or mutating requests occurred. The backup metadata, image IDs, database checks and browser evidence are recorded in [the deployment record](../artifacts/phase4-main-deployment-20261009/deployment-verification.json).

[Phase 5: habitat vegetation and ambient wildlife](living-environment.md) is now available in the separate preview. [Phase 6: two moons and atmosphere](sky-atmosphere.md) is now available in the separate preview.
