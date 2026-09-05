package com.mediahub.feature.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 异步连接测试结果与地址草稿版本绑定（Phase 1I）：地址变化后旧结果不得覆盖。 */
class AddressTestResultPolicyTest {

    @Test
    fun `same version applies result`() {
        assertTrue(AddressTestResultPolicy.shouldApply(currentVersion = 5, requestVersion = 5))
    }

    @Test
    fun `address changed during request discards stale result`() {
        assertFalse(AddressTestResultPolicy.shouldApply(currentVersion = 6, requestVersion = 5))
    }

    @Test
    fun `version only ever moves forward`() {
        assertFalse(AddressTestResultPolicy.shouldApply(currentVersion = 4, requestVersion = 5))
    }
}
