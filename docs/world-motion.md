# World motion timing

The October 5, 2026 investigation reproduced synchronized pauses in vehicles while the camera continued rendering. The server advertised a 5,000 ms tick, but consecutive live world minutes usually arrived about 5,500 ms apart. Playback finished each known path in 5,000 ms and then waited for the next snapshot. In a 45-second capture, all twelve cars paused together seven times, with a longest pause of 852 ms. Rendering continued throughout those pauses.

`WorldClock` now checks the existing database deadline every 100 ms. It claims the deadline before loading the world, and performs simulation work only when due. This avoids adding a full tick interval after transaction work while retaining the shared database gate and the rule that stopped servers do not catch up.

The 3D scene gives traffic and residents one `PlaybackTimeline`. It retains a small reserve of received movement, at most 2 seconds or 40% of the configured tick, and follows the measured publication cadence so small delays do not gradually exhaust that reserve. Movement uses received endpoints and recorded paths; it never extrapolates beyond the newest known interval. Boarding visibility and bus movement use the same cursor. Reduced motion resolves directly to the received endpoint.

The buffer introduces a small visual delay. A connection outage that exhausts all received movement still stops safely until updates return. Traffic signals, actual bus dwell times, resident work and rest, and other authoritative stops remain part of the simulation.

Diagnostics and original changed source files are saved under `artifacts/world-stalls/`. `profile-updates.cjs` captures publication cadence, and `profile-motion.cjs` samples actual Three.js object positions in an isolated browser. Use its synchronized-car metric to distinguish playback starvation from an individual vehicle legitimately stopping at a junction or bus stop.

## Verification, October 6, 2026

The final production build and TypeScript checks passed, along with 373 Java tests, 23 movement tests, and seven boarding, seating and exploration checks. The movement suite includes eleven simulated minutes of alternating early and late publications, forty slower ticks, duplicate snapshots, boarding, seating, reduced motion, missing history and reconnection. Publication-rate estimates also correct the remaining reserve gradually; an estimate alone allowed repeated jitter to exhaust the buffer over longer sessions.

After deployment, separate 60-second live captures of the overview and first-person views recorded no synchronized car pauses, no exhausted-buffer samples, no buffer-refill holds and no browser errors. Mean tick arrival intervals were about 5,037 ms. The original five resident IDs and all 168 places were preserved. The compact result is `artifacts/world-stalls/verification.json`; the complete position and clock samples are in the corresponding `final-*-motion.json` files.
