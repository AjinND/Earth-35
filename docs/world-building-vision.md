# Earth-35: long-term world-building direction

Recorded: 3 October 2026.

Status: product direction and future roadmap. This document describes the world we are working toward; it does not claim that these capabilities are implemented or require an immediate planet-scale rebuild. The current MVP remains a small, persistent city for autonomous, owner-controlled agents.

Future geography, scenery, buildings, camera controls and environmental effects should move toward this vision. The [MVP concept](../MVP-Concept.txt) defines the starting product; the [protocol guide](protocol.md) describes current behavior.

## 1. A planet with one inhabited island

Earth-35 should feel like a real planet. Most of its surface is ocean, with continents, archipelagos and smaller islands distributed across the world. The present square city area is an early prototype of a settlement within that geography.

Current civilization exists on one small island with approximately **100 km² of land area**. New Eden is the active settlement on that island. Its developed districts occupy part of the island; beaches, rivers, forests, hills and other wilderness can occupy the rest. The island should have an irregular coastline and recognizable terrain rather than inheriting the prototype's square outline.

Other continents and islands belong in the planetary plan. They can initially appear as coarse, undeveloped landmasses in the global view. Detailed terrain, settlements, services and agent access are future expansion work. Their appearance on the map must not imply that populated cities or usable facilities already exist there.

| Geographic level | Intended role |
| --- | --- |
| Planet | Ocean-dominated world, broad landmass layout and room for future expansion. |
| Active island | Approximately 100 km², containing New Eden and surrounding natural areas. |
| Settlement | Current homes, workplaces, shops, social venues and paved infrastructure. |
| Wilderness | Undeveloped forests, mountain ranges, valleys, lakes and coastal terrain. |
| Other landmasses | Planned continents and islands, initially represented at coarse detail. |

The island's area is a geographic target, not a conversion already applied to existing coordinates. Planet radius, total ocean percentage, landmass names and exact island outline remain design decisions. Define physical units and travel distances before implementing the larger scale so that distances, agent speeds and journey times remain coherent.

## 2. Map views and zoom

The default view should frame the **active island**, where current agents and civilization are located. Opening the app or resetting the map camera should return to that useful view.

Users should be able to move continuously between these scales:

| Scale | What should be visible |
| --- | --- |
| Planet | Oceans, the active island, other continents and undeveloped islands. |
| Island | Coastline, the developed settlement, major terrain, scenic landmarks and regional paths. |
| District | Streets, buildings, bridges, vegetation, public places and resident locations. |
| Resident | Individual agent avatars, their movements and nearby surroundings clearly enough to observe activity. |

Maintain position and orientation during transitions so people understand where the island sits in the larger world. Labels and markers should change with scale: continental context at world scale, landmarks at island scale, buildings and individual residents at close range. Dense labels should not obscure the scenery.

Zoom limits must permit both a complete world view and a clear view of individual avatars. Panning, reset, following a resident, mouse controls and touch controls must remain usable at every scale. A planetary view could use a globe or a geographic atlas; the final projection and transition style are still open decisions.

Large maps should load and render appropriate detail for the visible region. Distant landmasses need coarse silhouettes, while nearby terrain and residents need detail. The first island should not require every continent to be fully rendered or simulated.

## 3. Natural terrain and connected landscapes

Scenery should be recognizable from its geometry, vegetation, water and elevation. A destination name is not a substitute for its physical form.

| Feature | Direction for future additions |
| --- | --- |
| Rivers and riverbeds | Larger, continuous waterways with visible flow, changing width, bends, banks and believable riverbeds. Connect them to surrounding drainage, lakes or the sea. |
| Beaches and coastline | Sandy beaches, rocky shores, coves and coastal cliffs. Shoreline shape and materials should vary around the island. |
| Mountain ranges | Broad, connected ranges with foothills, ridgelines and multiple peaks. Higher elevations should include snow-capped summits. |
| Forests | Substantial untouched woodland outside civilized areas, with denser vegetation and clear wilderness character. |
| Lakes | Inland bodies of water with natural shorelines and relationships to the surrounding terrain. |
| Waterfalls | Flowing water dropping across actual elevation changes, with downstream water and local spray or mist. |
| Cliffs and valleys | Visible relief, escarpments, depressions and valley floors. The island should have meaningful elevation variation. |
| Natural paths and trails | Walking and trekking routes connecting scenic areas, with earth, gravel or rock surfaces distinct from paved city roads. |
| Bridges | Crossings where rivers or valleys interrupt otherwise walkable routes. Their structure, surface and navigation must agree. |

Plan geography as connected landscapes. A mountain valley can carry a river through forest toward a lake and the coast; a trail can connect viewpoints and cross a stream on a bridge. Natural features should support that continuity instead of appearing only as isolated decorations on city-sized plots.

Scenic outings remain **optional agent choices**, like visiting a park or café. Rivers, mountains, beaches, forests and trails should offer possibilities for free time without adding compulsory excursions or automatic trekking routines.

When terrain becomes usable, rendering and authoritative world rules must agree. Agents and first-person viewers need valid paths, safe arrival points and usable bridge surfaces. Routes should account for water, cliffs and elevation. A bridge that exists only visually, or an invisible shortcut across a lake, does not meet the intended behavior.

## 4. Homes, apartments and workplaces

Existing homes, apartments and offices need a proper visual and structural redesign. Their appearance should communicate their purpose and fit the scale of residents and surrounding streets.

Future redesigns should establish:

- Distinct forms for houses, apartment buildings and offices, with varied massing, rooflines and facade proportions.
- Believable floor heights, windows, doors, entrances and street frontage.
- Clear relationships among building footprints, sidewalks, gardens, courtyards and access paths.
- Materials and colors appropriate to the island's settlement, with controlled variation between districts.
- Day and night behavior such as window illumination, readable entrances and exterior lighting.
- Consistent visible structures and collision boundaries, including accessible entrances and private interiors.

Visual improvements must preserve residents' homes, venue identities, employment, ownership/privacy rules and saved activity. Scale changes should use explicit migrations rather than moving or replacing occupied buildings incidentally.

## 5. Vegetation and ambient wildlife

Vegetation should vary by environment rather than using the same tree model everywhere.

| Zone | Intended character |
| --- | --- |
| City | Street trees, tended gardens, lawns, flowers and planted public spaces. |
| Forest | Dense and varied trees, shrubs, understory and woodland ground cover. |
| Mountain | Sparser growth at altitude, conifers where appropriate, exposed rock and alpine vegetation. |
| Coast | Coastal grasses, shrubs, beach vegetation and plants adapted to exposed shorelines. |
| Grassland and valley | Open grass, meadow flowers and scattered trees, with variation along waterways. |

Consider birds and small animals as ambient life in natural areas. Their movement should suit the habitat and help the world feel alive. Keep ambient wildlife distinct from autonomous resident agents and their economy or decision protocol. Full animal simulation is a separate possible expansion, not a prerequisite for ambient wildlife.

Use bounded density and detail appropriate to camera distance. Respect reduced-motion settings for nonessential animation.

## 6. Sky, two moons and environmental change

Earth-35 has **two moons**. Future sky work must consistently represent both, with distinct visible bodies and positions. Moon sizes, orbital periods and phases remain to be designed.

The sky and landscape should include:

- A visible sun and both moons, with movement and visibility tied to the shared world clock.
- Visible stars, particularly at night.
- Visible sunrise and sunset transitions, including changing sky colors and sunlight direction.
- Golden-hour lighting around sunset and sunrise, with cooler tones and softer illumination at night.
- Clear, overcast and rainy conditions that change sky appearance, water, ground surfaces and visibility.
- Mist around mountains and waterfalls, and fog near rivers or lakes when appropriate.
- Consideration of seasonal changes over time, including vegetation, snow coverage or lighting where the eventual climate model supports them.

All observers should see a coherent environment for the same world time and weather. Preserve the existing shared clock and server-owned weather facts. Refreshing or reconnecting should not reroll geography, moon placement or weather.

Atmospheric effects should integrate with terrain: mist gathers around relief and water; snow relates to elevation and eventual climate; rain changes the appearance of rivers, trails and buildings. Effects must preserve readable controls, visible residents and usable navigation. Decorative motion should respect accessibility settings.

## 7. A phased roadmap

These phases express dependencies and direction, not delivery dates or a commitment to implement everything at once. Work can progress in small, reviewable additions.

| Phase | Main outcome | Completion evidence |
| --- | --- | --- |
| 1. Geographic foundation | Place New Eden on a planned ~100 km² island within an ocean-dominated planet. Establish island boundaries, physical units, undeveloped landmass outlines and a scale strategy. | An island/planet specification with coherent dimensions and a plan to preserve existing state and identifiers. |
| 2. Views and scale | Default island framing, outward world zoom and close resident viewing, with suitable detail at each scale. | Desktop and mobile demonstrations of all zoom levels, reset, panning and following an agent. |
| 3. Island landscapes | Connected coasts, riverbeds, mountains, forests, lakes, waterfalls, cliffs and valleys, with trails and bridges. | Close and distant visual review plus valid traversal, arrival and crossing checks. |
| 4. Built environment | Redesign homes, apartments and offices to fit the terrain, streets and resident scale. | Reviewed building forms, working entrances and preserved residential/workplace behavior. |
| 5. Living environment | Vegetation by zone and considered ambient wildlife. | Habitat variation, bounded rendering cost and reduced-motion verification. |
| 6. Sky and atmosphere | Visible sun, two moons, stars, transitions and terrain-aware weather; later seasonal variation. | Consistent shared-time observations by day/night and weather, including both moons. |
| 7. Expansion | Develop selected additional islands or continents when needed. | New regions integrated without disturbing existing geography, residents or histories. |

Phases 3–6 can overlap after the geographic and scale foundations are established. Existing local scenic work should remain compatible with the eventual island, but does not establish the final planetary geography by itself.

Phase 3 was completed on 8 October 2026. The [connected island landscapes](island-landscapes.md) link sandy coves, a rocky coastal headland, forested ridges and valley, a lake, grounded riverbeds and waterfall through nine marked trails and three usable bridges. The river mouth opens through the beach and blends into the sea without a closing bank or height step. Six dry viewpoints support optional outings, and travel costs follow the same terrain and bridge ramps as the viewer. Validation began with the separate preview, followed by the [saved-city deployment](../artifacts/phase3-main-deployment-20261008/deployment-verification.json) on the same day. The main city now uses geography version 4 with 174 places and its five existing residents preserved. Building redesign (Phase 4), habitat variation and wildlife (Phase 5), and the two-moon sky and broader atmosphere (Phase 6) remain separate roadmap work.

## 8. Guidance for implementation and review

Before implementing a substantial expansion, decide which region it affects, how it relates to the island's physical scale, and what becomes actually accessible to agents.

Use these checks for future work:

1. Does the addition fit the ocean-dominated planet and single active island?
2. Is it clearly developed settlement, accessible natural terrain, or an undeveloped future region?
3. Can its natural or built form be recognized without relying on a label?
4. Are connected water, landforms, trails and bridges spatially coherent?
5. Does the rendered terrain agree with routes, entrances, collision, arrival points and available actions?
6. Does camera behavior work from planetary context to individual residents, including on mobile?
7. Do time, weather and the two-moon sky remain consistent across observers?
8. Are saved residents, homes, jobs, memories, history and current journeys preserved?
9. Is rendering and simulation cost bounded to the detail and activity required in each region?
10. Have relevant close-up and overview screenshots been inspected, alongside behavioral checks?

Future terrain work should include visual review at island, district and resident scales. Location lists, labels and successful travel commands alone do not establish that the landscape looks right.

## 9. Decisions to resolve as the work approaches

The vision above is established. These details can be decided when the corresponding phase begins:

- The active island's name, exact coastline, settlement placement and distribution of wilderness.
- Planet size, ocean proportion, landmass outlines and geographic coordinate system.
- Globe versus atlas presentation and transitions between global and local cameras.
- Physical distance units, elevation representation and the migration from prototype coordinates.
- Island climate, vegetation palette, snow line and whether to introduce a seasonal cycle.
- The two moons' appearance, orbital behavior and phases.
- Which future landmasses should be developed first and how travel to them becomes available.

These open details should not weaken the fixed direction: a mostly watery planet, one currently inhabited island of roughly 100 km², natural and varied landscapes, optional exploration, clear avatars at close range, and a visible two-moon sky.
