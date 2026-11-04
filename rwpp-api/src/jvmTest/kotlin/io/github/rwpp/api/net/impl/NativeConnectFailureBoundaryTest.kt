package io.github.rwpp.api.net.impl

import kotlinx.coroutines.CancellationException
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NativeConnectFailureBoundaryTest {
    @Test
    fun reflectiveSocketTimeoutIsUnwrappedForTheNativeConnectionRunnable() {
        val timeout = SocketTimeoutException("connect timed out")

        val actual = assertFailsWith<SocketTimeoutException> {
            callNativeConnect { throw InvocationTargetException(InvocationTargetException(timeout)) }
        }

        assertSame(timeout, actual)
    }

    @Test
    fun reflectiveCancellationRemainsCancellation() {
        val cancelled = CancellationException("join cancelled")

        val actual = assertFailsWith<CancellationException> {
            callNativeConnect { throw InvocationTargetException(cancelled) }
        }

        assertSame(cancelled, actual)
    }

    @Test
    fun unexpectedConnectorFailureIsNotSwallowed() {
        val failure = IllegalStateException("invalid connector state")

        val actual = assertFailsWith<IllegalStateException> {
            callNativeConnect { throw InvocationTargetException(failure) }
        }

        assertSame(failure, actual)
    }

    @Test
    fun successfulConnectorSocketIsReturnedUnchanged() {
        val socket = Socket()

        assertSame(socket, callNativeConnect { socket })
    }
}
