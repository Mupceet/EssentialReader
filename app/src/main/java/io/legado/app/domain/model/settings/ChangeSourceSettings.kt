package io.legado.app.domain.model.settings

import io.legado.app.domain.usecase.ChangeSourceMigrationOptions

data class ChangeSourceSettings(
    val searchScope: String = "",
    val checkAuthor: Boolean = false,
    val loadInfo: Boolean = false,
    val loadToc: Boolean = false,
    val loadWordCount: Boolean = false,
    val migrateChapters: Boolean = true,
    val migrateReadingProgress: Boolean = true,
    val migrateGroup: Boolean = true,
    val migrateCover: Boolean = true,
    val migrateCategory: Boolean = true,
    val migrateRemark: Boolean = true,
    val migrateReadConfig: Boolean = true,
    // 默认删：正文缓存文件名是「序号-标题MD5」（与源无关），换源搬移会沿用
    // 旧源正文（含坏章），换源动机多为旧源内容有问题
    val deleteDownloadedChapters: Boolean = true,
) {
    fun migrationOptions() = ChangeSourceMigrationOptions(
        migrateChapters = migrateChapters,
        migrateReadingProgress = migrateReadingProgress,
        migrateGroup = migrateGroup,
        migrateCover = migrateCover,
        migrateCategory = migrateCategory,
        migrateRemark = migrateRemark,
        migrateReadConfig = migrateReadConfig,
        deleteDownloadedChapters = deleteDownloadedChapters,
    )
}
