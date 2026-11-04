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
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/**
 * Suppresses routine native engine log lines in headless server mode. Direct Android Log calls
 * are handled by [ServerAndroidLogMixin]; server-owned logs, warning wrappers, real errors, and
 * exception stack traces remain visible.
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object ServerGameEngineLogMixin {
    private val preservedLogDepth = ThreadLocal.withInitial { 0 }

    internal fun beginPreservedLog() {
        preservedLogDepth.set(preservedLogDepth.get() + 1)
    }

    internal fun endPreservedLog() {
        val depth = preservedLogDepth.get()
        if (depth <= 1) preservedLogDepth.remove() else preservedLogDepth.set(depth - 1)
    }

    internal fun shouldSuppress(dedicatedServer: Boolean): Boolean =
        dedicatedServer && preservedLogDepth.get() == 0

    /**
     * Native classes sometimes call android.util.Log directly instead of routing through
     * GameEngine.logInternal. Keep that path quiet only for ordinary engine diagnostics while
     * allowing warning/error wrappers and all application-owned tags to pass through.
     */
    internal fun shouldSuppressDirectAndroidLog(
        dedicatedServer: Boolean,
        priority: Int,
        tag: String?,
        message: String?,
    ): Boolean {
        if (!dedicatedServer || preservedLogDepth.get() != 0 || tag != ENGINE_LOG_TAG) return false
        return when (priority) {
            ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG -> true
            ANDROID_LOG_ERROR -> isRoutineEngineError(message)
            else -> false
        }
    }

    internal fun shouldSuppressDedicatedWarning(dedicatedServer: Boolean, message: String?): Boolean {
        if (!dedicatedServer || message == null) return false
        val uncoloredMessage = message.removePrefix(ANSI_YELLOW).removeSuffix(ANSI_RESET)
        return uncoloredMessage == MENU_ACTIVITY_MISSING_WARNING
    }

    private fun isRoutineEngineError(message: String?): Boolean =
        message != null &&
                (message in ROUTINE_ENGINE_ERROR_MESSAGES || ROUTINE_ENGINE_ERROR_PREFIXES.any(message::startsWith))

    private val ROUTINE_ENGINE_ERROR_MESSAGES = setOf(
        ENGINE_CONSTRUCTOR_LOG,
        "--- ----------------- ----",
        "--- GameEngine:init() ----",
        "gameThread already null",
    )
    private val ROUTINE_ENGINE_ERROR_PREFIXES = listOf(
        "packageName:",
        "MapLayer create: ",
        "StateEngine: get(",
    )
    private const val MENU_ACTIVITY_MISSING_WARNING = "selectMenuOption: inGameActivity==null"
    private const val ANSI_YELLOW = "\u001B[33m"
    private const val ANSI_RESET = "\u001B[0m"

    private const val ENGINE_LOG_TAG = "RustedWarfare"
    private const val ENGINE_CONSTRUCTOR_LOG = "GameEngine:GameEngine()"
    private const val ANDROID_LOG_VERBOSE = 2
    private const val ANDROID_LOG_DEBUG = 3
    private const val ANDROID_LOG_ERROR = 6

    @JvmStatic
    @AsmInject(
        method = "logInternal(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressDedicatedServerEngineLog(callback: CallbackInfo, message: String) {
        if (
            shouldSuppress(GameEngine.isDedicatedServerPlatform) ||
            shouldSuppressDedicatedWarning(GameEngine.isDedicatedServerPlatform, message)
        ) {
            callback.cancel()
        }
    }

    @JvmStatic
    @AsmInject(method = "logError(Ljava/lang/String;)V", target = InjectionPoint.HEAD, require = 1)
    fun enterErrorLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = beginPreservedLog()

    @JvmStatic
    @AsmInject(method = "logError(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun exitErrorLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = endPreservedLog()

    @JvmStatic
    @AsmInject(method = "logWarning(Ljava/lang/String;)V", target = InjectionPoint.HEAD, require = 1)
    fun enterWarningLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = beginPreservedLog()

    @JvmStatic
    @AsmInject(method = "logWarning(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun exitWarningLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = endPreservedLog()

    @JvmStatic
    @AsmInject(
        method = "logException(Ljava/lang/String;Ljava/lang/Throwable;)V",
        target = InjectionPoint.HEAD,
        require = 1
    )
    fun enterExceptionLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = beginPreservedLog()

    @JvmStatic
    @AsmInject(
        method = "logException(Ljava/lang/String;Ljava/lang/Throwable;)V",
        target = InjectionPoint.TAIL,
        require = 1
    )
    fun exitExceptionLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = endPreservedLog()

    @JvmStatic
    @AsmInject(
        method = "logExceptionWithContext(Ljava/lang/String;Ljava/lang/Exception;)V",
        target = InjectionPoint.HEAD,
        require = 1
    )
    fun enterExceptionWithContextLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = beginPreservedLog()

    @JvmStatic
    @AsmInject(
        method = "logExceptionWithContext(Ljava/lang/String;Ljava/lang/Exception;)V",
        target = InjectionPoint.TAIL,
        require = 1
    )
    fun exitExceptionWithContextLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = endPreservedLog()

    @JvmStatic
    @AsmInject(method = "logWarningWithStack(Ljava/lang/String;)V", target = InjectionPoint.HEAD, require = 1)
    fun enterWarningWithStackLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = beginPreservedLog()

    @JvmStatic
    @AsmInject(method = "logWarningWithStack(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun exitWarningWithStackLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = endPreservedLog()

    @JvmStatic
    @AsmInject(method = "logWarningAndChat(Ljava/lang/String;)V", target = InjectionPoint.HEAD, require = 1)
    fun enterWarningAndChatLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = beginPreservedLog()

    @JvmStatic
    @AsmInject(method = "logWarningAndChat(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun exitWarningAndChatLog(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) = endPreservedLog()
}
