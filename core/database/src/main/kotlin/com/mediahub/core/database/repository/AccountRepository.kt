package com.mediahub.core.database.repository

import com.mediahub.core.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** 媒体源账号仓库（删除媒体源级联清理用）。 */
@Singleton
class AccountRepository @Inject constructor(
    private val db: AppDatabase,
) {
    suspend fun deleteForServer(serverId: String) {
        db.accountDao().deleteForServer(serverId)
    }
}
