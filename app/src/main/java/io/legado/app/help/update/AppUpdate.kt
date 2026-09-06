package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    val gitHubUpdate: AppUpdateInterface? by lazy {
        AppUpdateGitHub
    }

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}

/**
 * 检查完成但远端无更高版本——「已是最新版本」的语义化载体：
 * 与检查失败（网络/解析异常）区分，供消费方走不同的用户反馈路径。
 */
class UpToDateException : NoStackTraceException("已是最新版本")
