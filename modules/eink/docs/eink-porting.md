# E-Ink 移植指南（已迁移）

本文档的最终维护版本已归档至宿主接入面目录：
`modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`
（2026-09-05 经 develop@01ee1e956 全量回放移植实测修订）。

## 端口表增量

归档版本之后的端口变化：

| 端口 | 必需性 | 职责 |
|---|---|---|
| ReaderSelectionEngine | 可选 | 阅读页长按选择的划线/想法落库、点按已有标记的浮条/浮窗（删除/写想法）与页面级书签 toggle。v2 方法清单：saveMarking（划线/想法同锚点 upsert）、deleteMarking、findMarking、togglePageBookmark；Commit = chapterIndex/start/end/selectedText/note/thought，附 ReaderMarkingDetail（v1 选区书签链路——解析预填、选区书签落库与书签弹层——已退役，页面级书签接管）。无端口：长按选择不启用（无浮条/无落库），点按标记浮条/浮窗不可达，下拉书签与顶栏书签钮隐藏（Task 9 落地其 UI 门控） |
| MarksEngine | 可选 | 目录页**书签 / 笔记 Tab**（标题下三段切换）的数据来源、条目跳转目标解析与笔记导出（列表按书名+作者跨源聚合）。方法清单：observeBookmarks（bookmarks 表 Flow，chapterIndex/chapterPos 升序）、observeMarkings（book_marks 表 Flow，**章内正文本位置**升序、同位置按 createdAt——位置在 anchorJson 内，SQL 排不了，宿主映射后排序）、resolveBookmarkJump（源指纹+章节标题校验：Match→Located、不 Match→NeedConfirm fallback=存储坐标、不存在→Failed，不执行跳转不写进度）、resolveMarkingJump（校验不 Match 先本地重定位——选中文本+前后文评分仅本地缓存章节——成功按重定位坐标、失败 NeedConfirm）、exportMarkingsMarkdown（划线/想法写 SAF uri，false=书不存在/无笔记/写失败）；模型 = BookmarkUiModel / MarkingUiModel（MarkingUiModel.chapterPos = 锚点章内位置，笔记卡排序依据），附 JumpResolution 三分支与 PendingJumpConfirm 确认弹层瞬态。无端口：目录页不显示书签 / 笔记 Tab（只剩目录，单列表现状不回退），无假死路径 |
| BookshelfGroupEngine | 可选 | 书架分组浏览/切换/排序（分组管理——建组、删组、书归组、AI 分组、标签规则——不在端口面内，仍归完整模式）。方法清单：observeGroups（book_groups 表 show>0 行按 order 排 + 组内计数；hideEmptyGroups 过滤空组、「全部」恒在）、observeGroupBooks（组内书籍流，映射语义同 observeShelf；组 bookSort>=0 覆盖全局排序）、selectedGroup / setSelectedGroup（选中分组与宿主 saveTabPosition 共享记忆，跨模式一致）、moveGroup（排序 ▲▼ 唯一写通道：与相邻行交换，每次点击即写 book_groups.order、不攒批，首行上移/末行下移 no-op，结果经 observeGroups 重发）。宿主义务：位掩码运算封闭在实现内；BookshelfGroupIds.ALL = -1 为「全部」约定值（宿主 BookGroup.IdAll）。无端口：宿主无分组浏览能力，书架选择器不渲染（书架维持全量平铺），不参与 install 必填校验 |
