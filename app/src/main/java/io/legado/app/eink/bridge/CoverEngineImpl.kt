package io.legado.app.eink.bridge

import androidx.compose.runtime.mutableStateOf
import coil3.request.ImageRequest
import io.legado.app.domain.gateway.CoverSettingsGateway
import io.legado.app.eink.contract.CoverEngine
import io.legado.app.help.coil.CoverExtras
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.launch

/**
 * 封面加载端口实现（Coil 集成 + MD3 主工程 buildCoverImageRequest 同款
 * 请求选项）。封面 data 不做形态转换，url 原样交给单例 ImageLoader 的
 * CoverInterceptor / 内置 fetcher；仅 Wi-Fi 加载固定关闭（不设
 * LoadOnlyWifi extra 即为关闭）。
 *
 * useDefaultCover（「我的」页可写）经 CoverSettingsGateway 读写，与
 * 完整模式封面设置共享同一存储键；读取由 Compose 快照状态背书，
 * 组合内（EInkBookCover）订阅变化，切换后可见封面立即重组。
 * 本文件持有 Compose runtime import，与引用遗留 Config 门面的桥接
 * 文件分离（verifyConfigArchitecture 护栏）。
 */
internal object CoverEngineImpl : CoverEngine, KoinComponent {

    private val coverSettingsGateway: CoverSettingsGateway by inject()

    private val useDefaultCoverState = mutableStateOf(false)

    /** 与宿主设置快照对齐（入口 install 时调用）：防止跨模式往返后的陈旧值。 */
    fun syncFromGateway() {
        useDefaultCoverState.value = coverSettingsGateway.currentSettings.useDefaultCover
    }

    override var useDefaultCover: Boolean
        get() = useDefaultCoverState.value
        set(value) {
            // 同步更新快照状态（组合即时可见），落盘异步承接
            useDefaultCoverState.value = value
            einkSettingsWriteScope.launch {
                coverSettingsGateway.update { it.copy(useDefaultCover = value) }
            }
        }

    override fun coverRequestOptions(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageRequest.Builder.() -> Unit = {
        if (sourceOrigin != null) {
            extras[CoverExtras.SourceOrigin] = sourceOrigin
        }
        size(widthPx, heightPx)
    }
}
