# MD3 上游 2026-10-21 → 11-03 合并分析与适配清单

- 范围：md3/main `67970a10b`（墨本已合并点）→ `f5c45b7f7`，103 提交、675 文件、+69,683/−2,975。
- 方法：按主题深读提交 diff 内容（非 commit message），七个领域并行分析；对照墨本侧同期 53 提交的文件交集（32 文件）做冲突预演。
- 工作空间：worktree `EssentialReader-md3-merge`，分支 `eink/md3-merge`（基于 `origin/eink/port/md3/modules` = 331af4d61）。

## 一、合并时必须处理（A 级）

| 项 | 来源 | 要点 |
|---|---|---|
| 富文本/RTF 渲染重写 | 1a84bf0e6（116 文件 +4615/−1155） | EPUB/MOBI 语义 HTML 保留 → `ReaderLocalPresentation` → `TextProjection` 逐字投影回正文；重写 `ContentProcessor`/`ReaderPaginator`/`ReaderChapterSource`/`ReadBook.getContent` 链。本批最大冲突源，绕不开。投影失配自动降级纯文本，正文仍是权威 |
| core/full 双包拆分 | 0a518bc81 + 40134e21d | `flavorDimensions = mode + engine`；`app/src/full`=漫画本地推理栈（ONNX/ML Kit，仅 fullImplementation），`app/src/core`=Unavailable 桩（32 行）。**任务名全变**（testAppDebugUnitTest → testAppCore*UnitTest），CI/AGENTS.md/发版 matrix 全绑定。结构性强制跟进 |
| DB 132→133 | a5400cb31 | 仅 `highlight_rules` +3 列（borderColor/Width/Radius），AutoMigration。墨本 schema 与上游 1..132 逐字节一致无分叉，fast-forward 低危；墨本后续自研迁移须基于 133 |
| CI/发版三 workflow + version.properties | 0a518bc81/40134e21d | 上游 matrix appCore/appFull vs 墨本自有发版体系（VERSION_CODE 显式、moben- 前缀、手写日志）——身份级差异，需手工融合；version.properties 行行冲突，取墨本侧 |
| ReadSettings 映射三处同构插入 | 91e19d0d0 + f2cff583d | 上游加 themeGridColumns/paragraphBubblePackage/forceSoftwareParagraphBubble vs 墨本 showReviewBubbles，`ReadSettingsRepository.kt` 读写+Keys 三处必撞；`ReadSettingsMappingTest` 字段计数同一行双改，解后重数 |
| Restore.kt | f2cff583d | 气泡包目录树恢复（imports + 375~453 区）与墨本阅读记录判重/设备分流（94/129/376~614 区）重叠 |
| BookDao 视频过滤语义 | 视频系列 | 上游在书架查询插 `type & video = 0`；墨本自研的分组/聚合查询需逐条判断是否同样要排除视频，属**易漏的语义冲突**（非文本冲突） |
| 视频契约层 | 078a16ed7 等 | `BookSourceType.video=4`（旧源零影响）、`BookType.video=0b100`（激活 legado-E 保留位）、`BookGroup.IdVideo=-6L`（墨本空置无冲突，已核实）。分组 `show=0` 无视频书时不显示，入口不突兀 |
| 首启迁移顺序 | 9b434db68 | `DefaultData.upVersion()` 前移到 onCreate 同步区（修升级迁移被跳过）；与墨本 App.kt eInkMode 一次性迁移共存，需回归首启顺序 |
| 邻章预排延迟 | 63f8bb5d8 | 当前章 batch commit 后才调度邻章预排（消 3 章并发排版）；墨水屏慢 CPU 上翻向未预排章的空白/加载态需实测 |

## 二、直接受益（B 级，低冲突，建议整批接收）

- **内存/性能组（#2478 + 27a5fb048 + 04f76aecd）**：字形宽度 `List<Float>`→`FloatArray` 去装箱；整章测量块流式丢弃（生产路径只转发不持有）；页文本 StringBuilder 成型即释放；流式页合表 O(n) 归并；翻页热路径 `mapNotNull`→无分配扫描。全部命中墨本命门（分页峰值内存、GC churn），作用于宿主 Canvas 轨道分页核心，墨本零改动文件。
- **排版正确性**：开标点连 Latin 单词整体换行（`ChineseLineBreaker.safeBreakStart` 重写）；对称符号（引号/撇号/竖线）上下文配对分类 + 行尾悬挂 + 越界收紧；下划线等装饰不超出内容 clip；引号跨段不再整章打挂（3 行修复）。
- **稳定性**：快速连续翻页保留已提交页状态（墨水屏组合更慢、更易触发）；分页失败原因带异常 message 与栈顶；批量导入 entryId 防重复 key 崩溃；条漫超长图拆段渲染（修 Constraints 溢出崩溃）。
- **周边**：长按翻译重译当前章（含并发防护）；cast 三笔（角色 last-seen/档案 500 上限/闭引号栈自愈，TTS 听书直接受益）；内置词典改汉典（带失效规则清理，不误删自建）；缓存维护（孤立章节缓存清理+字数字数重算）；高亮边框样式（渲染直接可用，随 DB 133）；a11y 语义修复；`HtmlFormatter` 二次转义修复（墨本 eink `displayIntro` 同链路受益）。

## 三、值得评估引入（C 级）

| 特性 | 墨水屏取舍 |
|---|---|
| 段评气泡包 f2cff583d | 书源 `dp:` 约定 + 气泡包目录契约 + SVG 栅格化，全走宿主 Canvas 行内图链路——完整模式直接获得；eink-lib 轨道需等价适配 INLINE 图才有。注意 ec0c203f1 已把 Compose 段评弹层回退为 `BottomWebViewDialog`（保留预加载脚本），跟随最终态即可 |
| 阅读主题列表弹窗 91e19d0d0 | 网格/流式/管理/导入导出，格式与 legado 单主题包 zip 互通；批量导入同名由覆盖改追加。墨水屏高价值交互改进，但触碰 ReadStyle/ReadSettings 热点，需与墨本主题网关对接 |
| pageParam 书源级翻页 636a33ecd | `ruleExplore.pageParam` 字段，无规则运行时地址的兜底分页；hapelee 私有扩展但导出无破坏。墨本 eink 模式无发现页，仅完整模式受益 |
| AI 对话入口 fa7c8c5e1 | 携带书/章元数据（不含正文）注入 system prompt；依赖应用内 AI 供应商配置。价值取决于墨本是否保留 AI 模块 |
| 封面比例自适应 76dd042dd | 默认关（固定 5:7）；仅搜索/发现/详情，书架与 eink-lib 封面体系零交互 |
| epub 图片缓存自适应 f5c45b7f7 | 灰占位修复部分直接受益；但 LRU 上限 128MB/堆 1/4 对低内存墨水屏偏激进——若滚动模式非主轨可裁掉扩容部分 |
| 关联书籍兜底/作者标签/kind 跳转/瀑布流/发现顶栏 sheet | 均在 app 侧 ui/book/{info,explore}，墨本零改动区域，冲突低；价值随完整模式使用率而定 |
| WebView 容器化 30b2fdbf7 | FrameLayout 间接挂载规避 Android16 Vulkan drawVk SIGTRAP；墨本 SourceLogin 若同栈可借鉴 |

## 四、可借鉴机制（不必引入特性本体）

1. `PageSnapshotBitmapPool`/`PageSnapshotKey`：页身份键（revision/背景/选区全入键）+ settled 门控（图片未就绪不固化快照）+ 位图优先/图层重放兜底——可迁移到墨本宿主位图管理、屏保整页位图、残影差量刷新的脏区判定。
2. `ReaderCommittedTurnPolicy`：「提交页 vs 组合页」竞态自愈 + 30 帧有界等待——任何异步截图/提交管线（含墨本屏保截图）的通用加固范式。
3. `upVideoPlayProgress` 单列 `@Query` 更新替代全字段 `@Update`（避免覆盖并发写的阅读进度）；`Dispatchers.IO.limitedParallelism(1)` + Mutex 进度串行化——墨本阅读进度同步可对照。
4. 翻页速度归一化（`scaledDuration` 映射共享档位）。
5. 气泡包导入校验（清单/路径逃逸/魔数/压缩比白名单 + staging 事务回滚）——墨本导入类功能（书源/主题包）可对照加固。

## 五、放弃 / 裁剪建议

- **三套仿真翻页本体（iOS curl / Google curl / parallax）**：墨水屏刷新模型与逐帧动画根本冲突；iOS curl 的 EGL+glReadPixels 回读在墨水屏是最坏组合。合并会带入（自洽、已 MIT 化、默认休眠在完整模式），建议在设置层屏蔽 `PageAnim` 6/7/8 三个新选项防用户误入。机制借鉴见上节。
- **视频播放 UI**：media3 hls/dash/ui 是 main sourceSet 全变体依赖，上游无开关，要裁只能整体带上后自删（入口 4 处 + DAO 条件 + 依赖，代码集中可行）。折中：至少带契约+数据层（识别 `bookSourceType:4` 书源、正确分组，成本极低），播放体验在墨水屏上天然受损（TextureView 全屏重绘、倍速/悬浮窗/PiP 无意义）。
- **漫画本地推理 full 栈**：墨本走 appCore（Unavailable 桩）即可，省 11MB 模型 + onnxruntime/ML Kit；漫画阅读 + 云端/AI 翻译仍可用。
- **Miuix squircle 关闭（8ecc31680）**：仅当墨本 SourceLogin WebView 出现同栈 SIGSEGV 才需要。

## 六、合并策略与验证清单

- **整体 merge，不要 cherry-pick**：103 提交多主题交织，多个中间态提交混入无关改动（35b863526 混 reader 大改、636a33ecd 混 VideoPlayerScreen）；squash/PR 重落对（63f8bb5d8 vs 27a5fb048）取 md3/main 最终态。
- 高冲突文件（解冲突预算集中处）：auto-release.yml、verify.yml、build-apk-for-user.yml、version.properties、Restore.kt、ReadSettingsRepository.kt、ReadSettingsMappingTest.kt、app/build.gradle.kts。
- 跟进 core/full 后 CI 与本地命令全面切换：`testAppCoreDebugUnitTest` / `assembleAppCoreDebug` 等；只发布 appCore。
- 合并后必验：layout 排版单测全量（上游断行/悬挂行为有可见变化）；ReadSettingsMappingTest 字段计数重数；图文 epub 滚动模式冒烟；首启默认数据迁移 + eInkMode 迁移共存回归；`GlyphClusters` 数组字段 equals 语义（墨本自测改 assertArrayEquals）；完整模式书架确认视频组默认隐藏。

## 七、合并执行记录（2026-11-03，分支 eink/md3-merge）

- 实际冲突仅 7 文件（预演 32 文件大多自动合并且语义正确）：CI×3、version.properties、Restore.kt、MyScreen.kt、ReadSettingsMappingTest.kt。
- 解决取向：version.properties 取墨本；Restore 双方保留（上游气泡包恢复 + 墨本入口预解析 configMap）；ReadSettingsMappingTest 字段计数 113+1+3=**117**（已测试确认）；MyScreen 双 import。
- CI 融合：verify.yml = Core-only matrix + 墨本 timeout45/打包隔离/submodules + 上游 job 级 concurrency 与 androidTest 编译检查；auto-release.yml = appCore-only，APK 命名 `moben-<版本>-core-<abi>.apk`（**必须保留 `-core-` 标记**：应用内更新 `AppReleaseInfo.Asset.releaseProduct` 按该标记挑包），mapping 只传 mapping-core.txt；build-apk-for-user 的 product choice 收敛为 appCore。
- 墨本适配决策（用户拍板）：阅读界面不采纳宿主翻页动画——PageAnim 6/7/8 在 PageAnimConfigSheet / GlobalThemePage / ReaderMoreActionsSheet 三处枚举移除（内核代码保留、休眠）；只发布 appCore。
- BookDao 语义判断：上游仅从「网络未分组文本」类查询排除视频，用户组/未读/在读允许视频书（点击由路由分流播放器）——墨本自研查询与该语义一致，未加补丁。
- 工作区坑：worktree 子模块需 `git -c protocol.file.allow=always` 且 gitlink 82206ba20 当时不在本地对象库（需先 `git fetch origin eink/lib` 进主仓再从本地路径补）；新 worktree 需拷 local.properties。
- 验证：compileAppCoreDebugKotlin 通过；ReadSettingsMapping/ReaderPunctuation/ChineseLineBreaker/ReaderCommittedTurnPolicy 单测通过；完整主验证集见交付说明。

## 附：领域分析要点备查

- md3 架构现状：`feature/reader/core`（分页核心产 `ReaderPage`）+ `ReaderCanvasSurface`（Canvas 直绘）+ `ui/book/read`（Compose 宿主）；旧 `ui/book/read/page` 双方均已不存在；"legacy" = 旧章节内容管线适配器。
- 三套翻页页帧来自 Compose GraphicsLayer 自录（非系统截图）；iOS curl 几何内核已换 MIT 的 Peyilo/PageCurl-Core（初版反编译产物风险已消除）。
- 视频：`VideoPlay` 进程内单例 + `VideoPlayService` 前台服务（不含播放器）+ 悬浮窗/PiP；与阅读服务完全平行互不感知；进度串行化防后发先至。
- RTF 提交的 "RTF" = Rich Text 泛称，不含 Microsoft .rtf 解析器。
- 段评气泡包与 d8cf117ab 的「角色台词气泡」是两套无关机制，后者属 cast 排版稳定性修复（B 级）。
