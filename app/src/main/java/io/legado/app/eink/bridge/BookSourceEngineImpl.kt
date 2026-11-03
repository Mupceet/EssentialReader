package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.eink.contract.BookSourceEngine
import io.legado.app.eink.contract.BookSourceEntryUiModel
import io.legado.app.help.CacheManager
import io.legado.app.eink.SourceLoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 书源管理端口实现。
 *
 * 读路径直连 `book_sources_part` 数据库视图的 Room flow（与完整模式
 * 书源管理列表同源），写操作复用 [BookSourceRepository] 的事务化路径
 * （置顶/置底的边界偏移与序号溢出全表重排语义同完整模式）；删除走
 * [io.legado.app.help.source.SourceHelp]（级联清理源变量缓存与选中源
 * 配置，不可恢复）。
 *
 * 登录状态按 [CacheManager] `userInfo_<key>` 的存在性判定（与
 * BaseSource.putLoginInfo 同键）：登录信息不是 Room 表、无失效通知，
 * 由模块在进入页面/ON_RESUME 时拉取快照合并。
 */
internal object BookSourceEngineImpl : BookSourceEngine, KoinComponent {

    private val bookSourceRepository: BookSourceRepository by inject()

    override fun sourceEntries(): Flow<List<BookSourceEntryUiModel>> =
        appDb.bookSourceDao.flowAll().map { parts -> parts.map { it.toUiModel() } }

    private fun BookSourcePart.toUiModel(): BookSourceEntryUiModel = BookSourceEntryUiModel(
        url = bookSourceUrl,
        name = bookSourceName,
        group = bookSourceGroup,
        enabled = enabled,
        hasLoginUrl = hasLoginUrl,
        customOrder = customOrder,
        weight = weight,
        lastUpdateTime = lastUpdateTime,
        respondTime = respondTime,
    )

    override suspend fun loginStates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        // 只查配置了登录地址的源（BookSourcePart 视图内联 hasLoginUrl 条件）
        appDb.bookSourceDao.allPart
            .filter { it.hasLoginUrl }
            .associate { it.bookSourceUrl to (CacheManager.get("userInfo_${it.bookSourceUrl}") != null) }
    }

    override suspend fun setEnabled(urls: List<String>, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            // dao 批量启停为按 url 逐条 UPDATE 的事务包装，最小 Part 只需 url
            appDb.bookSourceDao.enable(enabled, urls.map { BookSourcePart(bookSourceUrl = it) })
        }

    override suspend fun setExploreEnabled(urls: List<String>, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            appDb.bookSourceDao.enableExplore(
                enabled,
                urls.map { BookSourcePart(bookSourceUrl = it) },
            )
        }

    override suspend fun moveToEdge(urls: List<String>, toTop: Boolean) =
        withContext(Dispatchers.IO) {
            // 置顶/置底要按现有 customOrder 保持块内相对顺序，先取真实 Part
            val selected = urls.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it) }
            if (selected.isNotEmpty()) {
                bookSourceRepository.moveToEdge(selected, toTop)
            }
        }

    override suspend fun deleteSources(urls: List<String>) = withContext(Dispatchers.IO) {
        bookSourceRepository.deleteSourceParts(urls.map { BookSourcePart(bookSourceUrl = it) })
    }

    override fun openSourceLogin(sourceKey: String): Boolean {
        // 登录界面（表单/WebView/验证码）由完整模式同款 Compose 界面承载，
        // 经薄宿主 SourceLoginActivity 覆盖在 E-Ink 界面之上——MainActivity
        // 深链会被 eInkMode 分流弹回，不可作宿主
        val activity = EInkBridge.hostActivity() ?: return false
        activity.startActivity(SourceLoginActivity.createIntent(activity, sourceKey))
        return true
    }
}
