package com.mediahub.core.common.sync

/**
 * 合同违例。reason 是稳定机器码，供 UI 与测试断言；message 面向日志。
 *
 * reason 形态与 Windows SyncContractException 完全相同：
 * malformed-json、missing-field:<名字>、invalid-field:<名字>、
 * unsupported-schema-version:<版本>、unsupported-minimum-reader-version:<版本>。
 * 之所以逐字对齐，是为了两端对同一条坏输入给出同一个码，排障时不必做映射。
 */
class SourceSyncContractException(
    val reason: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        const val MALFORMED_JSON: String = "malformed-json"

        fun missingField(field: String): SourceSyncContractException =
            SourceSyncContractException("missing-field:" + field, "The required field '" + field + "' is missing or empty.")

        fun invalidField(field: String, detail: String): SourceSyncContractException =
            SourceSyncContractException("invalid-field:" + field, "The field '" + field + "' " + detail)

        fun unsupportedSchemaVersion(version: Int): SourceSyncContractException =
            SourceSyncContractException(
                "unsupported-schema-version:" + version,
                "The sync document uses schema version " + version + "; this build reads version " +
                    SourceSyncEnvelope.CURRENT_SCHEMA_VERSION + ".",
            )

        fun unsupportedMinimumReaderVersion(version: Int): SourceSyncContractException =
            SourceSyncContractException(
                "unsupported-minimum-reader-version:" + version,
                "The sender requires a reader of at least version " + version + "; this build implements version " +
                    SourceSyncEnvelope.CURRENT_SCHEMA_VERSION + ".",
            )
    }
}
