/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

/** Relation is based on native Team alliance state; it is only a display choice. */
enum class UnitAppearanceRelation { SELF, ALLY, ENEMY, NEUTRAL }

/** A paint source is the unit's own color, another player's position, or a fixed ARGB color. */
sealed interface UnitAppearanceColorSource {
    data object Position : UnitAppearanceColorSource
    data class PlayerPosition(val zeroBasedPosition: Int) : UnitAppearanceColorSource
    data class Fixed(val argb: Int) : UnitAppearanceColorSource
}

/** Parses `position`, player positions 1..10, or #RRGGBB/#AARRGGBB. Invalid values return null. */
fun parseUnitAppearanceColorSource(value: String): UnitAppearanceColorSource? {
    val token = value.trim()
    if (token.equals("position", ignoreCase = true)) return UnitAppearanceColorSource.Position
    if (token.isNotEmpty() && token.all { it in '0'..'9' }) {
        val slot = token.toIntOrNull() ?: return null
        return if (slot in 1..10) UnitAppearanceColorSource.PlayerPosition(slot - 1) else null
    }
    if (!token.startsWith('#')) return null
    val hex = token.drop(1)
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val parsed = when (hex.length) {
        6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> hex.toLongOrNull(16)?.toInt()
        else -> null
    } ?: return null
    return UnitAppearanceColorSource.Fixed(parsed)
}

/** Returns ten parsed sources ordered by owner teamId, falling back to each owner's current color. */
fun parseUnitAppearancePlayerColorMap(value: String): List<UnitAppearanceColorSource> =
    List(10) { index ->
        parseUnitAppearanceColorSource(value.split(',').getOrNull(index).orEmpty())
            ?: UnitAppearanceColorSource.Position
    }

fun resolveUnitAppearanceColorSource(
    source: UnitAppearanceColorSource,
    ownerNativeColor: Int,
    nativeColorForPlayerPosition: (Int) -> Int,
): Int = when (source) {
    UnitAppearanceColorSource.Position -> ownerNativeColor
    is UnitAppearanceColorSource.PlayerPosition -> nativeColorForPlayerPosition(source.zeroBasedPosition)
    is UnitAppearanceColorSource.Fixed -> source.argb
}

/** Selects custom paint only when explicitly enabled; otherwise native sprite colors remain untouched. */
fun resolveUnitAppearancePaintColor(
    enabled: Boolean,
    triColorEnabled: Boolean,
    relation: UnitAppearanceRelation,
    ownerPosition: Int,
    ownerNativeColor: Int,
    playerColorMap: List<UnitAppearanceColorSource>,
    selfSource: UnitAppearanceColorSource,
    enemySource: UnitAppearanceColorSource,
    allySource: UnitAppearanceColorSource,
    nativeColorForPlayerPosition: (Int) -> Int,
): Int? {
    if (!enabled) return null
    if (ownerPosition !in 0..9 || relation == UnitAppearanceRelation.NEUTRAL) return ownerNativeColor
    val source = if (triColorEnabled) when (relation) {
        UnitAppearanceRelation.SELF -> selfSource
        UnitAppearanceRelation.ENEMY -> enemySource
        UnitAppearanceRelation.ALLY -> allySource
        UnitAppearanceRelation.NEUTRAL -> null
    } else null
    val effective = source ?: playerColorMap.getOrNull(ownerPosition) ?: UnitAppearanceColorSource.Position
    return resolveUnitAppearanceColorSource(effective, ownerNativeColor, nativeColorForPlayerPosition)
}

fun resolveUnitAppearanceRingColor(
    enabled: Boolean,
    relation: UnitAppearanceRelation,
    selfColor: Int,
    enemyColor: Int,
    allyColor: Int,
    neutralColor: Int,
): Int? {
    if (!enabled) return null
    return when (relation) {
        UnitAppearanceRelation.SELF -> selfColor
        UnitAppearanceRelation.ENEMY -> enemyColor
        UnitAppearanceRelation.ALLY -> allyColor
        UnitAppearanceRelation.NEUTRAL -> neutralColor
    }
}
