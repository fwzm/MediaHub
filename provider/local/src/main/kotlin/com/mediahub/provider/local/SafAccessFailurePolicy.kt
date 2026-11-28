package com.mediahub.provider.local

import java.io.FileNotFoundException

/**
 * SAF 数据访问失败归类（缺陷 87）。
 *
 * 不同厂商的 DocumentsProvider 在授权失效/文档被移除/URI 不合法时抛出的异常并不统一：
 * 常见的是 SecurityException，但也会出现 FileNotFoundException、IllegalArgumentException、
 * UnsupportedOperationException、IllegalStateException。旧实现只捕获 SecurityException，
 * 其余异常会直接冒泡成"数据访问失败"甚至崩溃。
 *
 * 这里把它们统一归为"目录授权失效，需要用户重新选择"，从而给出可读提示并允许重试。
 */
internal object SafAccessFailurePolicy {

    fun isAccessFailure(error: Throwable): Boolean = when (error) {
        is SecurityException,
        is FileNotFoundException,
        is IllegalArgumentException,
        is UnsupportedOperationException,
        is IllegalStateException,
        -> true

        else -> false
    }
}
