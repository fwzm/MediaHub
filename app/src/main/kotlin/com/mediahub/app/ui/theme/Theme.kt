package com.mediahub.app.ui.theme

import androidx.compose.runtime.Composable

/**
 * 兼容入口：设计 token 与主题实现已下沉到 `:core:ui`（`com.mediahub.core.ui.theme`），
 * 以便各 feature 模块复用。保留本函数签名，现有调用点无需改动。
 */
@Composable
fun MediaHubTheme(content: @Composable () -> Unit) {
    com.mediahub.core.ui.theme.MediaHubTheme(content = content)
}
