package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.cache.DiscoveryCacheResetResult
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCacheState
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanPolicy
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanPolicyInput
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanReason
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanState
import com.sahidcode404.camx.core.camera.topology.ReconciliationRequestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepRescanCoordinatorTest {
    @Test
    fun `rescan is unavailable before first verified frame`() {
        var requested = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { false },
            reconciliationRunning = { false },
            setExplicitDeepRescan = {},
            requestReconciliation = {
                requested += 1
                ReconciliationRequestResult.STARTED
            },
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )
        assertEquals(DeepRescanRequestResult.NOT_READY, coordinator.requestDeepRescan())
        assertEquals(0, requested)
    }

    @Test
    fun `active rescan rejects a second request and clears force on completion`() {
        var forced = false
        var completion: (() -> Unit)? = null
        var requests = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { false },
            setExplicitDeepRescan = { forced = it },
            requestReconciliation = { done ->
                requests += 1
                completion = done
                ReconciliationRequestResult.STARTED
            },
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )
        assertEquals(DeepRescanRequestResult.STARTED, coordinator.requestDeepRescan())
        assertTrue(forced)
        assertEquals(DeepRescanRequestResult.ALREADY_RUNNING, coordinator.requestDeepRescan())
        assertEquals(1, requests)
        checkNotNull(completion).invoke()
        assertFalse(forced)
        assertFalse(coordinator.operationActive())
    }

    @Test
    fun `explicit rescan overrides stable empty skip and warm hot policy`() {
        val stableEmpty = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(
                cacheState = DeepAuxCacheState.COMPATIBLE,
                cachedAdvertisedTopologySignature = "same",
                currentAdvertisedTopologySignature = "same",
                previousFullReconciliationComplete = true,
                explicitDeepRescan = true,
            ),
        )
        val warmHot = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(
                cacheState = DeepAuxCacheState.COMPATIBLE,
                cachedAdvertisedTopologySignature = "same",
                currentAdvertisedTopologySignature = "same",
                cachedSuccessfulDeepIds = listOf("opaque-hidden"),
                previousFullReconciliationComplete = true,
                explicitDeepRescan = true,
            ),
        )
        listOf(stableEmpty, warmHot).forEach { decision ->
            assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
            assertEquals(DeepAuxScanReason.EXPLICIT_RESCAN, decision.reason)
        }
    }

    @Test
    fun `cache reset is separate and rejected while reconciliation is active`() = runTest {
        var resetCalls = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { true },
            setExplicitDeepRescan = {},
            requestReconciliation = { ReconciliationRequestResult.STARTED },
            resetCaches = {
                resetCalls += 1
                DiscoveryCacheResetResult.SUCCESS
            },
        )
        assertEquals(DiscoveryCacheResetResult.FAILED, coordinator.resetDiscoveryCache())
        assertEquals(0, resetCalls)
    }

    @Test
    fun `cache reset does not imply a deep rescan`() = runTest {
        var rescanRequests = 0
        var resetCalls = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { false },
            setExplicitDeepRescan = {},
            requestReconciliation = {
                rescanRequests += 1
                ReconciliationRequestResult.STARTED
            },
            resetCaches = {
                resetCalls += 1
                DiscoveryCacheResetResult.SUCCESS
            },
        )
        assertEquals(DiscoveryCacheResetResult.SUCCESS, coordinator.resetDiscoveryCache())
        assertEquals(1, resetCalls)
        assertEquals(0, rescanRequests)
    }
}
