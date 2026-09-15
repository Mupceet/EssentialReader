package io.legado.app.utils

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import java.io.File

/**
 * 按文件路径或 `content://` Uri 加载字体文件；失败返回 null，由调用方回落系统字体。
 *
 * API 26 起 [Typeface.Builder] 可以直接消费 FileDescriptor；API 24/25 只有
 * `Typeface.createFromFile(File)`，所以 content Uri 先落一份缓存副本再按文件加载。
 */
fun loadTypefaceOrNull(context: Context, path: String): Typeface? {
    if (path.isBlank()) return null
    return when {
        path.startsWith("content://", ignoreCase = true) ->
            loadTypefaceFromUri(context, path.toUri())

        else -> {
            val uri = path.toUri()
            val file = File(if (uri.scheme == "file") uri.path ?: path else path)
            if (file.isFile) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
        }
    }
}

private fun loadTypefaceFromUri(context: Context, uri: Uri): Typeface? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                Typeface.Builder(it.fileDescriptor).build()
            }
        }.getOrNull()
    }
    // API 24/25 没有 Typeface.Builder，只能把内容复制成文件后按路径加载。
    val cacheFile = File(context.cacheDir, "typeface-${uri.toString().hashCode()}")
    return runCatching {
        if (!cacheFile.isFile) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
        }
        Typeface.createFromFile(cacheFile)
    }.getOrNull()
}
