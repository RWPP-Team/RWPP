package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityDlss5NeuralTest {
    @Test
    fun onlyLiveProcessingWithRealNeuralFramesIsActive() {
        assertTrue(parseCommunityDlss5NeuralStatus("NEURAL\t3\t4096\t1\t24\t24\t2.5\t0\t")!!.active)
        assertFalse(parseCommunityDlss5NeuralStatus("NEURAL\t2\t4096\t1\t0\t0\t0\t0\t")!!.active)
        assertFalse(parseCommunityDlss5NeuralStatus("NEURAL\t5\t4096\t1\t24\t24\t0\t109\t")!!.active)
        assertNull(parseCommunityDlss5NeuralStatus("Controller started. Feature 18 available."))
    }

    @Test
    fun malformedAndInvalidCountersCannotClaimAnEffect() {
        assertNull(parseCommunityDlss5NeuralStatus("NEURAL\t3\t4096\t1\t-1\t24\t2.5\t0\t"))
        assertNull(parseCommunityDlss5NeuralStatus("NEURAL\t3\t4096\t1\t24\t24\tNaN\t0\t"))
        assertNull(parseCommunityDlss5NeuralStatus("NEURAL\t3\t4096\t1\t24\t24\t2.5\t0\tnot-base64"))
    }
}
