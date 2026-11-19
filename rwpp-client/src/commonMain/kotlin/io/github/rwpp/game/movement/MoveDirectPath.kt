package io.github.rwpp.game.movement

import kotlin.math.sqrt

/** Samples the native straight path after its tile clearance check has passed. */
internal fun directMoveNodes(from: MovePoint, goal: MovePoint): List<MovePoint>? {
    val dx = goal.x - from.x
    val dy = goal.y - from.y
    val distance = sqrt(dx * dx + dy * dy)
    val steps = (distance * 0.05f - 1f).toInt()
    if (steps <= 0) return listOf(goal)

    // Long straight paths discard the first sample. A full native buffer omits the goal.
    val skipped = if (steps >= 4) 1 else 0
    val interiorCount = steps - skipped
    if (interiorCount >= 119) return null

    val stepX = dx / distance * 20f
    val stepY = dy / distance * 20f
    return buildList(interiorCount + 1) {
        var x = from.x
        var y = from.y
        repeat(steps) { index ->
            x += stepX
            y += stepY
            if (index >= skipped) add(MovePoint(x, y))
        }
        add(goal)
    }
}
