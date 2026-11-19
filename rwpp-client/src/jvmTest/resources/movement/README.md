# Reported-map fixture

`two-sides-land-cost.bin` is a derived passability snapshot for the unedited stock
`[p8]Two Sides (8p).tmx`: 120x120, 20-pixel tiles, signed bytes, column-major
`x * 120 + y`, `-1` blocked and `0` free. SHA-256:
`b5ca11d402c3357ffa26438dceb4735bfd13140414633ec6c84e3ae3ec77d16d`.

Every cell was checked against the actual `MapTile.createTile` and
`MapCosts.rebuildTerrainCost` implementation. `two-sides-native-route.csv` was
produced by the actual native `PathSolver.solveAStar` with LAND, start tile
(10,92), heading 0, goal tile (61,56), native clearance, `avoidTightSpaces=false`
`useAlternateTurnCosts=true` (mechGun can reverse), and zero dynamic unit/building costs.
The final tile center is replaced with the requested exact goal (1234,1131).
The start (210,1850) is an approximation
from the report's movement trace. Motion parameters are from stock mechGun.

Game JAR SHA-256:
`42663886549ebcbd7bdb4bac81f2aa28e8fb2bcd44d0962f01dd355684f3672f`.

This fixture checks terrain clearance, alternative routes and diagnostic reasons.
It does not reproduce runtime buildings, an edited map, or prove the position of
a hand-drawn route in a screenshot. Travel times in this test are predictions.

`two-sides-reported-0101-route.csv` is the complete native route from the subsequent
01:01 gameplay input snapshot (71 points), with the exact logged motion and goal
embedded in `MoveReportedMapTest`. That fixture reconstructs the stock team-2
CommandCenter at (210,1790), whose native footprint blocks x=9..11, y=88..90.
This reproduces the former baseline failure at step 56, then tests keeping the
necessary corridor exit and full-radius clearance of assisted targets. The log
did not capture every runtime obstacle; the next diagnostic version includes a
blocked-cell mask so future snapshots can be replayed without reconstructing it.

The `two-sides-reported-0955-*` files contain the exact 68×53 blocked-cell snapshot
and native path of the first Builder in the subsequent two-Builder comparison.
The mask is one byte per cell, 0 free or 1 blocked, in column-major order. This
case checks the difference between world-space line of sight and the game's
tile walk when a command switches to its next target near a corner.

The `two-sides-builder-21-*` files record the third Builder from the five-Builder
test. The original candidate set failed to reach the next leg. This fixture
checks that an alternative native waypoint yields a valid plan without relaxing
terrain clearance or the estimated time improvement required for assistance.

The `interlocked-builder-4-*` files contain the first individually ordered Builder's
captured route and 53×68 blocked-cell snapshot on Interlocked. The case needs more
than six planned targets and improves the estimated time by under one second and
under 3%. The mask uses one byte per cell in the same column-major order.
