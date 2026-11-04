/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server

import com.corrodinggames.rts.gameFramework.GameEngine
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfoReturnable
import kim.der.asm.api.annotation.InjectionPoint

/**
 * Filters the native game's direct Android Log calls in dedicated-server mode. Most native code
 * goes through GameEngine.logInternal, but a few noisy paths (such as NetworkEngine.logNetwork,
 * GameEngine's constructor, and MusicController.pause) invoke android.util.Log directly.
 */
@AsmMixin("android/util/Log")
object ServerAndroidLogMixin {
    @JvmStatic
    @AsmInject(
        method = "v(Ljava/lang/String;Ljava/lang/String;)I",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressRoutineVerboseLog(
        callback: CallbackInfoReturnable<Int>,
        tag: String?,
        message: String?,
    ) = suppress(callback, tag, message, ANDROID_LOG_VERBOSE)

    @JvmStatic
    @AsmInject(
        method = "d(Ljava/lang/String;Ljava/lang/String;)I",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressRoutineDebugLog(
        callback: CallbackInfoReturnable<Int>,
        tag: String?,
        message: String?,
    ) = suppress(callback, tag, message, ANDROID_LOG_DEBUG)

    @JvmStatic
    @AsmInject(
        method = "e(Ljava/lang/String;Ljava/lang/String;)I",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressEngineConstructorLog(
        callback: CallbackInfoReturnable<Int>,
        tag: String?,
        message: String?,
    ) = suppress(callback, tag, message, ANDROID_LOG_ERROR)

    private fun suppress(
        callback: CallbackInfoReturnable<Int>,
        tag: String?,
        message: String?,
        priority: Int,
    ) {
        if (
            ServerGameEngineLogMixin.shouldSuppressDirectAndroidLog(
                dedicatedServer = GameEngine.isDedicatedServerPlatform,
                priority = priority,
                tag = tag,
                message = message,
            )
        ) {
            callback.setReturnValue(0)
        }
    }

    private const val ANDROID_LOG_VERBOSE = 2
    private const val ANDROID_LOG_DEBUG = 3
    private const val ANDROID_LOG_ERROR = 6
}
