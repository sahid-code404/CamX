package com.sahidcode404.camx.core.camera.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFailureTest {
    @Test
    fun storageCannotChangeRouteTrustOrTriggerFailover() {
        val policy = MediaStoreFailure("disk full").policy
        assertFalse(policy.structural)
        assertFalse(policy.sameCanonicalFailoverPermitted)
        assertTrue(policy.trustChange == TrustChange.NONE)
    }

    @Test
    fun structuralPreviewFailureMayFailOverSameCanonical() {
        val policy = UnsupportedStreamCombination.policy
        assertTrue(policy.structural)
        assertTrue(policy.sameCanonicalFailoverPermitted)
        assertTrue(policy.trustChange == TrustChange.REJECT_PREVIEW_PROFILE)
    }
}
