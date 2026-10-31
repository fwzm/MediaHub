package com.mediahub.core.common.sync

/**
 * 规划器给出的 8 类动作（与 Windows SourceSyncAction 逐项同名同序）。
 *
 * 注意：这不是"已经执行了什么"，而是"建议做什么"；真正落库由调用方在用户确认后进行。
 */
enum class SourceSyncAction {
    /** 本地没有，远端有，且地址可用：建议新建。 */
    CREATE,

    /** 同一个 syncSourceId，远端修订号更高：建议更新本地已知字段。 */
    UPDATE,

    /** 双方一致：什么都不用做。 */
    NO_CHANGE,

    /** 冲突：修订号相同但内容不同、本地重复 id、或身份不确定，必须由用户逐条裁决。 */
    CONFLICT,

    /** 远端修订号更低：忽略，因为"更旧"不等于"有分歧"。 */
    IGNORE_STALE,

    /** 远端墓碑确认删除：建议删除本地条目（需用户确认）。 */
    DELETE,

    /** 本地是 pre-W6 来源（没有 syncSourceId），但类型/地址/账号三者一致：建议认领。 */
    ADOPT,

    /** 协议里认识但本端没有这类来源：保存原文，暂不支持。 */
    UNSUPPORTED,
}
