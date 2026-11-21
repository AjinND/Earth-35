# Walking physics

The 17 October 2026 physics pass improves first-person exploration. Walking and sprinting accelerate smoothly, brake to rest within 200 ms, and travel at the same speed diagonally. The integrator uses analytic velocity updates with substeps no longer than 1/120 second. A stalled frame contributes at most 100 ms of movement.

Movement sweeps the full attempted path against building envelopes, installed signs, oriented benches, fountain basins, garden beds, tables, pergola posts and expanded destinations' planted tree trunks. A blocked move advances to contact and slides along the free axis. Blocked velocity is cleared, so pushing against a wall cannot store momentum. Furniture comes from the same placement functions as the rendered world. Terrain checks include both endpoints and sample at most every quarter map unit; river banks, protected cliffs and finite footbridges retain their existing access rules.

Geography version 4 retains directed slope effort, including slower climbing and braking on steep descents. Terrain effort is charged along the actual accepted movement, including wall slides. Eye height continues to clear both walking and rendered terrain surfaces.

Opening the map, losing focus or hiding the tab clears walking input and velocity. Teleports reset momentum and select checked standing positions. Returning from follow mode, or receiving newly occupied ground, relocates a free viewer to nearby clear ground when necessary. Pointer release and cancellation brake mobile movement as keyboard release does.

This pass changes the local exploration camera. Authoritative resident travel, traffic and world actions retain their existing server rules. Ambient vegetation outside expanded destination planting is decorative. These changes were deployed to the saved city on 18 October 2026; see the [deployment record](../artifacts/all-changes-20261018/deployment/deployment.json).

Validation lives in `web/tests/walking-physics.spec.ts`, `web/tests/world-collision.spec.ts` and `web/tests/walking-controls.spec.ts`. The numerical tests cover frame rates, diagonal speed, braking, swept contact, sliding, slopes and stalled frames. Collision regressions cover buildings, object status, furniture, planted trunks, river crossings, bridges and every saved address's arrival. Browser tests use intercepted world snapshots and sockets, so they require no running backend or saved-world changes.

On 17 October, 30 focused checks passed: 21 numerical/collision tests, seven existing world regressions, and two desktop/mobile control tests. TypeScript and the production build passed. A fresh Turbopack output directory resolved an earlier CSS-worker failure; the webpack production build also passed. Desktop and mobile screenshots were inspected. Collision layouts reuse a bounded spatial cache across snapshots, so resident and clock updates do not regenerate outdoor planting.

Run the focused checks from `web/` with:

```powershell
npm.cmd run typecheck
npm.cmd run test:e2e -- tests/walking-physics.spec.ts tests/world-collision.spec.ts tests/walking-controls.spec.ts --workers=1
```

Set `E35_WEB_URL` to the frontend preview URL for browser tests. `PLAYWRIGHT_CHROMIUM` can select an existing Chromium executable.
