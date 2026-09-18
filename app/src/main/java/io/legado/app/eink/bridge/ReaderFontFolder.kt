package io.legado.app.eink.bridge

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.PreferKey
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.File

/**
 * 字体文件夹端口支撑：宿主 SAF 字体文件夹（PreferKey.fontFolder，与完整
 * 模式 FontSelectDialog 同键）的枚举与持久化。
 *
 * 枚举口径同完整模式：配置文件夹内 .ttf/.otf + 应用外部私有目录 font/
 * 下的本地字体合并；path 取 [FileDoc.toString()]（文件路径或 content uri
 * 字符串，与 ReadBookConfig.textFont 的取值域一致）。
 */
internal object ReaderFontFolder {

    private val fontRegex = Regex("(?i).*\\.[ot]tf")

    /** 阻塞式文件夹枚举（契约约定调用方在 IO 上下文调用）。 */
    fun listFonts(): List<ReaderFontOption> {
        val fonts = LinkedHashMap<String, ReaderFontOption>()
        // 配置的字体文件夹（SAF 树 uri 或文件路径）
        appCtx.getPrefString(PreferKey.fontFolder)?.takeIf { it.isNotEmpty() }?.let { folder ->
            runCatching { folderDoc(folder) }.getOrNull()
                ?.list { it.name.matches(fontRegex) }
                ?.forEach { fonts[it.toString()] = ReaderFontOption(it.name, it.toString()) }
        }
        // 应用外部私有目录 font/ 下的本地字体（无需权限，恒可枚举）
        runCatching {
            File(FileUtils.getPath(appCtx.externalFiles, "font")).listFileDocs {
                it.name.matches(fontRegex)
            }
        }.getOrNull()?.forEach {
            fonts.putIfAbsent(it.toString(), ReaderFontOption(it.name, it.toString()))
        }
        return fonts.values.toList()
    }

    /** 持久化字体文件夹（SAF tree uri 字符串）。 */
    fun setFolder(uri: String) {
        appCtx.putPrefString(PreferKey.fontFolder, uri)
    }

    private fun folderDoc(folder: String): FileDoc? =
        if (folder.isContentScheme()) {
            DocumentFile.fromTreeUri(appCtx, Uri.parse(folder))?.let(FileDoc::fromDocumentFile)
        } else {
            FileDoc.fromFile(File(folder))
        }
}
