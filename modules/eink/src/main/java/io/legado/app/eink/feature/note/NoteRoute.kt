package io.legado.app.eink.feature.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 笔记页 Route 占位（Task 8 接线）：仅保证 [io.legado.app.eink.app.EInkScreen.Note]
 * 路由可达、返回可用；划线/想法混合列表、跳转与导出由后续任务在此替换实现。
 *
 * @param bookUrl 书籍唯一键（后续任务经 MarksEngine.observeMarkings 取列表）
 * @param fromReader 是否自阅读页链路进入（经目录页）：跳转后 pop 回阅读页；
 *   false = 预留（详情等入口），跳转后 replaceTop 进阅读页
 */
@Composable
fun NoteRoute(
    bookUrl: String,
    fromReader: Boolean,
    onBack: () -> Unit,
) {
    NoteScreen(onBack = onBack)
}

@Composable
private fun NoteScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(title = "笔记")
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EInkText(text = "笔记页施工中", style = EInkTheme.typography.bodyLarge)
        }
        EInkOperationBar(
            tabs = emptyList(),
            selectedTabIndex = 0,
            onTabSelect = {},
            navigationIcon = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_arrow_back),
                    contentDescription = "返回",
                    onClick = onBack,
                )
            },
        )
    }
}
