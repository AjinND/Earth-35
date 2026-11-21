# Phase 5: living environment

Implemented and verified on 10 October 2026 for geography version 4. The isolated production preview is at <http://localhost:3002>. This phase adds habitat vegetation and ambient wildlife to both the overview and first-person views. It requires no database migration and leaves geography, building coordinates, resident state, navigation and the agent protocol unchanged. Earlier geography versions retain their existing island vegetation; expanded outdoor destinations share the revised leisure planting.

## Habitats

| Habitat | Planting |
| --- | --- |
| City | Small street oaks and birches, flowers and grass beside homes and workplaces; existing tended parks and gardens remain. |
| Western forest | Denser mixed oak, birch and pine canopy, with ferns and shrubs underneath. |
| Mountain | Sparse pines and smaller alpine conifers, low grass and alpine flowers; exposed high summits remain unplanted. |
| Coast | Low shrubs and tufts of coastal grass, leaving the shoreline and beach approaches open. |
| Meadow and valley | Scattered broadleaf trees, open grass and muted pink, cream and yellow flowers. |
| Riverbank | Willows, birches, reeds and ferns on dry banks outside the channel. |

Plant placement is deterministic. Full crown and patch widths reserve space around roads, sidewalks, marked trails, bridge decks and ramps, water, cliffs, buildings, entrances, outdoor destinations and resident signs. Sloping ground constrains placement, ground cover follows its local slope, and trees remain upright. New construction and signs remove overlapping decorative planting on the next spatial snapshot. Ordinary clock or resident updates reuse the existing planting plan.

Vegetation is scenery rather than a new authoritative obstacle system. Existing routes and free exploration remain available, with the established server/viewer traversal rules. This phase does not move properties or reroute residents.

### Leisure planting refinement

Western Woodland now has **36 smaller trees: 12 oaks, 12 birches and 12 pines**, with varied sizes, rotations and foliage tones. A central lawn remains open, canopies have at least four map units of separation, and paths and benches have dedicated clear margins. Other expanded outdoor destinations use the same sparse planting rules with palettes suited to their setting; beaches and plazas remain free of these trees. The surrounding forest retains its denser habitat planting.

Previously the destination's oval trees and the island's habitat trees overlapped. The island layer now reserves the entire outdoor destination, and `outdoor-planting.ts` supplies its mixed groves. Both first-person and overview views use the same layout, shared tree geometry and bench placements. Destination ground, paths and furniture positions are unchanged.

## Ambient wildlife

Coastal gulls, inland songbirds, meadow butterflies and small rabbits use bounded local paths around natural destinations and vegetation. Ground animals pause between short hops, and birds and butterflies have wing motion. Their paths are checked against the same clear-space reservations as planting. They are decorative animals: they have no resident identity, model, account, jobs, inventory or actions, and do not appear in resident lists or the economy.

Activity uses the shared world minute and weather. These diurnal animals are inactive at night and during rain; butterflies have a shorter daylight window. Animation interpolates at most one received clock tick, then stops if the world stops updating. Reduced motion shows fixed poses, including across incoming clock updates, and can be changed while the view is open. Plants have no decorative motion.

## Rendering limits

The plan allows at most 7,200 plant clusters, 2,600 canopy plants and 36 animals. Plants are merged into 240-unit spatial tiles, with a shared vertex-color material and separate canopy and understory meshes. Frustum culling removes tiles outside the view. Island and planet scales omit understory and wildlife; local views add flowers, ferns and animals. First-person detail also has a distance limit. The planet view unmounts the island scenery.

After the leisure planting refinement, the verification fixture contains **5,310 habitat plant clusters**, including **1,461 canopy plants**, across **29 tiles**, and **17 animals**. Its habitat vegetation uses **56 potential draw calls** and **1,020,636 vertices** before view culling. Those counts exclude destination groves and the rest of the scene. Destination groves are separately merged into spatial tiles, with at most 36 trees per destination. Geometry and material resources are disposed when replaced or unmounted. No new external textures, models, dependencies or services are needed.

## Verification

Production build and TypeScript checking pass. All **16 distinct frontend checks passed across staged runs**: eight Phase 5 checks and eight existing environment, map, first-person and indoor-occupant regressions. Placement, construction/sign clearance, bounded geometry and wildlife path/time checks are covered by `web/tests/living-environment.spec.ts`. The tests verify live preference changes, frozen animal poses across clock updates, rain/night inactivity, desktop/mobile detail changes and valid arrivals. The unmocked preview check verifies actual public data, planet-to-island return and forest selection without mutating requests or page errors.

The visual review covers 17 Phase 5 screenshots: desktop/mobile island, forest, coast, mountain shore, estuary, city park and first-person views, plus a static wildlife view. Screenshots are under `web/test-results-phase5-final/`; integration evidence is under `web/test-results-phase5-regression/` and `web/test-results-phase5-immersive/`. The original immersive regression allowed only five seconds for its second scene to initialize on mobile; it now uses the same thirty-second readiness allowance as its first desktop entry. The full walk, map, follow, exit and mobile pinch flow then passed. The initial development-server cold-load timeout was superseded by successful production-image validation.

The leisure follow-up passed **10 targeted checks**, TypeScript checking and a production build. New checks cover mixed species, full-ground exclusion of the second vegetation layer, crown spacing, path bends, seating and clearings. Desktop/mobile screenshots and a first-person visit were reviewed, along with Central Park and existing habitat/wildlife regressions. Evidence is under `web/test-results-woodland-unit/` and `web/test-results-woodland-visual/`.

The updated preview runs image `sha256:9daab79f34116ee57dc7e8d81214de96a16c13c13e1b28d1891a70550968d753`, with a healthy isolated API, geography version 4, 174 places and no resident accounts. Backend source and protocol are unchanged; the backend suite was not rerun for this frontend phase. See [the initial Phase 5 verification record](../artifacts/phase5/verification.json) and [the leisure follow-up record](../artifacts/phase5/woodland-refinement.json) for staged runs and source hashes.

Run the phase checks from `web/`:

```powershell
$env:E35_WEB_URL="http://127.0.0.1:3002"
$env:PLAYWRIGHT_CHROMIUM="C:\Program Files\Google\Chrome\Application\chrome.exe"
npm.cmd run test:e2e -- tests/living-environment.spec.ts --workers=1
```

Phase 5 was deployed to the saved main city on **11 October 2026**, together with the resident awareness and intentions changes. The existing five residents and 174 places were preserved. Live desktop/mobile world and first-person checks passed; see [the main deployment record](../artifacts/agent-awareness-deployment-20261010/deployment-verification.json). [Phase 6: sun, two moons and atmosphere](sky-atmosphere.md) is now implemented in the separate preview.
