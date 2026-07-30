# Legado（开源阅读）技术开发文档

> **项目名称**: Legado（开源阅读）
> **项目类型**: 免费开源的 Android 平台电子书阅读器
> **开发语言**: Kotlin
> **当前数据库版本**: 79

---

## 目录

1. [技术栈概述](#1-技术栈概述)
2. [项目模块架构](#2-项目模块架构)
3. [数据层设计](#3-数据层设计)
4. [业务逻辑层](#4-业务逻辑层)
5. [后台服务层](#5-后台服务层)
6. [界面层（UI）](#6-界面层ui)
7. [Web API 层](#7-web-api-层)
8. [工具类/帮助类](#8-工具类帮助类)
9. [核心业务流程](#9-核心业务流程)
10. [关键设计模式](#10-关键设计模式)
11. [数据库设计](#11-数据库设计)
12. [第三方依赖](#12-第三方依赖)
13. [附录](#13-附录)

---

## 1. 技术栈概述

| 类别 | 技术/库 | 用途 |
|------|---------|------|
| 开发语言 | Kotlin | 主体开发语言 |
| 数据库 | Room (SQLite) | 本地数据持久化，当前版本 79 |
| 网络请求 | OkHttp + Cronet | HTTP 客户端与 Chromium 网络栈 |
| HTML 解析 | Jsoup + JsoupXpath | HTML 内容解析与 XPath 查询 |
| JSON 解析 | json-path | JSON 数据路径查询 |
| JS 引擎 | Rhino (Android) | 自定义 JavaScript 脚本执行 |
| 音频播放 | ExoPlayer (Media3) | 音频播放引擎 |
| 图片加载 | Glide | 图片加载与缓存 |
| Markdown 渲染 | Markwon | Markdown 内容渲染 |
| EPUB 解析 | epublib-core | EPUB 电子书格式解析 |
| 中文处理 | HanLP | 中文自然语言处理（分词等） |
| Web 服务 | NanoHTTPD + WebSocket | 内置 Web 服务器与实时通信 |
| Web 前端 | Vue.js | Web 书架与源编辑器前端 |
| 二维码 | bga-qrcode-zxing | 二维码扫描与生成 |

---

## 2. 项目模块架构

项目采用**分层架构**设计，核心代码位于 `app/src/main/java/io/legado/app/` 目录下。

### 2.1 整体分层

```
┌──────────────────────────────────────────────┐
│                  UI 层 (ui/)                  │
│    Activity / Fragment / ViewModel / View     │
├──────────────────────────────────────────────┤
│              业务逻辑层 (model/)               │
│   规则引擎 / 阅读核心 / 缓存 / 音频 / Web服务    │
├──────────────────────────────────────────────┤
│               数据层 (data/)                   │
│      DAO 接口 / Entity 实体 / Room 数据库      │
├──────────────────────────────────────────────┤
│            工具与基础设施层 (help/)             │
│     HTTP / 存储 / 加密 / 协程 / 图片加载       │
└──────────────────────────────────────────────┘
```

### 2.2 模块目录结构

```
app/src/main/java/io/legado/app/
├── data/          # 数据层
│   ├── dao/       # 23个DAO接口，数据访问对象
│   ├── entities/  # 23个实体类 + rule/子目录（6个规则类）
│   ├── AppDatabase.kt          # Room 数据库定义
│   └── DatabaseMigrations.kt   # 数据库迁移
├── model/         # 业务逻辑层
│   ├── analyzeRule/  # 规则解析引擎
│   ├── localBook/    # 本地书籍解析
│   ├── remote/       # 远程书籍管理
│   ├── rss/          # RSS 处理
│   ├── webBook/      # 网络书籍爬取
│   ├── ReadBook.kt   # 阅读核心
│   ├── ReadAloud.kt  # 朗读控制
│   ├── ReadManga.kt  # 漫画阅读
│   ├── AudioPlay.kt  # 音频播放
│   ├── CacheBook.kt  # 缓存下载
│   ├── CheckSource.kt # 书源校验
│   ├── Debug.kt      # 调试
│   └── ...
├── service/       # 后台服务
│   ├── AudioPlayService.kt      # 音频播放前台服务
│   ├── BaseReadAloudService.kt  # 朗读服务基类
│   ├── HttpReadAloudService.kt  # 在线朗读服务
│   ├── TTSReadAloudService.kt   # 系统TTS朗读服务
│   ├── CacheBookService.kt      # 书籍缓存前台服务
│   ├── CheckSourceService.kt    # 书源校验服务
│   ├── DownloadService.kt       # 通用下载服务
│   ├── ExportBookService.kt     # 书籍导出服务
│   ├── WebService.kt            # Web服务（NanoHTTPD）
│   └── WebTileService.kt        # Web快捷方式
├── ui/            # 界面层（MVVM 架构）
│   ├── main/      # 主界面（书架/发现/我的/RSS）
│   ├── book/      # 书籍相关功能
│   ├── about/     # 关于页面
│   ├── association/ # 关联导入
│   └── config/    # 应用配置
├── api/           # Web API 层
│   ├── controller/ # 控制器
│   ├── ReaderProvider.kt  # ContentProvider
│   └── ReturnData.kt      # 返回数据封装
├── help/          # 工具类/帮助类
│   ├── http/      # HTTP 工具
│   ├── config/    # 配置管理
│   ├── source/    # 书源辅助
│   ├── storage/   # 备份恢复
│   ├── coroutine/ # 协程工具
│   ├── crypto/    # 加密工具
│   ├── exoplayer/ # 播放器辅助
│   ├── glide/     # 图片加载辅助
│   ├── rhino/     # JS引擎辅助
│   └── update/    # 版本更新
├── constant/      # 常量定义
├── base/          # 基础类
├── exception/     # 自定义异常
├── lib/           # 第三方库封装
├── receiver/      # 广播接收器
└── App.kt         # Application 入口
```

---

## 3. 数据层设计

### 3.1 Room 数据库

**文件**: `AppDatabase.kt`

Room 数据库是整个应用的数据核心，定义了所有实体、DAO 接口和数据库迁移。

- **数据库版本**: 当前版本 79
- **AutoMigration**: 支持从 v43 到 v79 的自动迁移
- **TypeConverter**: 支持 JSON 序列化/反序列化

### 3.2 DAO 接口（23个）

数据访问层包含 23 个 DAO 接口，每个接口对应一个实体表的数据操作：

| DAO 名称 | 对应实体 | 职责 |
|----------|---------|------|
| BookDao | Book | 书籍数据操作 |
| BookChapterDao | BookChapter | 章节数据操作 |
| BookmarkDao | Bookmark | 书签数据操作 |
| BookGroupDao | BookGroup | 书籍分组操作 |
| BookTagDao | BookTag | 书籍标签操作 |
| BookSourceDao | BookSource | 书源数据操作 |
| RssSourceDao | RssSource | RSS源数据操作 |
| RssArticleDao | RssArticle | RSS文章操作 |
| ReplaceRuleDao | ReplaceRule | 替换规则操作 |
| DictRuleDao | DictRule | 词典规则操作 |
| TxtTocRuleDao | TxtTocRule | TXT目录规则操作 |
| SearchBookDao | SearchBook | 搜索书籍操作 |
| SearchKeywordDao | SearchKeyword | 搜索关键词操作 |
| ReadRecordDao | ReadRecord | 阅读记录操作 |
| ReadRecordDetailDao | ReadRecordDetail | 阅读记录详情 |
| CookieDao | Cookie | Cookie存储操作 |
| HttpTtsDao | HttpTTS | 在线朗读引擎操作 |
| RuleSubDao | RuleSub | 规则订阅操作 |
| SourceSubDao | SourceSub | 源订阅操作 |
| KeyedItemDao | KeyedItem | 键值项操作 |
| ServerDao | Server | WebDAV服务器配置 |
| CacheDao | Cache | 缓存数据操作 |
| AppLogDao | AppLog | 应用日志操作 |

### 3.3 实体类（23个 + 规则子类）

```
entities/
├── Book.kt               # 书籍信息
├── BookChapter.kt        # 书籍章节
├── Bookmark.kt           # 书签
├── BookGroup.kt          # 书籍分组
├── BookTag.kt            # 书籍标签（位掩码设计）
├── BookSource.kt         # 书源
├── RssSource.kt          # RSS源
├── RssArticle.kt         # RSS文章
├── ReplaceRule.kt        # 替换规则
├── DictRule.kt           # 词典规则
├── TxtTocRule.kt         # TXT目录规则
├── SearchBook.kt         # 搜索书籍
├── SearchKeyword.kt      # 搜索关键词
├── ReadRecord.kt         # 阅读记录
├── ReadRecordDetail.kt   # 阅读记录详情
├── Cookie.kt             # Cookie
├── HttpTts.kt            # 在线TTS引擎
├── RuleSub.kt            # 规则订阅
├── SourceSub.kt          # 源订阅
├── KeyedItem.kt          # 键值项
├── Server.kt             # 服务器配置
├── Cache.kt              # 缓存
├── AppLog.kt             # 应用日志
└── rule/                 # 规则子类
    ├── SearchRule.kt     # 搜索规则
    ├── ExploreRule.kt    # 发现规则
    ├── BookInfoRule.kt   # 书籍信息规则
    ├── TocRule.kt        # 目录规则
    ├── ContentRule.kt    # 正文规则
    └── ReviewRule.kt     # 评论规则
```

---

## 4. 业务逻辑层

### 4.1 规则解析引擎 (`analyzeRule/`)

规则引擎是 Legado 的核心能力，负责解析和处理书源定义的各类规则。

| 类名 | 职责 |
|------|------|
| **AnalyzeRule** | 规则引擎主类，支持4种解析方式：XPath、JSoup、JsonPath、Regex |
| **AnalyzeUrl** | URL 构建和 HTTP 请求处理 |
| **AnalyzeByXPath** | 基于 XPath 的 HTML 解析 |
| **AnalyzeByJSoup** | 基于 JSoup 的 CSS 选择器解析 |
| **AnalyzeByJsonPath** | 基于 JsonPath 的 JSON 数据提取 |
| **AnalyzeByRegex** | 基于正则表达式的文本解析 |
| **RuleAnalyzer** | 规则分析器，负责规则字符串的解析与验证 |

#### 四大解析方式

1. **XPath**: 使用 JsoupXpath 实现，支持标准 XPath 语法
2. **JSoup**: 使用 Jsoup 的 CSS 选择器，支持类 jQuery 的 DOM 操作
3. **JsonPath**: 使用 json-path 库，支持 JSON 数据的路径查询
4. **Regex**: 使用 Kotlin 原生正则，支持灵活的正则匹配和分组提取

#### 六大规则类型

| 规则 | 用途 |
|------|------|
| SearchRule | 定义搜索书籍的 URL 和结果解析规则 |
| ExploreRule | 定义发现页面的 URL 和内容解析规则 |
| BookInfoRule | 定义书籍详情页的解析规则 |
| TocRule | 定义章节目录的解析规则 |
| ContentRule | 定义章节正文的解析规则 |
| ReviewRule | 定义评论内容的解析规则 |

### 4.2 本地书籍 (`localBook/`)

负责本地电子书文件的解析和导入。

| 类名 | 职责 |
|------|------|
| LocalBook | 本地书籍统一入口，根据文件类型分发 |
| TextFile | TXT 文本文件解析 |
| EpubFile | EPUB 电子书解析（基于 epublib-core） |
| MobiFile | MOBI 格式电子书解析 |
| PdfFile | PDF 文件解析 |
| UmdFile | UMD 格式漫画解析 |

### 4.3 远程书籍管理 (`remote/`)

| 类名 | 职责 |
|------|------|
| RemoteBook | 远程书籍管理 |
| RemoteBookManager | 远程书籍管理器 |
| RemoteBookWebDav | 基于 WebDAV 的远程书籍同步 |

### 4.4 RSS 处理 (`rss/`)

| 类名 | 职责 |
|------|------|
| Rss | RSS 核心处理 |
| RssParserByRule | 基于规则的 RSS 解析器 |
| RssParserDefault | 默认 RSS 解析器 |

### 4.5 网络书籍 (`webBook/`)

| 类名 | 职责 |
|------|------|
| WebBook | 网络书籍爬取核心 |
| BookChapterList | 章节列表获取 |
| BookContent | 章节正文获取 |
| BookInfo | 书籍信息获取 |
| BookList | 书单/列表获取 |
| SearchModel | 搜索模型 |

### 4.6 阅读核心 (`ReadBook.kt` ~1054行)

`ReadBook` 是整个阅读体验的核心控制器，管理以下状态：

- 当前阅读书籍和章节
- 页面切换逻辑
- 章节加载与缓存
- 阅读进度记录
- 翻页模式切换
- 内容替换规则应用

### 4.7 朗读控制 (`ReadAloud.kt`)

管理朗读功能，支持：
- 系统 TTS 引擎朗读
- 在线 HTTP TTS 引擎朗读
- 朗读进度控制
- 朗读速度调节

### 4.8 漫画阅读 (`ReadManga.kt` ~640行)

漫画阅读器核心，支持：
- 图片加载与缓存
- 图片缩放与平移
- 翻页模式
- 阅读方向（左翻/右翻）

### 4.9 音频播放 (`AudioPlay.kt` ~433行)

音频播放控制，基于 ExoPlayer：
- 音频文件播放管理
- 播放列表控制
- 播放状态管理

### 4.10 缓存下载 (`CacheBook.kt` ~460行)

管理书籍缓存下载任务：
- 章节级别缓存
- 整本书缓存
- 范围缓存（指定章节范围）
- 下载队列管理
- 多线程并发下载

### 4.11 其他模块

| 类名 | 职责 |
|------|------|
| CheckSource.kt | 书源可用性校验 |
| Debug.kt (~310行) | 调试工具，用于规则调试 |
| BookCover.kt | 书籍封面处理 |
| ImageProvider.kt | 图片提供器 |
| Download.kt | 通用下载管理 |
| SharedJsScope.kt | JS 作用域共享，用于 Rhino 引擎 |

---

## 5. 后台服务层

### 5.1 音频播放服务

| 类名 | 职责 |
|------|------|
| **AudioPlayService** | 前台服务，管理音频播放，包含通知栏控制和 MediaSession |
| **BaseReadAloudService** | 朗读服务基类，提供通用朗读逻辑 |
| **HttpReadAloudService** | 在线朗读服务，通过 HTTP TTS 引擎获取朗读音频 |
| **TTSReadAloudService** | 系统 TTS 朗读服务，使用 Android TextToSpeech API |

### 5.2 缓存与下载服务

| 类名 | 职责 |
|------|------|
| **CacheBookService** | 前台服务，执行书籍缓存的多线程并发下载 |
| **DownloadService** | 通用文件下载服务 |
| **ExportBookService** | 书籍导出服务 |

### 5.3 Web 服务

| 类名 | 职责 |
|------|------|
| **WebService** | 基于 NanoHTTPD 的内置 Web 服务器，提供 Web 书架、源编辑器、文件上传等功能 |
| **WebTileService** | Web 快捷方式服务 |

### 5.4 其他服务

| 类名 | 职责 |
|------|------|
| **CheckSourceService** | 书源校验服务，批量检测书源可用性 |

---

## 6. 界面层（UI）

### 6.1 架构

UI 层采用 **MVVM** 架构模式：

```
Activity / Fragment → ViewModel → Repository → DAO → Room Database
```

- 使用 `BaseActivity` / `BaseFragment` / `BaseViewModel` 作为基类
- `VMBaseActivity` / `VMBaseFragment` 提供 ViewModel 绑定支持
- 数据绑定使用 LiveData 和 Flow

### 6.2 底部导航结构（4个Tab）

| Tab | 功能 | 对应目录 |
|-----|------|---------|
| 书架 | 书籍管理、分组、标签、搜索 | `ui/main/` (Bookshelf) |
| 发现 | 书源探索、分类浏览 | `ui/main/` (Explore) |
| RSS | RSS 订阅源管理 | `ui/main/` (RSS) |
| 我的 | 个人设置、配置管理 | `ui/main/` (My) |

### 6.3 主要 UI 模块

#### 主界面 (`ui/main/`)
- 书架管理（列表/网格布局）
- 发现页面
- RSS 订阅
- 个人中心

#### 书籍相关 (`ui/book/`)
- 阅读界面（ReadView, PageView, ContentTextView）
- 书籍管理（信息、搜索、导入）
- 书籍缓存
- 漫画阅读
- 音频播放
- 书签管理
- 分组/标签管理
- 书源管理
- 替换规则管理
- 词典规则
- 关联导入
- 扫码导入
- WebView 登录
- 字体管理
- 文件管理

#### 关于页面 (`ui/about/`)
- 应用版本信息
- 开源许可
- 更新日志

#### 关联导入 (`ui/association/`)
- URL Scheme 处理
- 外部链接导入

#### 应用配置 (`ui/config/`)
- 阅读配置
- 主题配置
- 备份配置

### 6.4 自定义 View

| 类名 | 用途 |
|------|------|
| ReadView | 阅读视图，管理页面渲染和翻页 |
| PageView | 页面视图，处理单页内容显示 |
| ContentTextView | 内容文本视图，处理文本渲染 |

### 6.5 翻页模式（4种）

通过 `PageDelegate` 及其子类实现：

| 模式 | 描述 |
|------|------|
| 覆盖 | 新页面覆盖在当前页面上方 |
| 仿真 | 模拟真实翻页效果 |
| 滑动 | 左右滑动切换页面 |
| 滚动 | 上下滚动连续阅读 |

---

## 7. Web API 层

### 7.1 控制器

| 类名 | 职责 |
|------|------|
| BookController | 书籍相关 API（列表、搜索、详情、章节等） |
| BookSourceController | 书源管理 API |
| ReplaceRuleController | 替换规则 API |
| RssSourceController | RSS 源管理 API |

### 7.2 其他组件

| 类名 | 职责 |
|------|------|
| ReaderProvider | ContentProvider，对外提供书籍数据 |
| ReturnData | Web API 返回数据封装 |
| ShortCuts | 快捷方式管理 |

---

## 8. 工具类/帮助类

### 8.1 HTTP 工具 (`help/http/`)

| 类名 | 职责 |
|------|------|
| HttpHelper | HTTP 请求辅助，封装 GET/POST 等操作 |
| CookieManager | Cookie 管理器 |
| CookieStore | Cookie 持久化存储 |
| Cronet | Chromium 网络栈封装 |
| SSLHelper | SSL 证书管理 |
| StrResponse | 字符串响应封装 |

### 8.2 配置管理 (`help/config/`)

| 类名 | 职责 |
|------|------|
| AppConfig | 应用全局配置 |
| ReadBookConfig | 阅读配置（字体、字号、行距等） |
| ReadTipConfig | 阅读提示配置 |
| ThemeConfig | 主题配置 |
| SourceConfig | 书源配置 |
| LocalConfig | 本地配置 |

### 8.3 书源辅助 (`help/source/`)

| 类名 | 职责 |
|------|------|
| SourceHelp | 书源辅助工具 |
| SourceVerificationHelp | 书源校验辅助 |
| BookSourceExtensions | 书源扩展函数 |
| RssSourceExtensions | RSS源扩展函数 |

### 8.4 存储与备份 (`help/storage/`)

| 类名 | 职责 |
|------|------|
| Backup | 数据备份 |
| BackupAES | AES 加密备份 |
| BackupConfig | 备份配置 |
| Restore | 数据恢复 |
| ImportOldData | 旧版本数据导入 |

### 8.5 协程工具 (`help/coroutine/`)

| 类名 | 职责 |
|------|------|
| Coroutine | 协程工具 |
| CompositeCoroutine | 组合协程管理 |
| CoroutineContainer | 协程容器 |

### 8.6 加密工具 (`help/crypto/`)

| 类名 | 职责 |
|------|------|
| AsymmetricCrypto | 非对称加密 |
| Sign | 数字签名 |
| SymmetricCryptoAndroid | Android 对称加密 |

### 8.7 其他帮助类

| 目录/类名 | 职责 |
|----------|------|
| `help/exoplayer/` | ExoPlayer 辅助（ExoPlayerHelper, InputStreamDataSource） |
| `help/glide/` | 图片加载辅助（ImageLoader, GlideHeaders, BlurTransformation） |
| `help/rhino/` | Rhino JS 引擎辅助（NativeBaseSource） |
| `help/update/` | 版本更新（AppUpdate, AppUpdateGitHub, AppReleaseInfo） |
| `help/book/` | 书籍辅助（BookContent, BookHelp, ContentHelp, ContentProcessor） |
| AppWebDav.kt | WebDAV 客户端 |
| CacheManager.kt | 缓存管理器 |
| DefaultData.kt | 默认数据初始化 |
| TTS.kt | TTS 引擎封装 |

---

## 9. 核心业务流程

### 9.1 书源规则解析流程

```
用户搜索/浏览
    │
    ▼
AnalyzeRule（规则引擎主类）
    │
    ├── AnalyzeUrl（URL 构建 + HTTP 请求）
    │       │
    │       ├── HttpHelper（OkHttp/Cronet 网络请求）
    │       │       │
    │       │       ├── CookieManager（Cookie 管理）
    │       │       ├── SSLHelper（SSL 证书处理）
    │       │       └── Cronet（Chromium 网络栈）
    │       │
    │       └── 返回 HTML/JSON 字符串
    │
    ├── 根据规则类型选择解析方式：
    │   ├── AnalyzeByXPath（XPath 解析）
    │   ├── AnalyzeByJSoup（JSoup CSS 选择器解析）
    │   ├── AnalyzeByJsonPath（JsonPath 解析）
    │   └── AnalyzeByRegex（正则表达式解析）
    │
    └── 返回解析结果
```

#### 六大规则执行流程

```
SearchRule（搜索规则）
    └── 构建搜索 URL → 请求 → 解析搜索结果列表 → 返回 Book 列表

ExploreRule（发现规则）
    └── 构建发现 URL → 请求 → 解析分类/排行 → 返回 Book 列表

BookInfoRule（书籍信息规则）
    └── 构建详情 URL → 请求 → 解析书名/作者/封面/简介 → 返回 BookInfo

TocRule（目录规则）
    └── 构建目录 URL → 请求 → 解析章节列表 → 返回 BookChapter 列表

ContentRule（正文规则）
    └── 构建章节 URL → 请求 → 解析正文内容 → 返回 BookContent

ReviewRule（评论规则）
    └── 构建评论 URL → 请求 → 解析评论列表 → 返回评论数据
```

### 9.2 阅读渲染流程

```
ReadBook（阅读核心）
    │
    ├── 管理阅读状态
    │   ├── 当前书籍
    │   ├── 当前章节
    │   ├── 当前页面
    │   └── 阅读进度
    │
    ├── 章节加载
    │   ├── 本地缓存检查
    │   ├── 网络请求加载
    │   └── ContentProcessor 内容处理
    │       ├── 替换规则应用
    │       └── 内容净化
    │
    ├── 页面渲染
    │   ├── TextPageFactory（文本排版 + 页面分割）
    │   │   ├── 字体/字号/行距计算
    │   │   ├── 段落分割
    │   │   └── 页面边界计算
    │   │
    │   └── PageDelegate（翻页控制）
    │       ├── CoverPageDelegate（覆盖模式）
    │       ├── SimulationPageDelegate（仿真模式）
    │       ├── ScrollPageDelegate（滚动模式）
    │       └── SlidePageDelegate（滑动模式）
    │
    └── 用户交互
        ├── 点击翻页
        ├── 滑动翻页
        ├── 音量键翻页
        └── 自动翻页（定时器）
```

### 9.3 缓存下载流程

```
用户触发缓存下载
    │
    ▼
CacheBook（管理下载任务队列）
    │
    ├── 下载模式选择
    │   ├── 当前章节
    │   ├── 后续章节（指定数量）
    │   ├── 整本书
    │   └── 范围缓存（指定章节范围）
    │
    ├── 任务队列构建
    │   └── 生成章节下载任务列表
    │
    ▼
CacheBookService（前台服务）
    │
    ├── 通知栏显示下载进度
    ├── 多线程并发下载
    │   ├── 线程池管理
    │   ├── 并发数量控制
    │   └── 失败重试
    │
    ├── 章节内容获取
    │   ├── ContentRule 规则解析
    │   ├── HTTP 请求
    │   └── 内容处理（替换规则）
    │
    └── 存储到本地数据库
        └── BookChapter 实体更新
```

### 9.4 音频播放流程

```
用户触发朗读/音频播放
    │
    ├── ReadAloud（朗读控制）
    │   │
    │   ├── 获取朗读内容
    │   │   └── 当前章节/段落文本
    │   │
    │   └── 分发到朗读服务
    │       ├── TTSReadAloudService（系统 TTS）
    │       │   └── Android TextToSpeech API
    │       │
    │       └── HttpReadAloudService（在线 TTS）
    │           └── HTTP TTS 引擎（如Edge TTS）
    │
    └── AudioPlay（音频文件播放）
        │
        └── AudioPlayService（前台服务）
            ├── ExoPlayer（播放引擎）
            ├── 通知栏控制
            ├── MediaSession（蓝牙/线控）
            └── 播放列表管理
```

### 9.5 Web 服务流程

```
WebService 启动
    │
    ├── NanoHTTPD 服务器
    │   ├── 端口监听（默认 1122）
    │   ├── 静态文件服务（Web前端资源）
    │   └── REST API 路由
    │       ├── /api/books（书籍列表）
    │       ├── /api/chapters（章节列表）
    │       ├── /api/content（章节正文）
    │       └── /api/sources（书源管理）
    │
    ├── WebSocket 实时通信
    │   ├── 阅读进度同步
    │   └── 实时通知
    │
    └── Vue.js 前端
        ├── Web 书架
        ├── 源编辑器
        └── 文件上传（书籍导入）
```

### 9.6 数据同步流程

```
数据备份/恢复
    │
    ├── 备份（Backup）
    │   ├── BackupAES（加密备份）
    │   │   └── AES 加密 → 导出文件
    │   └── BackupConfig（配置备份）
    │       └── 书源/规则/配置 → 导出文件
    │
    ├── 恢复（Restore）
    │   ├── 解密备份文件
    │   ├── 解析数据
    │   └── 导入到数据库
    │
    └── WebDAV 同步（RemoteBookWebDav）
        ├── Server 表存储服务器配置
        ├── 上传备份文件到 WebDAV
        └── 从 WebDAV 下载备份文件
```

---

## 10. 关键设计模式

### 10.1 MVVM 架构

```
View (Activity/Fragment)
    ↕ 数据绑定（LiveData/Flow）
ViewModel (BaseViewModel)
    ↕ 业务调用
Repository / Model
    ↕ 数据访问
DAO (Room)
    ↕ SQL
SQLite Database
```

- **View**: `Fragment` / `Activity`，负责 UI 展示和用户交互
- **ViewModel**: 管理 UI 状态，处理业务逻辑调用
- **Model**: 业务逻辑层，包含规则引擎、阅读核心等
- **Repository**: 数据仓库层，封装数据访问逻辑

### 10.2 策略模式

**翻页策略**（4种翻页方式）:
```
PageDelegate（接口/抽象）
    ├── CoverPageDelegate（覆盖翻页）
    ├── SimulationPageDelegate（仿真翻页）
    ├── ScrollPageDelegate（滚动翻页）
    └── SlidePageDelegate（滑动翻页）
```

**解析策略**（4种解析方式）:
```
AnalyzeRule（上下文）
    ├── AnalyzeByXPath（XPath 解析策略）
    ├── AnalyzeByJSoup（JSoup 解析策略）
    ├── AnalyzeByJsonPath（JsonPath 解析策略）
    └── AnalyzeByRegex（Regex 解析策略）
```

### 10.3 观察者模式

使用 **EventBus** 事件总线实现组件间解耦通信：

- 阅读进度变更事件
- 书籍更新事件
- 书源变更事件
- 网络状态变化事件
- 缓存完成事件

### 10.4 工厂模式

- **PageFactory**: 页面工厂，创建不同类型的页面对象
- **TextPageFactory**: 文本页面工厂，负责文本排版和页面分割

### 10.5 适配器模式

- **RecyclerAdapter 基类**: 提供通用的 RecyclerView 适配器功能
- 各类列表适配器继承基类，如 `BookSourceAdapter`, `ChapterListAdapter` 等

### 10.6 单例模式

- **appDb**: Room 数据库实例（单例）
- **AppConfig**: 应用配置（单例）

### 10.7 模板方法模式

- **BaseActivity**: 定义 Activity 生命周期模板方法
- **BaseFragment**: 定义 Fragment 生命周期模板方法
- **BaseViewModel**: 定义 ViewModel 通用行为模板
- **BaseReadAloudService**: 定义朗读服务通用流程模板

---

## 11. 数据库设计

### 11.1 数据库概述

| 属性 | 值 |
|------|-----|
| 数据库类型 | Room (SQLite) |
| 当前版本 | 79 |
| 自动迁移 | 从 v43 到 v79 |
| 表数量 | 23张表 + 1个视图 |
| 实体数量 | 23个 |

### 11.2 核心表设计

#### Book（书籍表）
| 字段 | 类型 | 说明 |
|------|------|------|
| bookUrl | TEXT (PK) | 详情页URL（本地书源存储完整文件路径） |
| name | TEXT | 书名 |
| author | TEXT | 作者 |
| coverUrl | TEXT | 封面URL |
| kind | TEXT | 分类信息 |
| origin | TEXT | 书源URL |
| intro | TEXT | 简介 |
| wordCount | TEXT | 字数 |
| type | INTEGER | 类型(0文本/1音频/2图片/3文件) |
| tocUrl | TEXT | 目录页URL |
| latestChapterTitle | TEXT | 最新章节标题 |
| durChapterTitle | TEXT | 当前阅读章节 |
| durChapterIndex | INTEGER | 当前章节索引 |
| durChapterPos | INTEGER | 当前章节内位置 |
| group | INTEGER | 分组ID |
| order | INTEGER | 排序 |
| rating | INTEGER | 评分(0-5) |
| tags | INTEGER | 标签(位掩码) |
| canUpdate | INTEGER | 是否可更新 |
| readConfig | TEXT | 阅读配置(JSON) |
| variable | TEXT | 变量(JSON) |

> 索引: `(name, author)` UNIQUE, `(type)`

#### BookChapter（章节表）
| 字段 | 类型 | 说明 |
|------|------|------|
| url | TEXT (PK) | 章节地址 |
| title | TEXT | 章节标题 |
| bookUrl | TEXT (PK, FK) | 所属书籍URL |
| index | INTEGER | 章节序号 |
| isVolume | INTEGER | 是否卷名 |
| isVip | INTEGER | 是否VIP |
| resourceUrl | TEXT | 音频真实URL |
| tag | TEXT | 更新时间/附加信息 |
| wordCount | TEXT | 本章节字数 |
| start | INTEGER | 章节起始位置(EPUB) |
| end | INTEGER | 章节终止位置(EPUB) |
| variable | TEXT | 变量(JSON) |

> 复合主键: `(url, bookUrl)`，外键: `bookUrl` → `Book.bookUrl`，级联删除

#### BookSource（书源表）
| 字段 | 类型 | 说明 |
|------|------|------|
| bookSourceUrl | TEXT (PK) | 书源URL |
| bookSourceName | TEXT | 书源名称 |
| bookSourceGroup | TEXT | 书源分组 |
| bookSourceType | INTEGER | 类型(0文本/1音频/2图片/3文件) |
| bookUrlPattern | TEXT | 详情页URL正则 |
| searchUrl | TEXT | 搜索URL |
| ruleSearch | TEXT | 搜索规则(JSON) |
| ruleBookInfo | TEXT | 书籍信息规则(JSON) |
| ruleToc | TEXT | 目录规则(JSON) |
| ruleContent | TEXT | 正文规则(JSON) |
| ruleExplore | TEXT | 发现规则(JSON) |
| enabled | INTEGER | 是否启用 |
| enabledExplore | INTEGER | 是否启用发现 |
| weight | INTEGER | 智能排序权重 |

#### BookTag（标签表）
| 字段 | 类型 | 说明 |
|------|------|------|
| tagId | INTEGER (PK) | 标签ID（2的幂次，位掩码设计） |
| name | TEXT | 标签名称 |
| order | INTEGER | 排序 |

> **位掩码设计**: `tagId` 为 2 的幂次（1, 2, 4, 8, ...），Book.tags 字段存储选中标签ID的按位OR结果，支持最多64个标签。

#### ReplaceRule（替换规则表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER (PK) | 规则ID |
| name | TEXT | 规则名称 |
| pattern | TEXT | 匹配模式 |
| replacement | TEXT | 替换内容 |
| isEnabled | INTEGER | 是否启用 |
| isRegex | INTEGER | 是否正则 |
| scope | TEXT | 作用范围(URL正则) |
| timeoutMillisecond | INTEGER | 超时时间(ms) |
| order | INTEGER | 排序 |

#### RssSource（RSS源表）
| 字段 | 类型 | 说明 |
|------|------|------|
| sourceUrl | TEXT (PK) | RSS源URL |
| sourceName | TEXT | RSS源名称 |
| sourceGroup | TEXT | 分组 |
| articleStyle | INTEGER | 列表样式(0列表/1卡片/2图文) |
| ruleArticles | TEXT | 文章解析规则 |
| ruleContent | TEXT | 正文规则 |
| enabled | INTEGER | 是否启用 |

#### Server（服务器配置表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER (PK) | 服务器ID |
| name | TEXT | 服务器名称 |
| type | TEXT | 类型(WEBDAV) |
| config | TEXT | 配置(JSON，含url/username/password) |
| sortNumber | INTEGER | 排序编号 |

### 11.3 数据库迁移

`DatabaseMigrations.kt` 管理 Room 数据库的版本迁移，支持从 v43 到 v79 的 AutoMigration 自动迁移策略。

---

## 12. 第三方依赖

### 12.1 核心依赖

| 依赖 | 版本/说明 | 用途 |
|------|----------|------|
| OkHttp | HTTP 客户端 | 网络请求 |
| Cronet | Chromium 网络栈 | 高性能网络请求 |
| Jsoup | HTML 解析器 | HTML 内容解析 |
| JsoupXpath | Jsoup XPath 扩展 | XPath 查询 |
| json-path | JSON 路径查询 | JSON 数据提取 |
| Rhino (Android) | Mozilla JS 引擎 | JavaScript 脚本执行 |
| Room | Android 官方 ORM | 数据库操作 |
| Glide | 图片加载库 | 封面和图片加载 |
| ExoPlayer (Media3) | Google 媒体播放器 | 音频播放 |
| Markwon | Markdown 渲染 | Markdown 显示 |
| epublib-core | EPUB 解析库 | EPUB 电子书解析 |
| HanLP | 中文 NLP 库 | 中文分词处理 |
| NanoHTTPD | 轻量级 HTTP 服务器 | 内置 Web 服务 |
| WebSocket | WebSocket 协议 | 实时通信 |
| bga-qrcode-zxing | 二维码库 | 扫码和生成 |

### 12.2 前端依赖

| 依赖 | 用途 |
|------|------|
| Vue.js | Web 前端框架 |
| Element UI / 自定义组件 | UI 组件库 |

### 12.3 第三方库封装 (`lib/`)

| 子目录 | 封装的库 | 用途 |
|--------|---------|------|
| aliyun/ | 阿里云 SDK | 云存储相关 |
| cronet/ | Cronet | 网络栈封装 |
| dialogs/ | 对话框库 | 对话框封装 |
| icu4j/ | ICU4J | 字符集检测 |
| mobi/ | MOBI 解析库 | MOBI 格式解析 |
| permission/ | 权限管理库 | 运行时权限 |
| prefs/ | SharedPreferences | 偏好设置封装 |
| theme/ | 主题引擎 | 主题切换 |
| webdav/ | WebDAV 客户端 | WebDAV 协议 |

---

## 13. 附录

### 13.1 广播接收器

| 类名 | 用途 |
|------|------|
| MediaButtonReceiver | 媒体按钮事件（耳机/蓝牙控制） |
| NetworkChangedListener | 网络状态变化监听 |
| SharedReceiverActivity | 共享接收 Activity |
| TimeBatteryReceiver | 时间和电量广播接收 |

### 13.2 常量定义 (`constant/`)

| 文件 | 内容 |
|------|------|
| AppConst.kt | 应用常量（包名、路径等） |
| AppPattern.kt | 正则表达式模式 |
| BookType.kt | 书籍类型枚举 |
| BookSourceType.kt | 书源类型枚举 |
| EventBus.kt | 事件定义 |
| IntentAction.kt | Intent Action 常量 |
| PageAnim.kt | 翻页动画类型 |
| PreferKey.kt | SharedPreferences Key |
| SourceType.kt | 源类型枚举 |
| Status.kt | 状态常量 |
| Theme.kt | 主题常量 |
| NotificationId.kt | 通知 ID 常量 |
| AppLog.kt | 日志类型 |

### 13.3 自定义异常 (`exception/`)

| 异常类 | 用途 |
|--------|------|
| NoStackTraceException | 无堆栈异常（性能优化） |
| ConcurrentException | 并发异常 |
| 其他自定义异常 | 业务异常处理 |

### 13.4 基础类 (`base/`)

| 类名 | 职责 |
|------|------|
| BaseActivity | Activity 基类 |
| BaseFragment | Fragment 基类 |
| BaseViewModel | ViewModel 基类 |
| VMBaseActivity | 带 ViewModel 绑定的 Activity 基类 |
| VMBaseFragment | 带 ViewModel 绑定的 Fragment 基类 |
| BaseAdapter | RecyclerView 适配器基类 |

### 13.5 国际化支持

应用支持以下语言：
- 简体中文 (zh)
- 繁體中文 (zh-rTW, zh-rHK)
- 英语 (en, 默认)
- 西班牙语 (es-rES)
- 日语 (ja-rJP)
- 葡萄牙语（巴西）(pt-rBR)
- 越南语 (vi)

---

> **文档版本**: 1.0
> **生成日期**: 2026-07-30
> **基于代码扫描自动生成**