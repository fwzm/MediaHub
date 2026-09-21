package com.mediahub.provider.webdav

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联合候选 A2-5 对齐点：备份恢复/身份变更经 [CredentialGenerationInvalidator]
 * 接口推进 WebDAV 凭据世代，恢复前捕获的旧 handle 不得再执行条件清理。
 */
class WebDavCredentialGenerationInvalidatorTest {

    @Test
    fun `invalidator advances generation so pre-restore handles cannot clear`() = runBlocking {
        val stack = WebDavTestStack("http://127.0.0.1:1/dav/")
        stack.storePassword("kept-password")
        val preRestoreHandle = stack.credentialStore.readPassword(stack.server.id)!!

        val invalidator = WebDavCredentialGenerationInvalidator(stack.credentialCoordinator)
        invalidator.invalidateCredentialsGeneration(stack.server.id)

        val cleared = stack.credentialStore.clearIfStill(stack.server.id, preRestoreHandle)
        assertFalse("恢复前句柄不得执行条件清理", cleared)
        assertEquals("密码保持不变", "kept-password", stack.credentialStore.readPasswordValue(stack.server.id))

        // 新身份（恢复后重新认证）正常增代并可用
        stack.storePassword("post-restore-password")
        val freshHandle = stack.credentialStore.readPassword(stack.server.id)!!
        assertTrue("同代清理语义保持", stack.credentialStore.clearIfStill(stack.server.id, freshHandle))
        assertEquals("清理后为空", null, stack.credentialStore.readPasswordValue(stack.server.id))
    }
}
