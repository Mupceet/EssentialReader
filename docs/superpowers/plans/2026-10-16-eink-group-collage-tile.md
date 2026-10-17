# 聚合条目嵌套封面拼贴（P2 微切片）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 聚合条目磁贴改为嵌套封面拼贴：外层 2×2（前三本书封面 + 右下递归 2×2 第四至七本），空位留白，左下角缺角矩形角标显示书籍数量。

**Architecture:** 宿主侧新增轻量封面投影查询（每组合并流），契约 Group 模型 `coverUrl` 字段替换为 `covers: List<BookshelfCoverRefUiModel>`；UI 侧新组合贴组件（网格/列表共用）+ 缺角角标 Shape。契约变更与消费者同任务原子提交。

**Tech Stack:** 同 P0/P1。**规格：** 主 spec D9（2026-10-16 修订行）。

## Global Constraints

（继承 P0/P1 全部约束）先 eink-lib 后宿主提交；只 add 触碰文件；E-Ink 零动画；`EInkBookCover` 对空 url 渲染文字占位——**拼贴空位不组合该组件**（组合空白 Box）；缓存键带尺寸——象限尺寸 Dp 必须显式计算下传。

---

### Task 1: 封面拼贴磁贴（双仓原子）

**Files:**
- Modify: `app/src/main/java/io/legado/app/data/dao/BookDao.kt`（+投影 POJO 与查询）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt`（Group.coverUrl→covers；+BookshelfCoverRefUiModel）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt`（observeShelfWithGroups 每组封面合并流；纯函数签名）
- Modify: `app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt`（纯函数用例适配 + 封面夹具）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt`（GroupGridItem/GroupListItem 换拼贴磁贴；删 GroupTileText）
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/GroupCollageTile.kt`

**Interfaces:**
- Consumes: 既有 `EInkBookCover(url, name, author?, modifier, width, height, sourceOrigin?)`；`EInkShapes.medium`。
- Produces: `GroupCollageTile(covers: List<BookshelfCoverRefUiModel>, bookCount: Int, tileWidth: Dp, tileHeight: Dp, modifier)`；契约模型 `BookshelfCoverRefUiModel(url: String, origin: String?)`；DAO `flowUserGroupCoverRefs(groupId: Long, limit: Int): Flow<List<BookGroupCoverRef>>`。

- [ ] **Step 1: 纯函数测试先行**

`BookshelfGroupEngineImplTest.kt`：`buildShelfWithGroupsEntries` 增加 `groupCovers: Map<Long, List<BookshelfCoverRefUiModel>>` 参数——既有用例补 `groupCovers = emptyMap()`；新增用例：

```kotlin
@Test
fun `封面引用按组装配并截断至七`() {
    val groups = listOf(group(0b1, order = 0))
    val covers = (1..9).map { BookshelfCoverRefUiModel("c$it", null) }
    val result = buildShelfWithGroupsEntries(
        userGroups = groups, userCounts = mapOf(0b1L to 9),
        groupCovers = mapOf(0b1L to covers),
        ungroupedBooks = emptyList(),
        filterActive = false, hideEmpty = false,
        nameOf = nameOf, bookMapper = mapper,
    )
    val entry = result.single() as BookshelfShelfEntryUiModel.Group
    assertEquals(7, entry.covers.size)
    assertEquals("c1", entry.covers.first().url)
}
```

- [ ] **Step 2: 跑测试确认失败**（编译错：签名/模型未定义）

- [ ] **Step 3: DAO 投影查询**

`BookDao.kt`（POJO 位置随既有 `GroupBookCount` 同址；若 GroupBookCount 在 dao 包即同包）：

```kotlin
/** 组内前 N 本封面引用投影（eink 聚合条目拼贴）。 */
data class BookGroupCoverRef(
    val coverUrl: String?,
    val origin: String?,
)

@Query(
    """
    SELECT ifnull(customCoverUrl, coverUrl) AS coverUrl, origin
    FROM books
    WHERE (`group` & :groupId) > 0
    AND ((SELECT isPrivate FROM book_groups WHERE groupId = :groupId) = 1 OR $PUBLIC_BOOK_FILTER)
    ORDER BY durChapterTime DESC
    LIMIT :limit
    """
)
fun flowUserGroupCoverRefs(groupId: Long, limit: Int): Flow<List<BookGroupCoverRef>>
```

（先 grep `Book.getDisplayCover` 实现确认 `ifnull(customCoverUrl, coverUrl)` 与其一致；若其另有逻辑（如本地书路径处理），以注释说明偏差并保持 SQL 投影近似。）

- [ ] **Step 4: 契约模型替换**

```kotlin
/** 封面引用（url + 所属书源 origin，origin 参与加载解析）。 */
@EInkImmutable
data class BookshelfCoverRefUiModel(
    val url: String,
    val origin: String?,
)
```

`BookshelfShelfEntryUiModel.Group`：删 `coverUrl: String?`，加 `val covers: List<BookshelfCoverRefUiModel>`（KDoc：按 durChapterTime DESC 前 7，空位由 UI 补白）。

- [ ] **Step 5: 桥装配**

`observeShelfWithGroups`：userGroups 非空分支的 countsFlow 旁并构 coversFlow（同形 combine，每组 `flowUserGroupCoverRefs(g.groupId, 7)`，桥内过滤 `coverUrl` 空白后映射契约模型）；并入最终 combine（参数位增加）。`buildShelfWithGroupsEntries` 增加 `groupCovers: Map<Long, List<BookshelfCoverRefUiModel>>` 参数，Group 条目构造 `covers = (groupCovers[group.groupId] ?: emptyList()).take(7)`。

- [ ] **Step 6: 拼贴组件（新建 GroupCollageTile.kt）**

```kotlin
package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfCoverRefUiModel
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover

/**
 * 聚合条目嵌套封面拼贴磁贴（spec D9 2026-10-16 修订）：
 * 外层 2×2 = 前三本封面 + 右下递归 2×2（第四至七本），空位留白；
 * 左下角缺角矩形角标（左上角 45° 切角）显示书籍数量。
 * E-Ink：静态单帧、零动画；象限尺寸显式计算（封面缓存键带尺寸）。
 */
private val COLLAGE_GAP = 2.dp
private const val COLLAGE_COVER_COUNT = 7

@Composable
fun GroupCollageTile(
    covers: List<BookshelfCoverRefUiModel>,
    bookCount: Int,
    tileWidth: Dp,
    tileHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = modifier
            .size(tileWidth, tileHeight)
            .border(1.dp, colors.outline, EInkShapes.medium),
    ) {
        val quadrantWidth = (tileWidth - COLLAGE_GAP * 2) / 2
        val quadrantHeight = (tileHeight - COLLAGE_GAP * 2) / 2
        Column(modifier = Modifier.fillMaxSize().padding(COLLAGE_GAP)) {
            Row(modifier = Modifier.weight(1f)) {
                CollageQuadrant(covers.getOrNull(0), quadrantWidth, quadrantHeight, Modifier.weight(1f))
                Box(Modifier.size(COLLAGE_GAP))
                CollageQuadrant(covers.getOrNull(1), quadrantWidth, quadrantHeight, Modifier.weight(1f))
            }
            Box(Modifier.size(COLLAGE_GAP * 2, COLLAGE_GAP))
            Row(modifier = Modifier.weight(1f)) {
                CollageQuadrant(covers.getOrNull(2), quadrantWidth, quadrantHeight, Modifier.weight(1f))
                Box(Modifier.size(COLLAGE_GAP))
                // 右下递归 2×2：第四至七本
                Box(modifier = Modifier.weight(1f)) {
                    val innerW = (quadrantWidth - COLLAGE_GAP * 2) / 2
                    val innerH = (quadrantHeight - COLLAGE_GAP * 2) / 2
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) {
                            CollageQuadrant(covers.getOrNull(3), innerW, innerH, Modifier.weight(1f))
                            Box(Modifier.size(COLLAGE_GAP))
                            CollageQuadrant(covers.getOrNull(4), innerW, innerH, Modifier.weight(1f))
                        }
                        Box(Modifier.size(COLLAGE_GAP * 2, COLLAGE_GAP))
                        Row(Modifier.weight(1f)) {
                            CollageQuadrant(covers.getOrNull(5), innerW, innerH, Modifier.weight(1f))
                            Box(Modifier.size(COLLAGE_GAP))
                            CollageQuadrant(covers.getOrNull(6), innerW, innerH, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // 左下角缺角计数标
        NotchedCountBadge(
            count = bookCount,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(EInkSpacing.xxs),
        )
    }
}

/** 单象限：有封面则渲染（尺寸显式传参与缓存键一致），空位留白不组合封面组件。 */
@Composable
private fun CollageQuadrant(
    cover: BookshelfCoverRefUiModel?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (cover != null) {
            EInkBookCover(
                url = cover.url,
                name = "",
                modifier = Modifier.fillMaxSize(),
                width = width,
                height = height,
                sourceOrigin = cover.origin,
            )
        }
    }
}

/** 缺角矩形角标：左上角 45° 切角（cut=8dp），onSurface 实心底 + 反色字。 */
@Composable
private fun NotchedCountBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = modifier
            .background(colors.onSurface, NotchedCornerShape(cut = 8.dp))
            .padding(horizontal = EInkSpacing.xs, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = "$count 本",
            style = EInkTheme.typography.labelSmall,
            color = colors.background,
        )
    }
}
```

`NotchedCornerShape`（同文件或 shapes 工具区）：

```kotlin
/** 左上角 45° 切角的矩形（cut 为切角直角边长）。 */
private class NotchedCornerShape(private val cut: Dp) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = androidx.compose.ui.graphics.Outline.Generic(
        Path().apply {
            val c = with(density) { cut.toPx() }
            moveTo(c, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            lineTo(0f, c)
            close()
        },
    )
}
```

- [ ] **Step 7: 条目接线（BookshelfScreen.kt）**

- `GroupGridItem`：磁贴区（现 `Box(aspectRatio + border + 封面/GroupTileText)`）替换为：

```kotlin
        GroupCollageTile(
            covers = group.covers,
            bookCount = group.bookCount,
            tileWidth = gridCellWidth,
            tileHeight = gridCellWidth * (EInkCoverHeight / EInkCoverWidth),
        )
```

（外层 Column 保留 combinedClickable；磁贴下方保留组名行，**删除「N 本」计数行**——计数已在缺角标。）
- `GroupListItem`：磁贴 Box（border+GroupTileText）替换为 `GroupCollageTile(covers, bookCount, listCoverWidth, listCoverHeight)`；右列「分组 · N 本」改「分组」（计数由角标承载）。
- 删除 `GroupTileText` 组件与相关无用 import。

- [ ] **Step 8: 测试 + 编译 + 提交**

Run: `./gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest" :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest`
Expected: 全绿。

提交：
- eink-lib：`feat(bookshelf): 聚合条目嵌套封面拼贴磁贴——外层2×2前三本+右下递归第四至七本、空位留白、左下缺角矩形计数标；Group 模型 coverUrl→covers（带 origin）`
- 宿主：`feat(eink-bridge): 组封面引用投影查询(durChapterTime DESC LIMIT 7)与拼贴装配；eink-lib 指针推进`

---

### Task 2: 验证 + 无线安装

- [ ] `./gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache` 全绿
- [ ] 无线安装：`adb connect 192.168.0.80:<mDNS 新端口>`（`adb mdns services` 查）→ `adb install -r app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk`
- [ ] 交付说明（真机待验：拼贴在残影下的辨识度、缺角标与封面重叠遮挡、7 封面加载时序）

## Self-Review

- Spec 覆盖：D9 修订行全部要素（2×2/前三本/右下递归/留白/缺角标/7 上限/durChapterTime 序/origin）→ Task 1 Steps 3-7。
- 类型一致：`BookshelfCoverRefUiModel(url, origin)` 契约/桥/测试/UI 四处一致；`covers` 字段替换后无旧 `coverUrl` 残留（编译期保证）。
- 不确定点：`GroupBookCount` POJO 实际位置（Step 3 就近）；`getDisplayCover` 与投影一致性（Step 3 核对）。
