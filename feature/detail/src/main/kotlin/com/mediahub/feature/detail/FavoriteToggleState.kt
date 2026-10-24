package com.mediahub.feature.detail

/**
 * 详情页收藏开关状态（task-25 / A2）。
 *
 * 语义基线（与 Android provider 层一致）：
 * - 收藏真源在服务端 UserData.IsFavorite，本机没有收藏表；
 * - 点击后以 `MediaFavoriteProvider.setFavorite` 的**回读值**渲染，不做本地取反；
 * - 失败时保持原状态，只附加可读错误——界面绝不切成相反状态。
 */
data class FavoriteToggleState(
    val itemId: String,
    val isFavorite: Boolean,
    val isUpdating: Boolean = false,
    val error: String? = null,
)

/** 纯状态迁移：可在无关 Compose 的单元测试里直接验证。 */
object FavoriteToggle {

    /** 详情加载完成后按服务器 UserData 初始化。 */
    fun fromServerState(itemId: String, isFavorite: Boolean): FavoriteToggleState =
        FavoriteToggleState(itemId = itemId, isFavorite = isFavorite)

    /** 请求在途：按钮禁用，清掉上次错误，但不预翻转图标。 */
    fun pending(state: FavoriteToggleState): FavoriteToggleState =
        state.copy(isUpdating = true, error = null)

    /** 成功：采用服务器回读的真实状态（服务器忽略写入时这里会是旧值）。 */
    fun confirmed(state: FavoriteToggleState, serverValue: Boolean): FavoriteToggleState =
        state.copy(isFavorite = serverValue, isUpdating = false, error = null)

    /** 失败：图标/文案保持原状，只透出错误。 */
    fun failed(state: FavoriteToggleState, message: String): FavoriteToggleState =
        state.copy(isUpdating = false, error = message)
}
