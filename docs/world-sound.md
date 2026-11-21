# World sound: first pass

Implemented and deployed on 14 October 2026 to the saved main city at <http://localhost:3000>. The first implementation adds an optional, quiet environmental soundscape to first-person exploration. The current pass uses procedural browser audio; these are prototype sounds for tuning, not recorded nature assets. It uses the server's received world minute and weather and the actual viewer camera. The deployment replaces only the frontend; the running API and database containers are preserved, with no resident, geography or database changes. The saved world was backed up and the previous frontend image retained for rollback. See the [deployment record](../artifacts/world-sound-20261014/deployment/deployment.json).

Enter the world and press **Sound off** to start listening. The volume slider remembers your preferred level locally; each new exploration session starts with sound off. Muting suspends the audio context, changing browser tabs suspends it until you return, and leaving exploration closes it. The dashboard remains silent. Unsupported browsers can still explore.

| Layer | Behavior |
| --- | --- |
| Wind | Quiet outdoor bed; stronger on the coast, in the mountains and under cloud or rain. |
| Rain | Follows received rainy weather, with a smooth transition. |
| Thunder | [Lightning and thunder](world-storm.md), deployed on 15 October: quiet rolling rumbles follow distant flashes during rain, with direction and delay based on viewer position. |
| Surf | Fades with distance to the rendered coastline; direction responds to camera rotation. |
| Stream / lake | Local running-water bed near the rendered river, with softer water at the lake shore. |
| Waterfall | Broader rushing-water sound around Silverstream Falls. |
| Bird / gull calls | Sparse synthesized calls weighted by habitat and daytime activity; quiet during rain. These are ambience, not voices attached to individual animal models. |
| Night insects | A subtle pulsed insect bed in suitable habitats after dark; quiet during rain. |

The spatial mix samples at most four times a second. Static shoreline and river segment indices avoid scanning all vegetation or rebuilding wildlife plans. One audio context owns reusable noise buffers, bounded layers, smooth gain and pan transitions, and output compression. Call timing and gusts use real listening time; simulation time determines environmental conditions, without accelerating calls as the world clock advances. Sound does not predict new weather while disconnected.

This outdoor pass does not simulate indoor acoustics, terrain/building obstruction or individual footsteps, doors, residents, vehicles and construction. The next sound pass should prioritize viewer footsteps by surface, followed by nearby buses and place activity. Replace the prototype nature layers with original or appropriately licensed seamless recordings after listening and approving the spatial mix; retain the same location, weather and lifecycle controls. Add source and license records for each imported asset.

## Verification

All seven distinct checks passed, together with TypeScript and the production build. Desktop and mobile screenshots were reviewed. The final playback lifecycle check was rerun successfully after fixing unexpected device suspension and automatic recovery. The preview's read-only API check confirmed a healthy server, 30 residents and 174 places.

The deployment's Docker production build and TypeScript checks pass, and all seven sound checks pass against the deployed frontend. Main-city desktop and mobile playback checks also passed with the live WebSocket, nonzero generated audio samples, mute/resume, volume controls and context cleanup; no page errors or mutating browser requests occurred. Before/after preservation digests confirm 30 resident identities, 174 places, two owners, agent credentials and provider connections are preserved. The API remains healthy and the world clock advances. Deployed test evidence is under `web/test-results-world-sound-deployed/`; live screenshots, backup and rollback details are in the deployment record above.

The model checks in `web/tests/soundscape.spec.ts` cover locality, coast/river termination, still lake behavior, stereo rotation, weather, day/night, habitat differences, legacy maps and finite bounds. Browser checks in `web/tests/world-sound.spec.ts` cover opt-in startup, actual generated audio samples, live weather, map travel, volume persistence, tab suspension, device interruption recovery, mute/resume, context cleanup, mobile control bounds and unsupported audio. Browser evidence is under `web/test-results-world-sound-browser/` and `web/test-results-world-sound-final/`.

From `web/`, against the deployed frontend (browser tests supply an isolated world fixture):

```powershell
$env:E35_WEB_URL="http://localhost:3000"
$env:PLAYWRIGHT_CHROMIUM="C:\Program Files\Google\Chrome\Application\chrome.exe"
npm.cmd run test:e2e -- tests/soundscape.spec.ts tests/world-sound.spec.ts --workers=1
```

The lifecycle follows the browser's [resume](https://developer.mozilla.org/en-US/docs/Web/API/AudioContext/resume) and [suspend](https://developer.mozilla.org/en-US/docs/Web/API/AudioContext/suspend) APIs; gain transitions use [AudioParam scheduling](https://developer.mozilla.org/en-US/docs/Web/API/AudioParam/setTargetAtTime).
