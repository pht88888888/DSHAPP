package com.dsh.android.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairConsentWindowTest {
    private val start = 100_000L
    private val until = start + RepairConsentWindow.IDLE_TIMEOUT_MS
    private val grant = RepairConsentWindow(until, 6, "target-install:assistant-install")

    @Test fun explicitGrantLastsThirtyMinutesAndProcessRecreationDoesNotConsumeIt() {
        assertTrue(grant.copy().accepts(start, 6, grant.install))
        assertTrue(grant.copy().accepts(until - 1, 6, grant.install))
        assertFalse(grant.accepts(until, 6, grant.install))
    }

    @Test fun heartbeatRenewsOnlyLiveMatchingGrant() {
        val now = start + 25 * 60 * 1000L
        val renewed = grant.renew(now, 6, grant.install)
        assertNotNull(renewed)
        assertEquals(now + RepairConsentWindow.IDLE_TIMEOUT_MS, renewed!!.until)
        assertTrue(renewed.accepts(until, 6, grant.install))
        assertFalse(renewed.accepts(renewed.until, 6, grant.install))
        assertNull(grant.renew(until, 6, grant.install))
    }

    @Test fun rebootAndEitherAppReplacementInvalidateConsent() {
        assertFalse(grant.accepts(start, 7, grant.install))
        assertFalse(grant.accepts(start, 6, "target-new:assistant-install"))
        assertFalse(grant.accepts(start, 6, "target-install:assistant-new"))
        assertNull(grant.renew(start, 7, grant.install))
    }

    @Test fun heartbeatTimeoutIsThirtyMinutes() {
        assertEquals(30 * 60 * 1000L, RepairConsentWindow.IDLE_TIMEOUT_MS)
    }
}
