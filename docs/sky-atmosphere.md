# Phase 6: sky and atmosphere

Implemented on 11 October 2026 for the isolated preview at <http://localhost:3002>, and subsequently deployed with the frontend loading improvements to the saved main city at <http://localhost:3000>. This is a frontend change: the server still owns the world minute and weather. No database migration, resident changes, geography changes or protocol changes are required.

## Observing the sky

Enter the world and use **Look at sky** beside the first-person zoom controls. It points toward the sun while it is above the horizon, or toward the visible moons at night. Drag to look elsewhere. The camera only changes direction when the control is pressed; subsequent clock updates do not take over the camera. Buildings and terrain can naturally obscure a body close to the horizon.

The map keeps its useful downward view and receives the same sunlight, moonlight, weather and surface changes. Celestial discs belong to the first-person sky. The planet atlas keeps its existing geographic presentation.

| Body | Appearance | Time behavior |
| --- | --- | --- |
| Sun | Warm disc, soft surrounding glow | Rises in the east, crosses the sky and sets in the west during the 1,440-minute world day. |
| Selene | Larger, cool silver moon | 23-world-day phase cycle, apparent angular radius about 1.83°. |
| Ember | Smaller, warm amber moon | 9-world-day phase cycle, apparent angular radius about 1.15°. |

The two inclined lunar tracks use world minute zero as their epoch. Their apparent positions, phases and horizon visibility are deterministic, including across midnight, refresh, reconnect and independent observers. Illuminated surfaces face the sun, and moonlight follows each visible body's direction and illuminated fraction. Moon discs fade with the same solar daylight factor as moonlight, disappearing once full daylight is reached (including 08:00) and returning gradually at dusk. Clouds still obscure the night sky without making daytime moons reappear. Both moons are above the horizon at the epoch, but they do not remain visible together throughout every day or night. These are designed fictional tracks and apparent sizes, not a physical n-body simulation or a change to world geography.

Fixed stars appear in clear night skies and fade with daylight and cloud. A vertical sky gradient, directional warm glow and warmer sunlight make sunrise and sunset visible. Clear, overcast and rainy skies use different cloud coverage. Cloud structure uses the received world minute; the browser never chooses weather or advances celestial time on its own.

## Terrain and weather

Local rain follows the first-person viewer throughout the island. Drops stop above terrain, lake and river surfaces, bridge decks and building roof envelopes. The rain field is rebuilt only when the viewer enters another 24-unit cell or relevant building geometry changes. The dashboard map shows a separate layer of rain streaks sized in screen pixels so rainfall remains visible at island and settlement zoom. Both views use the received world weather and bounded clock. Clear and overcast weather hide the streaks; the planet atlas keeps its geographic presentation.

Rain darkens roads, ground, trails and redesigned building exteriors. Their roughness decreases, while ocean, lake and river surfaces become rougher and more muted. Clearing weather restores the dry materials. Navigation and collision remain unchanged.

Soft, stationary mist patches are anchored above mountain relief, Cloudmere Lake, the downstream river and Silverstream Falls. Mountain mist increases under cloud, low water mist strengthens around dawn, and waterfall mist persists. Existing waterfall spray and terrain geometry remain in place. Distant first-person mist fades out, and nearby patches fade before they can obscure the viewer's immediate path. Atmospheric fog retains readable map and first-person distances.

## Motion and rendering limits

The sky takes one background draw with no downloaded textures or models. The phase adds one mist draw and one active rain draw, with at most 48 mist patches, 420 rain streaks on desktop and 200 on narrow screens. Only the map or first-person rain layer draws in its respective view. Mist has no decorative movement. Rain animation advances at most one received tick and then stops if updates stop. Reduced motion uses a fixed rain pose in both views, including across subsequent clock updates, and freezes decorative cloud drift. Shared-time celestial position and weather facts still update.

Seasonal vegetation, snowline changes and a climate calendar remain future work. Existing elevation-based snow is preserved.

## Validation

TypeScript checking, the production build and **15 distinct frontend checks** pass across staged runs. The final image passes all five phase checks and the existing wildlife and unmocked-preview checks; eight environment, camera, first-person and building-entry regressions also passed. The live preview check issued no mutating requests and reported no page errors.

Phase checks are in `web/tests/sky-atmosphere.spec.ts`: orbital continuity and phase coverage, independent observations, refresh and map return, rain bounds, terrain and roof clearance, irregular water boundaries, mist placement, desktop/mobile views, weather transitions and live reduced-motion changes. A screenshot pixel check verifies that stars actually render. All 14 phase screenshots were visually reviewed. The [verification record](../artifacts/phase6/verification.json) records the completed runs, final image and reviewed views. Backend tests were not rerun because this phase changes no backend or protocol source.

The dashboard-rain fix was deployed on 11 October 2026. Both desktop/mobile map-rain tests and all five existing sky checks passed, along with TypeScript and the production build. The map tests verify rendered streak pixels, weather transitions, zoom, reduced motion, first-person entry and map return. Live deployment checks preserved five residents and 174 places; because the live weather had cleared by verification, a browser-only rainy snapshot also verified the production map layer without changing saved weather. Records and screenshots are in `artifacts/map-rain/`.

To start this preview from the repository root:

```powershell
docker build -t earth35-phase6-web-preview web
docker compose -f compose.landscapes-preview.yaml -f artifacts/phase6/compose.override.yaml up -d
```

To run its tests from `web/`:

```powershell
$env:E35_WEB_URL="http://127.0.0.1:3002"
$env:PLAYWRIGHT_CHROMIUM="C:\Program Files\Google\Chrome\Application\chrome.exe"
npm.cmd run test:e2e -- tests/sky-atmosphere.spec.ts --workers=1
```
