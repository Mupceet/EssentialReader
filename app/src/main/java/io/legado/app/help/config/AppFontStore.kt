package io.legado.app.help.config

import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.openInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid

/**
 * 应用字体私有副本仓库：「外观 → 字体」（ThemeConfigViewModel）与 eink
 * 模式「字体设置」（EInkBridge 的 GlobalSettingsImpl）共用的安装/清理
 * 逻辑。选中字体文件复制入应用私有 fonts 目录——源可能是 SAF 文档，
 * 复制后应用不再依赖源 URI 的读权限；路径写入 ThemeSettings.appFontPath
 * 由调用方完成（各自治定写入时机），本仓库只保证文件落位与副本清理。
 * 操作经单并发 IO 串行执行：后到的安装/清理等先到的完成，最终态即最后
 * 一次操作，不会交叉写坏副本目录。
 */
object AppFontStore {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val fontIo = Dispatchers.IO.limitedParallelism(1)

    /**
     * 把 [source] 字体文件复制入私有目录并返回副本。以内容摘要命名：
     * 同一字体无论导入多少次都指向同一路径，既不会留下重复副本，字体
     * 缓存也能按路径命中。失败抛原异常，由调用方决定降级（不写路径）。
     */
    suspend fun install(source: FileDoc): File = withContext(fontIo) {
        val extension = source.name.substringAfterLast('.', "ttf")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "ttf"
        val fontDir = appFontDir()
        val temp = File(fontDir, "app_font_${Uuid.random()}.tmp")
        try {
            source.openInputStream().getOrThrow().use { input ->
                FileOutputStream(temp).use(input::copyTo)
            }
            val digest = temp.inputStream().use(MD5Utils::md5Encode)
            File(fontDir, "app_font_$digest.$extension").also { target ->
                if (target.isFile) {
                    temp.delete()
                } else if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * 清理已经用不到的字体副本：只认本应用复制的 app_font 前缀，不碰
     * 主题包导入的 theme_ 资源；[keep] 为当前生效副本时保留。
     */
    suspend fun prune(keep: File? = null) = withContext(fontIo) {
        appFontDir().listFiles()?.forEach { file ->
            if (file.name.startsWith("app_font") && file != keep) file.delete()
        }
    }

    private fun appFontDir() = File(appCtx.filesDir, "fonts").apply { mkdirs() }
}
