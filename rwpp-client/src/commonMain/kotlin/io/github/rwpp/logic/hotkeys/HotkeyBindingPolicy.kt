/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.hotkeys

/** Normalize aliases and whitespace without depending on the native input controller. */
internal fun normalizedHotkeyBindings(value: String): String =
    value.uppercase().split(',').joinToString(",") { binding ->
        binding.trim().split('+').joinToString("+") { part ->
            when (part.trim()) {
                "BACKSPACE" -> "DEL"
                "DELETE" -> "FORWARD_DEL"
                "ESC" -> "ESCAPE"
                else -> part.trim()
            }
        }
    }

/** Pure validation; the native adapter supplies the accepted key names. */
internal fun validHotkeyBindings(value: String, nativeKeyNames: Set<String>): Boolean =
    normalizedHotkeyBindings(value).split(',').let { bindings ->
        bindings.size <= 2 && bindings.all { binding ->
            val parts = binding.trim().uppercase().split('+')
            binding.isBlank() || (parts.dropLast(1).distinct().size == parts.size - 1 &&
                    parts.dropLast(1).all { it in listOf("CTRL", "SHIFT", "ALT") } &&
                    parts.last() in nativeKeyNames)
        }
    }

/** Keep at most two distinct, valid alternatives for the editable binding slots. */
internal fun hotkeyBindingSlots(labels: List<String>, isValid: (String) -> Boolean): List<String> =
    labels.filter { it.isNotBlank() && isValid(it) }.distinct().take(2)
        .let { listOf(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) }
