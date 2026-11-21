# Queued movement regression data

Each CSV records the native path as ordered world coordinates. Its paired
`mask.bin` contains one byte per tile (0 free, 1 blocked), indexed as
`x * height + y` from the snapshot origin. Both captures use 20-unit tiles.

- `two-sides-reported-0955-*` captures a Builder on Two Sides. The snapshot
  is 68 × 53 tiles at (40, 960). The tests replay the route and verify
  that the native tile walk can reject a geometrically clear shoreline leg.
- `interlocked-builder-4-*` captures a Builder in a corridor on Interlocked.
  The snapshot is 53 × 68 tiles at (80, 1040). The test retains a viable
  route with multiple turns and an estimated improvement under one second.
