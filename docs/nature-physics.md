# Atmosphere and nature physics

The 18 October 2026 environmental physics pass connects clouds, precipitation, vegetation, rivers, lakes, coastal water and waterfall effects to a shared wind field and presentation clock. These changes were deployed to the saved city on 18 October 2026; see the [deployment record](../artifacts/all-changes-20261018/deployment/deployment.json).

`windAt` supplies a continuous prevailing direction, coherent gusts and a logarithmic height profile. Clear, overcast and rainy conditions use reference wind speeds of 2, 4 and 7 metres per second at ten metres above ground, with bounded gust variation. Scene materials share the reference field; plant flexibility increases from fixed roots through stems and crowns. The field is inexpensive and deterministic from world time and position.

The overview and first-person canvases share their clock and accumulated cloud transport. Each received world minute provides five presentation seconds, independently of the configured publication interval. Duplicate snapshots cannot restart movement, cadence changes preserve progress, and a stopped connection holds after one interval. Clouds advect by integrating wind velocity; switching views does not start another cloud or wave clock. Solar and lunar directions continue to use the exact received world minute.

Local rain falls at seeded terminal speeds of 5.5–8.5 metres per second, with downwind drift and streaks aligned to velocity. Collision sampling checks roofs, bridges, water, analytic walking surfaces and the triangles actually rendered on hillsides. A spatial roof index retains exact heights while avoiding a full building scan per drop. The particle budgets remain 420 on desktop and 200 on mobile. Terrain mist drifts downwind, rises slightly around falls and fades completely before recycling.

Trees, shrubs and ground cover bend on the GPU with separate flexibility for stems and foliage. Roots stay attached to the existing terrain, trunks bend less than crowns, and maximum displacement remains within half a map unit. The color and shadow passes share the same deformation. Geometry placement, walking clearances and plant populations are preserved.

Ocean, river, lake and plunge-pool ripples align to the wind. Wave frequencies use the deep-water gravity relation, scaled to the island's ten-metre map units. Existing shore exposure controls sheltered coves; the narrow surf ribbon retains its land and estuary clearances. Waterfall droplets retain ballistic vertical motion while bounded air drag carries spray and vapor downwind, constrained in front of the impact cliff. Waves and plants receive bounded phases calculated in JavaScript double precision so large world ages do not degrade GPU motion into visible steps.

Reduced motion fixes decorative animation poses across both views. Weather appearance can still change when a new weather state arrives. This is a bounded visual environmental simulation; pressure systems, humidity, climate, erosion, water-volume conservation and vegetation growth remain future work. No server action rules or saved-world data are changed by this pass.

Numerical checks cover wind continuity, cadence and shared transport, terminal fall, terrain and roof clipping, fixed roots, deformation bounds, particle drag, phase wrapping and long-running GPU precision. Offline desktop/mobile browser tests check weather transitions, shared consumers, shader compilation, reduced motion, scene changes and screenshots. Existing terrain, planting, coast and sky checks cover preserved geometry and access.

On 18 October 2026, 67 focused checks passed: 45 numerical tests, 11 geometry/grounding regressions and 11 browser checks. TypeScript and the fresh Turbopack production build passed. Desktop/mobile woodland, sky and coastal screenshots were inspected. The exact rain-floor sampler reduced the measured 840-query median from roughly 7–9 ms to 2–3 ms per frame on the review machine. Surf fixtures now publish a new simulation minute when testing continued wave motion, matching the shared clock's bounded behavior.

Run the focused numerical suite from `web/`:

```powershell
npm.cmd run typecheck
npm.cmd run test:e2e -- tests/wind.spec.ts tests/environment-motion.spec.ts tests/environment-shader-phases.spec.ts tests/precipitation-physics.spec.ts tests/precipitation-floor.spec.ts tests/vegetation-wind.spec.ts tests/waterfall-wind.spec.ts --workers=1
```

Set `E35_WEB_URL` to an isolated frontend preview for `tests/environment-physics.spec.ts` and the existing browser regressions. They intercept world snapshots and WebSockets. `PLAYWRIGHT_CHROMIUM` can select an installed Chromium executable.
