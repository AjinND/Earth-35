# Phase 2: views and scale

Implemented 4 October 2026 against the [world-building vision](world-building-vision.md). The initial island remains the default view. The **Planet**, **Island**, **Settlement** and **Residents** presets now share one continuous orthographic camera.

## World atlas

Earth-35 uses a flat equirectangular atlas for planetary context. Its reference radius is **6,000 km**, giving an equatorial circumference of approximately **37,699 km**. Ten deterministic coarse silhouettes describe fictional continents and archipelagos; their latitude-corrected areas leave approximately **73.58% ocean**. Vesper Reach, Orison, Southmere, Avel Coast, Nacre Isles, Pelagic Isles, Tamar Isles and Eastern Shoals are explicitly **undeveloped**. Their outlines do not add facilities, destinations, residents or travel actions.

The active island is anchored at **22° east, 12° north**. Its existing coastline, 100 km² planar land area, x/z coordinates and 10 metres per local coordinate unit remain intact. The atlas extends around those coordinates rather than rescaling the saved city. Equirectangular projection distorts physical distance away from the equator; global scale bars show approximate equatorial distances. Planetary geodesic travel remains future work.

The island is drawn at its actual atlas size. A screen-sized **New Eden / Inhabited island** marker keeps it identifiable at world scale and returns the camera to its island framing. Other labels are passive. Mobile devices show the large landmass labels and the inhabited-island marker, with smaller archipelago labels suppressed to prevent crowding.

## Camera behavior

- Opening the map or **Reset camera** frames the island at **100%**. Reset restores the original orientation and releases following.
- **Planet** fits the full atlas to the available screen. The outward zoom limit adapts to screen dimensions, and fractional island-relative percentages remain readable.
- Wheel, pinch and the zoom buttons operate continuously from planetary context to **18,000%** resident detail. Panning and orbiting use the same target throughout.
- **Island** returns to New Eden at 100%; **Settlement** retains its 200% framing; **Residents** retains its 5,500% framing and empty-world observation point.
- Ordinary transitions use eased logarithmic zoom over 650 ms. Zoom-weighted panning keeps New Eden in view while returning from the full atlas. Island/planet transitions preserve orientation; reset restores it.
- Reduced motion makes preset changes immediate. Resize preserves relative zoom subject to the new viewport's limits.
- Selecting places and following residents return to their local terrain. The inhabited-island marker uses the same action as the Island preset, releasing any active follow before reframing.
- On mobile, close resident views shift beside the inspector so the live avatar remains visible. This changes camera projection only, preserving the resident's position and following target.
- First-person exploration, its island map and traversal rules continue to use the existing island. Opening and closing that view preserves the overview camera.

## Detail and saved state

At coarse scales the renderer removes detailed terrain, buildings, traffic, weather geometry and resident meshes. It renders ten basic land silhouettes, a bounded graticule, ocean and the original island silhouette. Local detail returns when the camera approaches the inhabited island. Zooming over a distant landmass keeps the coarse atlas presentation and its undeveloped status.

Atlas layers use explicit drawing order without writing depth; the very large planetary clipping range therefore cannot cause ocean and land to flicker over one another. Nearby terrain retains its existing depth, lighting and shared-clock weather.

This phase changes the frontend only. It introduces no migration, backend actions, autonomous-agent decisions or saved-world edits. Resident identities, ownership, homes, employment, memories, balances and journeys remain server-owned.

## Verification

Browser coverage is in `web/tests/planet.spec.ts`, with outward-limit regressions updated in `web/tests/map.spec.ts`. Checks cover desktop/mobile full-world fit, continuous zoom, panning, pinch, both reversible limits, animated and reduced-motion returns, orientation, island selection, live resident following, empty worlds, resize, first-person round trips and account-free observations.

TypeScript and the production build passed. Across the production verification runs, all **19 distinct frontend checks** passed, including existing organic-island, environment and immersive regressions. The final camera and map run passed **10 checks** after the mobile resident framing adjustment.

The final frontend is deployed at `http://localhost:3000`. Read-only live checks confirmed desktop and mobile atlas fit, island reset and the settlement preset, with no browser page errors. Reviewed screenshots and JSON results are saved in `artifacts/views-scale-20261004/`. The API, database and embeddings containers retained their identities throughout deployment; the live world reported **168 places and four residents** at verification.

**Island landscapes** is implemented as the next phase: connected coasts, a rocky headland, forest valley, grounded river and waterfall, nine marked trails, three usable footbridges and six safe viewpoints. Terrain-aware travel shares the viewer's elevations and bridge ramps. See [Phase 3 implementation, validation and deployment](island-landscapes.md). Its preview on port 3002 uses a disposable world; the saved city on port 3000 was upgraded on 8 October 2026 to geography version 4 and 174 places, preserving all five residents. Building redesign, habitat variation and wildlife, and the two-moon sky belong to later phases.
