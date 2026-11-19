# Single-unit move assistance

Enable **Settings → Gameplay → Single-unit move assistance (experimental, ground units)**.
The default is off. **Log move assistance diagnostics** also works with assistance off for comparison runs.

Each plan handles one locally owned, body-steered LAND unit. Ordinary MOVE orders, Shift-queued MOVE
orders and the approach to a BUILD order are supported. The client observes up to 16 units ordered
individually. Group orders, attack-move, patrol, scripted waypoints and unsupported motion retain native
behavior. A new replacement order or Stop cancels assistance for that unit.

## Planning and execution

The player's command first follows the ordinary native command path. After it has applied, the client
reads the current native route and copies terrain and observable building footprints. For a route truncated
at the native 120-node buffer, a detached solver tail is used only when it matches the live prefix.
Planning runs in the background; expired results and changed queues are discarded.

A bounded visibility graph and shortcuts through native path nodes propose at most 24 MOVE destinations,
including the segment's final destination. Route costs include heading, throttle, turn acceleration,
braking, reversing, native node spacing and the native handoff between queued waypoints. Every queued
destination must actually be reached; candidates cannot assume early replacement or skip an inconvenient
corner. All legs are checked again against the copied terrain and native tile traversal.

Candidates must have a lower predicted travel time than the current native route, including the initial
command delay. There is no minimum saving of one second or three percent. Among verified candidates
within 0.5 seconds of the fastest prediction at normal game speed, fewer waypoints are preferred.
This is a prediction over bounded candidates, not a guarantee of globally fastest or measured travel time.

An accepted plan is submitted as one ordered batch of ordinary native commands. The first MOVE replaces
the active segment, subsequent MOVE orders are queued, and the player's original suffix follows them.
For a BUILD, the MOVE prefix ends safely within native build range and is followed by the original BUILD
with its unit type and tech level intact. The building centre is never substituted for a reachable approach.
Later segments can be planned when they become active. Shift appends made during execution are retained.

The native queue has 29 slots. The planner uses only the space left after retaining all player orders,
up to 24 assisted targets per segment; a full queue keeps its native behavior. Ordinary MOVE/BUILD queues
are copied and checked through native serialization. If reissuing an order would lose script flags,
timing, range or private serialized state, the entire rewrite is refused.

No unit position, throttle, path buffer or live waypoint array is directly written. Commands use the original
outgoing queue and serialization. Input-side path prefetch is skipped for the batch so a later queued order
cannot overtake the replacement while a prefetched path is pending; execution still uses native pathfinding.
New player input must be observed applying before another plan is submitted. Stop does not delete commands
already received from the server. No new network packet is introduced.

## Limits and verification

Snapshots cover at most 16,384 terrain cells and select up to 96 corners per clearance. Planning checks run
at most every 80 ms per observed unit, with a serialized background planning budget. Candidate corridors
compare wider clearance with a small terrain margin of up to two pixels around the unit centre. The native
engine still controls live movement costs, physical collision and interactions with moving units. Invalid,
stale, unreachable or non-improving plans fall back to ordinary movement.

JVM tests exercise the actual game JAR's steering and full Builder update, real native command serialization,
queue capacity and rollback, terrain snapshots and class transformations. They cover continuous queued
routes, turning-dependent costs, Shift suffix preservation and BUILD approach endpoints. Static fixtures
do not replace full-game motion or mixed-client testing.

For game validation, save one builder at a fixed position and heading near a mountain or building. Compare
assistance off/on for: one MOVE; several Shift MOVE goals; a distant BUILD followed by another order.
Check the complete sequence, not just the first turn. During a separate run, append a Shift goal, issue Stop,
and issue a new replacement goal: no old destination should resume. Then repeat with an unmodified peer
and check trajectories and desynchronization messages. Mixed-client compatibility remains unverified until
that real-match check passes.

The game log uses `Move assist:` summaries, `Move assist sample:` traces and `Move assist planning:` snapshots.
Queued execution records `mode=queued`, `queue-submitted`, `queue-applied` and `native-queue-advance`; native
advancement does not send another replacement MOVE. Order records include the unit, token and game clock.
`nativeSteps` and `plannedSteps` are predictions. Samples record travelled distance, low-speed duration and
reverse distance. Planning input includes motion, the complete baseline, region and copied blocker mask
(`x * height + y`, four cells per hex digit, low bit first), allowing offline replay without guessing terrain.