# Phase 3: island landscapes

Completed 8 October 2026 against the [world-building vision](world-building-vision.md). The implementation connects the highlands, western ridge and forest valley, coves, headland and Silverstream estuary. Production and visual validation was first recorded with the separate preview. The changes were then deployed to the saved city on the same day, with its residents and prior state preserved.

## Connected water and terrain

Cloudmere Lake is centered at **(-132, -398)**, approximately 520 × 360 metres across before its irregular shoreline variation. Its level water surface lies at 72 map units. Silverstream enters from the northern ridge, crosses the submerged lake basin, leaves through an elevated rocky outlet and drops over a granite lip before continuing through its valley, three footbridges and sea outlet. River heights never increase downstream.

The entire mountain-to-sea water ribbon has a continuous rock bed, banks and shore support. Dense local mesh samples follow narrow channels, the inlet cascade and both sides of the waterfall drop. The irregular granite face, attached outcrops and submerged plunge basin physically connect the falling sheet to the hillside and receiving pool. Terrain triangles cannot bridge over the drop and conceal the waterfall. Cloudmere also has a detailed stony shore that meets the lake basin.

The inlet and waterfall base produce airborne spray, rising mist and spreading foam. Falling water streaks and river currents communicate flow. Reduced motion freezes these decorative effects. The island terrain, trail surfaces, bridges, cameras and resident avatars use the same elevations.

The existing connected northern mountain range retains its foothills, exposed rock and snowy summits. Three bounded relief features add the western ridge, a depression through Fern Valley and the Basalt headland. Broad western woodland continues around these features. Trees stay clear of water, exposed cliffs, trails, roads and existing buildings; instanced meshes and limited local refinement keep rendering cost bounded.

Four coastal zones give the shoreline distinct forms and materials:

| Coastal zone | Physical character |
| --- | --- |
| Moonwash Cove | Sheltered western sandy beach below the forest, with a gentle shore profile. |
| Basalt Headland | Raised rocky promontory, exposed coastal cliffs and attached boulders. |
| Northern Rocky Shore | Rock bands and formations along the northern coast. |
| Silverstream Estuary | Sandy receiving coast beside the river's existing sea outlet. |

The source coastline and its **100 km²** land area remain unchanged. The developed settlement keeps its existing ground, streets, buildings and identifiers.

## Trails, crossings and optional exploration

Two highland trails and seven further marked trails form one connected network. The Western Ridge Trail links Cloudpeak, Pinecrest Hill Station, Pine Ridge Trekking Trail, Fern Valley and the western woodland. The Moonwash Forest Path and Basalt Headland Trail continue to the western coast. The Silverstream Valley Trail links the falls to the new valley footbridge; the eastern bank trail, southern bridge link and estuary approach reach the sea outlet. Earth, gravel and rock surfaces distinguish these routes from paved settlement streets.

Three footbridges cross the actual winding channel. Their finite deck footprints, constant heights, supports, rails and four-unit approach ramps agree with traversal. Navigation permits crossings on the usable decks and rejects adjacent water shortcuts. Every trail has dry endpoints and connects to the rest of the network.

New construction and sign installations reserve the full marked path and a half-unit verge. Their complete footprints must avoid trail segments and protected cliffs before money or work is reserved. Nearby clear wilderness remains available for resident projects.

Six outdoor destinations support optional visits, social activity and rest:

| Place | Coordinate | Setting |
| --- | --- | --- |
| Cloudmere Lakeside | (-172, -399) | Dry western shore overlooking the lake. |
| Silverstream Falls Lookout | (-65, -350) | Eastern valley view over the rocky fall and plunge pool. |
| Moonwash Cove | (-705, -120) | Sandy beach below the western forest. |
| Basalt Headland Lookout | (-710, 10) | Dry ledge overlooking the rocky coast. |
| Fern Valley Rest | (-550, -245) | Open resting spot in the forested valley. |
| Silverstream Estuary | (204, 134) | Dry sandy shore beside the sea outlet. |

These destinations stay open all day, add no jobs and initiate no automatic excursions. Residents choose whether to visit. Agents can also request safe island coordinates through the existing `point:x,z` protocol. The overview and first-person map show the connected landscapes, trails and destinations.

Authoritative navigation and the viewer block Cloudmere's water, the waterfall cliff corridor and the exposed Basalt cliff edge. The viewer checks the complete movement segment. Accepted paths go around these obstacles and use bridge decks for river crossings. Destination selection places the viewer on each actual dry arrival point.

## Elevation-aware travel

Geography version 4 uses the same terrain and bridge-ramp profile for route search, movement, travel estimates and first-person progress. Uphill movement takes more effort; gentle descents can be easier, while steep descents require slower progress. Running up slopes uses more energy. Routes can retain a longer, easier hill detour rather than simplifying it back into a costly straight climb.

The server measures slopes along half-unit surface samples. Directional costs distinguish ascent from descent, and bounded caches reuse heights and route segments. The viewer uses the matching effort model. Ordinary flat settlement walking and running retain their existing speeds and energy costs. Historical geography versions retain their prior horizontal travel behavior until upgrade.

This phase adds walking and running over dry terrain. It does not add climbing, swimming or compulsory trekking.

## Saved-world upgrade

Fresh worlds have **geography version 4**, **174 places** and **33 social destinations**. Flyway **V24** retains its historical scope: the two original highland viewpoints and geography version 3. **V25** adds the four further outdoor destinations, advances to version 4 and prepares the connected terrain and crossing rules.

The upgrades preserve unknown JSON fields, private resident state, the clock, existing buildings, homes, employment, balances, inventories, memories, history and unrelated records. They patch only routes actually changed by new obstacles; unchanged accepted actions retain even omitted fields and original numeric representations. Affected walks keep their receipt, destination, pace and other pending metadata. Reapplying either upgrade has no effect.

Before changing state, the migration checks properties, resident objects, resident positions, accepted destinations and reserved object installations. Full property and sign footprints cannot overlap the new cliff zone or marked trail corridors; existing outdoor destinations and their trail markers remain intentional arrival points. Newly obstructed accepted walks are rerouted before any saved fields are changed; an unreachable or conflicting destination aborts the upgrade. The migration never moves a resident or occupied property to make room for scenery.

The matching specification is stored in `backend/src/main/resources/city/island-landscapes-v1.json` and `web/lib/island-landscapes.json`. Despite the historical resource filename, its current specification version is 2. Shared tests compare the specification and authoritative dry-land/elevation surveys.

## Separate local preview

The production preview runs at **http://localhost:3002** with API **http://localhost:8082** and its own disposable database. It contains no copied residents, owner accounts or provider credentials. Open **Enter the world → World map** and visit the six landscape destinations; overview presets provide island, settlement and resident-scale inspection.

The normal saved city on port 3000 now runs **geography version 4, 174 places and five residents**. Its V24/V25 rollout was completed after the separate preview verification, with a custom-format database backup and exact comparisons before normal simulation activity resumed. The original 168 places, all five residents, clock and eleven unrelated database tables were preserved. See the [main-city deployment record](../artifacts/phase3-main-deployment-20261008/deployment-verification.json).

From the project root:

```powershell
docker build -t earth35-landscapes-api-preview backend
docker build -t earth35-landscapes-web-preview web
docker compose -f compose.landscapes-preview.yaml up -d
```

Stop the preview with `docker compose -f compose.landscapes-preview.yaml down`. Its database is temporary; this command targets the separate preview project.

## Verification and phase status

Phase 3 is complete. The initial completion run on **8 October 2026** passed **429 backend tests** and **20 frontend checks**. The river-mouth correction and water-layer review were then verified with **27 distinct frontend checks**: seventeen geometry/parity checks and ten desktop/mobile browser flows, completed in staged runs. Both mouth inspection cases and both new rendered dry-ground checks passed against the final production image. One original waterfall animation check timed out during concurrent headless rendering and passed on its isolated rerun. TypeScript and the updated frontend production build passed; the backend and its prior passing validation remain unchanged. Validation covers authoritative server/viewer boundary and elevation parity, complete trail traversals, all six resident outings and returns home, actual bridge crossings, directional slope timing and energy costs, saved-state preservation, migration idempotence and atomic overlap rejection.

Rendered mesh raycasts check support beneath the full river, the lake and receiving basin, waterfall visibility, trail contact with terrain, and continuous bridge decks and ramps. Coastal bands share inset corners and retain exact shoreline vertices, closing the small gaps that previously exposed green ground through the sand. A regression checks actual shoreline coverage and the former visible seam. Desktop and mobile review covers island, settlement and resident-scale views, first-person coastal/highland arrivals, reduced motion and existing map/exploration behavior. Mist and spray checks verify airborne effects at both impact points.

The river mouth opens through the flat land, beach and foam meshes instead of passing beneath them. Its widening offshore outlet extends beyond the coastal shelf, lowers its banks beneath the receiving water, and blends into the sea at one common surface height. This rendering correction preserves authoritative inland heights and navigation. Dedicated regressions inspect the actual land, shore, foam, terrain, coastal, walking-path, bed and water meshes together, including the former blocked mouth and the transition beyond the outlet.

The final sea and shelf materials separate their coplanar surfaces using fixed depth-buffer units. This keeps dry settlement ground and roads visible at island zoom. Actual desktop/mobile canvas pixels are checked at 228 dry-ground probes and 26 offshore controls per viewport; both final images show zero cyan-water coverage over the dry-ground probes. Retained regression images fail those same thresholds, with their earlier lighting difference documented.

The authoritative surveys contain **17,337 land-boundary samples**, **4,797 elevation points** and **10 directional travel-cost segments**, alongside the original 3,780 highland samples. The running preview reports geography version 4, 174 places, 33 social destinations, a healthy API and HTTP 200 from the frontend. All 25 Flyway migrations passed in its disposable database. Earlier unmocked browser visits confirm the real cove, waterfall and estuary views. A fresh desktop/mobile overview and public API check passed on the final water-material image.

The original highland evidence remains under `artifacts/island-landscapes/`; the waterfall grounding review is in its `waterfall-grounding/` directory. The [completion record](../artifacts/phase3/verification.json) links the backend reports, [initial 41 reviewed screenshots and frontend results](../artifacts/phase3/visual-review/verification.json), the [river-mouth regressions and desktop/mobile before-and-after review](../artifacts/river-mouth/verification.json), [rendered water/ground regression checks](../artifacts/river-mouth/water-rendering/verification.json), [live preview checks](../artifacts/phase3/live-preview/verification.json) and [preview deployment evidence](../artifacts/phase3/deployment.json). Those preview records describe the earlier validation before the saved-city rollout.

The [main-city deployment](../artifacts/phase3-main-deployment-20261008/deployment-verification.json) rebuilt the backend and frontend, passed all 429 backend tests and TypeScript checking, and applied both migrations to the existing `earth35_world-data` volume. Exact state checks passed while ticking and hosted decisions were paused; the normal five-second tick interval, hosted encryption key and embedding endpoint were restored afterward. Live desktop/mobile overviews and actual first-person visits to the cove, estuary and falls passed without runtime or rendering errors. The existing embeddings image was retained after a dependency-download timeout; all three of its source files were confirmed identical to the current project files. The backup and prior application image tags remain available for recovery.

The remaining roadmap work belongs to later phases: building redesign in Phase 4, habitat-specific vegetation and ambient wildlife in Phase 5, the two-moon sky and broader atmosphere in Phase 6, and selected geographic expansion in Phase 7.
