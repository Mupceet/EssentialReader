# E-Ink 书籍详情页设计

日期：2026-08-15
状态：已确认（用户批准，含调整）

## 目标

搜索结果点击后进入 E-Ink 书籍详情页；无顶栏，封面在内容区最顶部，
返回按钮放在底部操作栏。参考 View 版 `BookInfoActivity` 的信息数据结构。

## 用户确认的布局

1. 无顶栏；封面在顶部（加大尺寸，约 110×160dp，与 View 一致）。
2. 封面下方：书名 + 作者（居中）。
3. 操作按钮一排（图标在上、文字在下，4 个）：
   - 加入书架、查看目录、切换书源、阅读
4. 信息区（图标 + 文字行）：当前进度章节、最新章节、标签/分类、书源、字数。
5. 简介完整显示（多行不省略）。
6. 底部操作栏：左侧返回按钮（`EInkOperationBar` + `EInkBackButton`）。

## 开放决策（已确认）

1. **切换书源**：本期做占位（点击提示"该功能开发中"），与阅读器占位一致。
2. **数据获取**：打开详情时若不在书架，联网调用 `WebBook.getBookInfoAwait` 拉取
   完整详情（简介/标签/最新章节/封面/字数），带 loading 状态。

## 入口与导航

- `EInkScreen` 新增 `data class BookDetail(name, author, bookUrl)`。
- `EinkApp` 增加 `is EInkScreen.BookDetail` 分支。
- 搜索结果项 `ResultItem` 可点击 → `EinkApp` 中 `navigate(EInkScreen.BookDetail(...))`。
- 详情页内：查看目录 → `EInkScreen.Toc(bookUrl)`；阅读 → `EInkScreen.Reader(bookUrl)`（占位）；返回 → pop。

## 数据层（BookDetailViewModel，UDF）

- 加载顺序（参考 View `BookInfoViewModel.initData`）：
  1. `appDb.bookDao.getBook(name, author)`
  2. `appDb.bookDao.getBook(bookUrl)`
  3. `appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()`
  4. `appDb.searchBookDao.getFirstByNameAuthor(name, author)?.toBook()`
- 找到后：计算 `isInBookshelf`；若 `tocUrl` 为空且非本地书，调用
  `WebBook.getBookInfoAwait(source, book)` 拉取完整信息（`isUpdating` 状态）。
- 动作：
  - `addToBookshelf`：参考 View 实现（`removeType(notShelf)`、`book.save()`），成功后更新
    `isInBookshelf` 并发消息。
  - `changeSource`：占位，发"该功能开发中"消息。
- 消息：`MutableSharedFlow<UserMessage>`，Route 收集后 Toast 显示。

## 界面（BookDetailRoute + 无状态 BookDetailScreen）

```
Column
├─ when:
│  ├─ isLoading → EInkLoading
│  ├─ book == null → "未找到书籍"
│  └─ else → Column(verticalScroll)
│     ├─ 封面 EInkBookCover(110×160，居中)
│     ├─ 书名（居中 titleLarge）
│     ├─ 作者（居中 bodyMedium, onSurfaceVariant）
│     ├─ 操作按钮 Row（SpaceEvenly）：4 × 图标上文字下按钮
│     ├─ EInkHorizontalDivider
│     └─ 信息区：
│        ├─ 当前进度章节（ic_history + EInkInfoRow）
│        ├─ 最新章节（ic_book_last + EInkInfoRow）
│        ├─ 标签/字数（getKindList）
│        ├─ 书源（originName）
│        └─ 简介（完整显示）
└─ EInkOperationBar（navigationIcon=返回，翻页箭头置灰）
```

- `EInkBookCover` 增加 `width`/`height` 参数（默认 66×90 不变，详情页传 110×160）。
- 图标复用现有 drawable：`ic_add`/`ic_book_has`（加入书架）、`ic_toc`（目录）、
  `ic_swap_horiz`（切换书源）、`ic_play_outline_24dp`（阅读）。
- 新增字符串资源：`eink_feature_developing`（该功能开发中）、`eink_added_to_bookshelf`（已加入书架）。

## 涉及文件

- 新增：`eink/bookdetail/BookDetailViewModel.kt`、`eink/bookdetail/BookDetailRoute.kt`
- 修改：`navigation/EInkScreen.kt`、`EinkApp.kt`、`search/SearchScreen.kt`、
  `widget/EInkBookCover.kt`、`res/values/strings.xml`

## 范围外（本期不做）

- 真实的切换书源（跨书源搜索重载）
- 阅读器（仍为占位）
- 章节列表预加载（查看目录时由 Toc 页自行加载）