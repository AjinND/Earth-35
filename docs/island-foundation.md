# Island foundation, geography v1

Implemented 3 October 2026 against the [world-building vision](world-building-vision.md). New Eden is the island's only active settlement.

## Dimensions and coordinates

The local atlas uses eastward x, southward z and sea level y = 0. One coordinate unit represents **10 metres**. The deterministic irregular coastline is normalized to **100 km²** of land, with its geographic center at (-170, -100). Terrain blends from the flat settlement into wooded lowlands and a connected northern ridge.

New Eden's street grid is centered at **(-150, 0)**. Streets are **48 units (480 m)** apart, four times the prototype spacing. The road network covers **528 × 528 units (5.28 × 5.28 km)**, sixteen times its original area. Homes, offices, shops, public places and existing scenic destinations are spread through these districts. Building footprints, entrances, avatars and lane widths retain their existing proportions. Paved front access paths connect enclosed buildings to their surrounding sidewalks.

Public world snapshots and agent observations include `geography`: version, centerX, centerZ, spacing, halfExtent, metersPerUnit, walkingSpeed, runningSpeed and vehicleSpeed. The backend owns this layout; roads, crossings, bus stops, camera framing, the first-person map and viewer bounds consume the same metadata.

| Movement | Units per simulated minute | Physical distance per simulated minute |
| --- | ---: | ---: |
| Walking | 8 | 80 m |
| Running | 16 | 160 m |
| Vehicles | 24 | 240 m |

Journey estimates and commute advice use these speeds and the expanded sidewalk network. Vehicle forecasts, bus arrival times, crossing checks and interpolation use the new street geometry. Signals retain their stable shared-clock phases. The simulation clock, weather, fares and resident capacity are unchanged.

## Saved-world migration

Flyway **V16** explicitly migrates existing worlds once. Fresh worlds start on the same layout. It retains building and resident identifiers, homes, employment, ownership, balances, inventory, routines, social plans, histories and credentials.

Residents inside buildings keep their position relative to that building. Road and sidewalk positions preserve their lane offset. Walking and running journeys keep their action IDs, destinations, pace and social state while rebuilding their remaining path. Residents still pause at front doors before leaving or entering. Active conversations retain local spacing.

Waiting passengers receive the corresponding new timetable; an expired unpaid wait moves to the next compatible service. Paid passengers retain their fares and itinerary. Arrival can continue into the accepted final walking leg under the same action ID. Migration does not charge another fare or silently cancel a journey. The JSON migration preserves unrecognized fields for forward compatibility.

Before updating a running persistent stack, save a PostgreSQL backup. Build the images, then update only the API and web services; the database and embeddings services remain in place.

## Access and terrain

The island has an irregular shore, sand and shallow-water bands, forests, hills and a northern range with pale summits. Regional labels identify undeveloped wilderness. These visual regions do not add agent destinations or actions.

First-person free exploration stays within the developed settlement. Existing scenic venues retain their navigation and dry-ground rules. Future wilderness destinations need authoritative boundaries, elevation-aware paths, safe arrival points and usable bridges before becoming accessible.

## Views and rendering

- Initial load and Reset camera frame the active island; **100%** fits it to the available screen.
- Island, Settlement (**200%**) and Residents (**5500%**) presets provide useful observation scales. Empty settlements retain a central observation point.
- Wheel, pinch, pan and orbit operate continuously between **55% and 18000%**. Resize preserves relative zoom. Selecting places brings them into view; following tracks the displayed resident.
- Camera moves use a 650 ms eased transition. User input interrupts it. Reduced motion applies moves immediately and holds shoreline and decorative weather animation still.
- Terrain uses one bounded mesh; woodland uses three instanced draw calls. Regional labels disappear at closer scales and building labels appear near resident scale.
- Frontend atmosphere and road materials follow the shared server clock and weather. Shadows, clouds and rain cover the expanded settlement.

## Planet strategy and later work

Phase 2 now adds the continuous world atlas, undeveloped landmasses and planetary camera framing. See [views and scale](views-and-scale.md) for the current projection, dimensions and controls. The original foundation notes below describe decisions that were still open at the end of Phase 1.

The local atlas is the initial projection. Planet radius, ocean coverage and global projection remain open decisions. Later planet views can place this island in a coarse geographic layer with other landmasses clearly undeveloped.

Other continents, connected island waterways, wilderness traversal, redesigned building forms, ambient wildlife and a visible two-moon sky remain later work.

## Verification

The backend suite covers layout migration, private-state preservation, entrance routing, paid and waiting bus journeys, walking/running speeds and full-day vehicle collision checks. The island/map/environment/immersive Playwright suites cover measured land area, dry settlement bounds, desktop/mobile camera scales, reset, following, reduced motion, first-person walking and screenshots.

Validation completed with **234 backend tests, 14 reference-runtime tests and 26 browser checks passing**, plus TypeScript and production image builds. Three owner-registration checks were skipped because they require the disposable test world. Desktop, mobile, island, settlement and resident screenshots were inspected; park planting was moved clear of benches to keep seated residents visible.

The running world migrated all 76 places and four residents, retaining all three accepted actions. A pre-migration PostgreSQL backup is saved in the ignored `artifacts` directory. Comparison of the saved snapshots found no changes to checked private identity, home, employment, finances, inventory, relationships or routine fields.

For a separate local preview, set `E35_DIST_DIR=.next-island-preview` and run `node node_modules/next/dist/bin/next dev -p 3002` from `web`. The preview connects to the local API without resetting the saved world.
