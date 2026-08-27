package io.legado.app.eink.engine

import android.graphics.drawable.Drawable
import com.bumptech.glide.RequestBuilder

/**
 * 封面加载端口：把宿主 Glide 集成（默认封面设置、OkHttp 选项、
 * url 形态判定）挡在模块外。
 */
interface CoverEngine {
    /** 用户是否开启「使用默认封面」（true 时全部显示文字占位封面）。 */
    val useDefaultCover: Boolean

    /**
     * 封面地址 → Glide model：http/data 地址原样、content 解析为 Uri、
     * 其余按本地文件路径处理（与 View 版 ImageLoader.load 一致）。
     */
    fun resolveCoverModel(url: String): Any

    /**
     * 封面请求构建：仅 Wi-Fi 加载固定关闭 + 透传书源 origin 自定义头 +
     * 目标尺寸 override。
     */
    fun coverRequestTransform(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): (RequestBuilder<Drawable>) -> RequestBuilder<Drawable>
}
