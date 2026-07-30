package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.DownloadService
import io.legado.app.utils.startService

/**
 * 下载入口模块，提供通过系统下载管理器下载文件的功能。
 */
object Download {


    fun start(context: Context, url: String, fileName: String) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
        }
    }

}