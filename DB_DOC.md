# Legado 数据库文档

## 1. 数据库概述

| 属性 | 值 |
|------|-----|
| 数据库名称 | `legado.db` |
| 数据库类型 | SQLite（基于 Android Room 持久化框架） |
| 当前版本 | **79** |
| 字符集 | UTF-8 |
| 表数量 | 23 张表 + 1 个视图 |
| 实体数量 | 23 个（含 21 个 Room `@Entity` 注解类 + 1 个 `@DatabaseView` 注解类） |
| 迁移策略 | `fallbackToDestructiveMigration()` + 手动迁移（v10~v43）+ AutoMigration（v43~v79） |
| 主要语言 | 中文（数据库 Collation 设置为 `Locale.CHINESE`） |

### 1.1 数据库设计说明

本数据库是 Legado（阅读）Android 应用的本地数据库，使用 Room 框架进行 ORM 映射。数据库主要存储以下业务数据：

- **书籍管理**：`books`（书架书籍）、`chapters`（章节）、`book_groups`（分组）、`book_tags`（标签）
- **书源管理**：`book_sources`（书源）、`searchBooks`（搜索缓存）、`replace_rules`（替换规则）、`txtTocRules`（TXT目录规则）
- **RSS 订阅**：`rssSources`（RSS源）、`rssArticles`（RSS文章）、`rssStars`（RSS收藏）、`rssReadRecords`（RSS阅读记录）
- **搜索**：`search_keywords`（搜索关键词）、`book_search_keywords`（书籍搜索关键词）
- **辅助功能**：`bookmarks`（书签）、`readRecord`（阅读记录）、`httpTTS`（在线朗读）、`dictRules`（词典规则）、`keyboardAssists`（键盘辅助）
- **基础设施**：`cookies`（Cookie）、`caches`（缓存）、`ruleSubs`（规则订阅）、`servers`（服务器）

### 1.2 版本历史

数据库从 v10 起经过多次手动迁移（`Migration`），v43 开始启用 Room 的 `AutoMigration` 机制，逐步演进至当前 v79。

---

## 2. 实体关系图（ER 图）

```mermaid
erDiagram
    books ||--o{ chapters : "bookUrl → bookUrl (CASCADE)"
    books ||--o{ book_groups : "group → groupId"
    books }o--o{ book_tags : "tags(bitset) → tagId"
    book_sources ||--o{ searchBooks : "bookSourceUrl → origin (CASCADE)"
    book_sources ||--o{ books : "bookSourceUrl → origin"
    rssSources ||--o{ rssArticles : "逻辑关联"
    rssSources ||--o{ rssStars : "逻辑关联"
    rssArticles ||--o{ rssStars : "origin+link"
    rssArticles ||--o{ rssReadRecords : "逻辑关联"

    books {
        String bookUrl PK "详情页URL"
        String name "书名"
        String author "作者"
        String origin "书源URL"
        Long group "分组ID"
        Long tags "标签位掩码"
        Int type "类型"
        Int durChapterIndex "当前章节索引"
        String durChapterTitle "当前章节名称"
        String coverUrl "封面URL"
        String intro "简介"
        String latestChapterTitle "最新章节"
        Long latestChapterTime "更新时间"
        Int totalChapterNum "总章节数"
        Boolean canUpdate "是否允许更新"
        Int order "排序"
        Int rating "评分"
        String readConfig "阅读设置JSON"
        String variable "自定义变量JSON"
        Long syncTime "同步时间戳"
    }

    chapters {
        String url PK "章节地址"
        String bookUrl PK_FK "书籍地址"
        String title "章节标题"
        Int index "章节序号"
        Boolean isVolume "是否卷名"
        Boolean isVip "是否VIP"
        Boolean isPay "是否已购买"
        String resourceUrl "音频URL"
        String tag "附加信息"
        String wordCount "字数"
        Long start "EPUB起始位置"
        Long end "EPUB终止位置"
        String startFragmentId "EPUB起始fragmentId"
        String endFragmentId "EPUB终止fragmentId"
        String variable "变量JSON"
    }

    book_groups {
        Long groupId PK "分组ID"
        String groupName "分组名称"
        String cover "封面URL"
        Int order "排序"
        Boolean enableRefresh "启用刷新"
        Boolean show "是否显示"
        Int bookSort "书籍排序"
    }

    book_tags {
        Long tagId PK "标签ID(2的幂次)"
        String name "标签名称"
        Int order "排序"
    }

    book_sources {
        String bookSourceUrl PK "书源地址"
        String bookSourceName "书源名称"
        String bookSourceGroup "分组"
        Int bookSourceType "类型"
        String bookUrlPattern "详情页URL正则"
        Int customOrder "手动排序"
        Boolean enabled "是否启用"
        Boolean enabledExplore "启用发现"
        String jsLib "JS库"
        Boolean enabledCookieJar "Cookie管理"
        String concurrentRate "并发率"
        String header "请求头JSON"
        String loginUrl "登录地址"
        String loginUi "登录UI JSON"
        String loginCheckJs "登录检测JS"
        String coverDecodeJs "封面解密JS"
        String bookSourceComment "注释"
        String variableComment "变量说明"
        Long lastUpdateTime "最后更新时间"
        Long respondTime "响应时间"
        Int weight "权重"
        String exploreUrl "发现URL"
        String exploreScreen "发现筛选规则"
        String ruleExplore "发现规则JSON"
        String searchUrl "搜索URL"
        String ruleSearch "搜索规则JSON"
        String ruleBookInfo "书籍信息规则JSON"
        String ruleToc "目录规则JSON"
        String ruleContent "正文规则JSON"
        String ruleReview "段评规则JSON"
    }

    searchBooks {
        String bookUrl PK "详情页URL"
        String origin FK "书源URL"
        String originName "书源名称"
        String name "书名"
        String author "作者"
        String kind "分类"
        String coverUrl "封面URL"
        String intro "简介"
        String latestChapterTitle "最新章节"
        String tocUrl "目录URL"
        Long time "搜索时间"
        Int originOrder "书源排序"
        Int chapterWordCount "章节字数"
        Int respondTime "响应时间"
    }

    rssSources {
        String sourceUrl PK "订阅源地址"
        String sourceName "名称"
        String sourceIcon "图标URL"
        String sourceGroup "分组"
        String sourceComment "注释"
        Boolean enabled "是否启用"
        String variableComment "变量说明"
        String jsLib "JS库"
        Boolean enabledCookieJar "Cookie管理"
        String concurrentRate "并发率"
        String header "请求头"
        String loginUrl "登录地址"
        String loginUi "登录UI"
        String loginCheckJs "登录检测JS"
        String coverDecodeJs "封面解密JS"
        String sortUrl "分类URL"
        Boolean singleUrl "是否单URL源"
        Int articleStyle "列表样式"
        String ruleArticles "列表规则"
        String ruleNextPage "下一页规则"
        String ruleTitle "标题规则"
        String rulePubDate "发布日期规则"
        String ruleDescription "描述规则"
        String ruleImage "图片规则"
        String ruleLink "链接规则"
        String ruleContent "正文规则"
        String contentWhitelist "白名单"
        String contentBlacklist "黑名单"
        String shouldOverrideUrlLoading "URL拦截JS"
        String style "WebView样式CSS"
        Boolean enableJs "启用JS"
        Boolean loadWithBaseUrl "使用BaseURL"
        String injectJs "注入JS"
        Long lastUpdateTime "最后更新时间"
        Int customOrder "自定义排序"
    }

    rssArticles {
        String origin PK "订阅源URL"
        String link PK "文章链接"
        String sort "分类"
        String title "标题"
        Long order "排序序号"
        String pubDate "发布日期"
        String description "描述"
        String content "正文"
        String image "图片URL"
        String group "分组"
        Boolean read "是否已读"
        String variable "变量JSON"
    }

    rssStars {
        String origin PK "订阅源URL"
        String link PK "文章链接"
        String sort "分类"
        String title "标题"
        Long starTime "收藏时间"
        String pubDate "发布日期"
        String description "描述"
        String content "正文"
        String image "图片URL"
        String group "分组"
        String variable "变量JSON"
    }

    rssReadRecords {
        String record PK "记录标识"
        String title "标题"
        Long readTime "阅读时间"
        Boolean read "是否已读"
    }

    bookmarks {
        Long time PK "创建时间"
        String bookName "书名"
        String bookAuthor "作者"
        Int chapterIndex "章节索引"
        Int chapterPos "章节内位置"
        String chapterName "章节名称"
        String bookText "选中文本"
        String content "备注"
    }

    readRecord {
        String deviceId PK "设备ID"
        String bookName PK "书名"
        Long readTime "阅读时长ms"
        Long lastRead "最后阅读时间"
    }

    replace_rules {
        Long id PK_AUTO "规则ID"
        String name "规则名称"
        String group "分组"
        String pattern "匹配模式"
        String replacement "替换内容"
        String scope "作用范围URL正则"
        Boolean scopeTitle "作用于标题"
        Boolean scopeContent "作用于正文"
        String excludeScope "排除范围URL正则"
        Boolean isEnabled "是否启用"
        Boolean isRegex "是否正则"
        Long timeoutMillisecond "超时时间ms"
        Int order "排序"
    }

    search_keywords {
        String word PK "关键词"
        Int usage "使用次数"
        Long lastUseTime "最后使用时间"
    }

    book_search_keywords {
        String word PK "搜索关键词"
        Int usage "使用次数"
        Long lastUseTime "最后使用时间"
    }

    cookies {
        String url PK "URL"
        String cookie "Cookie值"
    }

    txtTocRules {
        Long id PK "规则ID"
        String name "名称"
        String rule "规则正则"
        String example "示例"
        Int serialNumber "序列号"
        Boolean enable "是否启用"
    }

    httpTTS {
        Long id PK "引擎ID"
        String name "名称"
        String url "合成接口地址"
        String contentType "响应格式"
        String concurrentRate "并发率"
        String loginUrl "登录地址"
        String loginUi "登录UI"
        String header "请求头"
        String jsLib "JS库"
        Boolean enabledCookieJar "Cookie管理"
        String loginCheckJs "登录检测JS"
        Long lastUpdateTime "最后更新时间"
    }

    caches {
        String key PK "缓存键"
        String value "缓存值"
        Long deadline "过期时间戳"
    }

    ruleSubs {
        Long id PK "订阅ID"
        String name "名称"
        String url "订阅URL"
        Int type "类型"
        Int customOrder "排序"
        Boolean autoUpdate "自动更新"
        Long update "更新时间"
    }

    dictRules {
        String name PK "词典名称"
        String urlRule "查词接口规则"
        String showRule "结果解析规则"
        Boolean enabled "是否启用"
        Int sortNumber "排序"
    }

    keyboardAssists {
        Int type PK "类型"
        String key PK "键名"
        String value "键值"
        Int serialNo "序列号"
    }

    servers {
        Long id PK "服务器ID"
        String name "名称"
        TYPE type "类型(WEBDAV)"
        String config "配置JSON"
        Int sortNumber "排序"
    }
```

---

## 3. 表结构详细定义

### 3.1 books（书籍表）

**表名：** `books`  
**说明：** 存储书架中所有书籍的核心信息，包括阅读进度、封面、简介、分组等。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `bookUrl` | TEXT | `""` | **PRIMARY KEY** | 详情页URL（本地书源存储完整文件路径） |
| `tocUrl` | TEXT | `""` | NOT NULL | 目录页URL（toc = table of contents） |
| `origin` | TEXT | `BookType.localTag` | NOT NULL | 书源URL（默认 `BookType.localTag`，即本地书源标签） |
| `originName` | TEXT | `""` | NOT NULL | 书源名称或本地书籍文件名 |
| `name` | TEXT | `""` | NOT NULL | 书籍名称（书源获取） |
| `author` | TEXT | `""` | NOT NULL | 作者名称（书源获取） |
| `kind` | TEXT | NULL | 可空 | 分类信息（书源获取，逗号/换行分隔） |
| `customTag` | TEXT | NULL | 可空 | 用户自定义分类 |
| `coverUrl` | TEXT | NULL | 可空 | 封面URL（书源获取） |
| `customCoverUrl` | TEXT | NULL | 可空 | 用户自定义封面URL |
| `intro` | TEXT | NULL | 可空 | 简介内容（书源获取） |
| `customIntro` | TEXT | NULL | 可空 | 用户自定义简介 |
| `charset` | TEXT | NULL | 可空 | 自定义字符集名称（仅适用于本地书籍） |
| `type` | INTEGER | `0` | NOT NULL | 类型：0=文本, 1=音频, 2=图片, 3=文件（详见 BookType） |
| `group` | INTEGER | `0` | NOT NULL | 自定义分组索引号 |
| `latestChapterTitle` | TEXT | NULL | 可空 | 最新章节标题 |
| `latestChapterTime` | INTEGER | `0` | NOT NULL | 最新章节标题更新时间（毫秒时间戳） |
| `lastCheckTime` | INTEGER | `0` | NOT NULL | 最近一次更新书籍信息的时间（毫秒时间戳） |
| `lastCheckCount` | INTEGER | `0` | NOT NULL | 最近一次发现新章节的数量 |
| `totalChapterNum` | INTEGER | `0` | NOT NULL | 书籍目录总数 |
| `durChapterTitle` | TEXT | NULL | 可空 | 当前阅读章节名称 |
| `durChapterIndex` | INTEGER | `0` | NOT NULL | 当前阅读章节索引 |
| `durChapterPos` | INTEGER | `0` | NOT NULL | 当前阅读进度（首行字符的索引位置） |
| `durChapterTime` | INTEGER | `0` | NOT NULL | 最近一次阅读书籍的时间（打开正文的时间） |
| `wordCount` | TEXT | NULL | 可空 | 字数 |
| `canUpdate` | INTEGER | `1` | NOT NULL | 刷新书架时是否更新书籍信息（1=是, 0=否） |
| `order` | INTEGER | `0` | NOT NULL | 手动排序 |
| `originOrder` | INTEGER | `0` | NOT NULL | 书源排序 |
| `variable` | TEXT | NULL | 可空 | 自定义书籍变量信息（JSON格式，用于书源规则检索） |
| `readConfig` | TEXT | NULL | 可空 | 阅读设置（JSON，存储为 `ReadConfig` 对象） |
| `syncTime` | INTEGER | `0` | NOT NULL | 同步时间戳 |
| `rating` | INTEGER | `0` | NOT NULL | 评分（0-5，默认0） |
| `tags` | INTEGER | `0` | NOT NULL | 用户自定义标签（位掩码，2的幂次组合） |

**索引：**
- `UNIQUE (name, author)` — 书籍名称+作者联合唯一索引，防止重复
- `(type)` — 类型索引，用于按书籍类型筛选

**ReadConfig 内嵌对象（JSON 序列化）：**

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `reverseToc` | Boolean | `false` | 反向目录 |
| `pageAnim` | Int? | `null` | 翻页动画 |
| `reSegment` | Boolean | `false` | 重新分段 |
| `imageStyle` | String? | `null` | 图片样式 |
| `useReplaceRule` | Boolean? | `null` | 正文使用净化替换规则 |
| `delTag` | Long | `0` | 去除标签（位掩码） |
| `ttsEngine` | String? | `null` | TTS引擎 |
| `splitLongChapter` | Boolean | `true` | 拆分长章节 |
| `readSimulating` | Boolean | `false` | 模拟阅读 |
| `startDate` | LocalDate? | `null` | 模拟阅读起始日期 |
| `startChapter` | Int? | `null` | 用户设置的起始章节 |
| `dailyChapters` | Int | `3` | 用户设置的每日更新章节数 |

---

### 3.2 book_groups（分组表）

**表名：** `book_groups`  
**说明：** 书籍分组管理，包含系统预设分组和用户自定义分组。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `groupId` | INTEGER | `0b1` | **PRIMARY KEY** | 分组ID |
| `groupName` | TEXT | `""` | NOT NULL | 分组名称 |
| `cover` | TEXT | NULL | 可空 | 封面URL |
| `order` | INTEGER | `0` | NOT NULL | 排序 |
| `enableRefresh` | INTEGER | `1` | NOT NULL | 启用刷新（1=刷新, 0=不刷新） |
| `show` | INTEGER | `1` | NOT NULL | 是否显示（1=显示, 0=隐藏） |
| `bookSort` | INTEGER | `-1` | NOT NULL | 书籍排序方式（-1=使用全局设置） |

**系统预设分组ID：**

| 常量名 | groupId | 说明 |
|--------|---------|------|
| `IdRoot` | -100 | 根分组 |
| `IdAll` | -1 | 全部 |
| `IdLocal` | -2 | 本地 |
| `IdAudio` | -3 | 音频 |
| `IdNetNone` | -4 | 网络未分组 |
| `IdLocalNone` | -5 | 本地未分组 |
| `IdError` | -11 | 更新失败 |

**说明：** 系统预设分组在数据库首次打开时通过 `onOpen` 回调自动插入（如果不存在）。

---

### 3.3 book_tags（标签表）

**表名：** `book_tags`  
**说明：** 书籍标签管理，标签ID为2的幂次，用于位掩码组合。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `tagId` | INTEGER | `0b1` | **PRIMARY KEY** | 标签ID（2的幂次，如 1, 2, 4, 8, ...） |
| `name` | TEXT | `""` | NOT NULL | 标签名称 |
| `order` | INTEGER | `0` | NOT NULL | 排序 |

**说明：** 书籍的 `tags` 字段通过位掩码组合多个标签，例如 `tags = 3` 表示同时拥有 `tagId=1` 和 `tagId=2` 的标签。

---

### 3.4 book_sources（书源表）

**表名：** `book_sources`  
**说明：** 书源规则配置，定义了从网络获取书籍信息、搜索、发现、目录、正文等的规则。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `bookSourceUrl` | TEXT | `""` | **PRIMARY KEY** | 书源地址（含 http/https 协议） |
| `bookSourceName` | TEXT | `""` | NOT NULL | 书源名称 |
| `bookSourceGroup` | TEXT | NULL | 可空 | 分组（逗号分隔，如 "正版,优质"） |
| `bookSourceType` | INTEGER | `0` | NOT NULL | 类型：0=文本, 1=音频, 2=图片, 3=文件（知轩藏书类） |
| `bookUrlPattern` | TEXT | NULL | 可空 | 详情页URL正则匹配模式 |
| `customOrder` | INTEGER | `0` | NOT NULL | 手动排序编号 |
| `enabled` | INTEGER | `1` | NOT NULL | 是否启用（1=启用, 0=禁用） |
| `enabledExplore` | INTEGER | `1` | NOT NULL | 启用发现（1=启用, 0=禁用） |
| `jsLib` | TEXT | NULL | 可空 | JS库（用于规则中引用JS代码） |
| `enabledCookieJar` | INTEGER | `0` | NULLABLE | 启用OkHttp CookieJar自动保存每次请求的Cookie |
| `concurrentRate` | TEXT | NULL | 可空 | 并发率 |
| `header` | TEXT | NULL | 可空 | 请求头（JSON格式） |
| `loginUrl` | TEXT | NULL | 可空 | 登录地址 |
| `loginUi` | TEXT | NULL | 可空 | 登录UI配置（JSON格式） |
| `loginCheckJs` | TEXT | NULL | 可空 | 登录检测JS代码 |
| `coverDecodeJs` | TEXT | NULL | 可空 | 封面解密JS代码 |
| `bookSourceComment` | TEXT | NULL | 可空 | 注释 |
| `variableComment` | TEXT | NULL | 可空 | 自定义变量说明 |
| `lastUpdateTime` | INTEGER | `0` | NOT NULL | 最后更新时间，用于排序 |
| `respondTime` | INTEGER | `180000` | NOT NULL | 响应时间（毫秒），用于排序 |
| `weight` | INTEGER | `0` | NOT NULL | 智能排序的权重 |
| `exploreUrl` | TEXT | NULL | 可空 | 发现URL |
| `exploreScreen` | TEXT | NULL | 可空 | 发现筛选规则 |
| `ruleExplore` | TEXT | NULL | 可空 | 发现规则（JSON，映射为 `ExploreRule` 对象） |
| `searchUrl` | TEXT | NULL | 可空 | 搜索URL |
| `ruleSearch` | TEXT | NULL | 可空 | 搜索规则（JSON，映射为 `SearchRule` 对象） |
| `ruleBookInfo` | TEXT | NULL | 可空 | 书籍信息页规则（JSON，映射为 `BookInfoRule` 对象） |
| `ruleToc` | TEXT | NULL | 可空 | 目录页规则（JSON，映射为 `TocRule` 对象） |
| `ruleContent` | TEXT | NULL | 可空 | 正文页规则（JSON，映射为 `ContentRule` 对象） |
| `ruleReview` | TEXT | NULL | 可空 | 段评规则（JSON，映射为 `ReviewRule` 对象） |

**索引：**
- `(bookSourceUrl)` — 书源URL索引

**类型转换器：** `BookSource.Converters` 负责 `ExploreRule`、`SearchRule`、`BookInfoRule`、`TocRule`、`ContentRule`、`ReviewRule` 的 JSON 序列化/反序列化。

---

### 3.5 chapters（章节表）

**表名：** `chapters`  
**说明：** 存储书籍的章节信息，包含目录结构、VIP状态、EPUB位置等。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `url` | TEXT | `""` | **PRIMARY KEY（复合）** | 章节地址 |
| `bookUrl` | TEXT | `""` | **PRIMARY KEY（复合）, FOREIGN KEY** | 书籍地址，关联 `books(bookUrl)` |
| `title` | TEXT | `""` | NOT NULL | 章节标题 |
| `isVolume` | INTEGER | `0` | NOT NULL | 是否为卷名（0=否, 1=是） |
| `baseUrl` | TEXT | `""` | NOT NULL | 基准URL（用于拼接相对URL） |
| `index` | INTEGER | `0` | NOT NULL | 章节序号 |
| `isVip` | INTEGER | `0` | NOT NULL | 是否VIP章节（0=否, 1=是） |
| `isPay` | INTEGER | `0` | NOT NULL | 是否已购买（0=未购买, 1=已购买） |
| `resourceUrl` | TEXT | NULL | 可空 | 音频真实URL |
| `tag` | TEXT | NULL | 可空 | 更新时间或其他章节附加信息 |
| `wordCount` | TEXT | NULL | 可空 | 本章节字数 |
| `start` | INTEGER | NULL | 可空 | 章节起始位置（EPUB） |
| `end` | INTEGER | NULL | 可空 | 章节终止位置（EPUB） |
| `startFragmentId` | TEXT | NULL | 可空 | EPUB书籍当前章节的 fragmentId |
| `endFragmentId` | TEXT | NULL | 可空 | EPUB书籍下一章节的 fragmentId |
| `variable` | TEXT | NULL | 可空 | 变量（JSON格式） |

**复合主键：** `(url, bookUrl)`  
**外键：** `bookUrl` → `books(bookUrl)` ON DELETE **CASCADE**（删除书籍时自动删除章节）  
**索引：**
- `(bookUrl)` — 按书籍查询章节
- `UNIQUE (bookUrl, index)` — 书籍+章节序号唯一索引，确保同一书籍的章节序号不重复

---

### 3.6 replace_rules（替换规则表）

**表名：** `replace_rules`  
**说明：** 文本替换规则，用于内容净化，支持正则表达式和超时控制。

| 字段名 | 数据类型 | 列名 | 默认值 | 约束 | 说明 |
|--------|---------|------|--------|------|------|
| `id` | INTEGER | `id` | 自增 | **PRIMARY KEY (autoGenerate)** | 规则ID |
| `name` | TEXT | `name` | `""` | NOT NULL | 规则名称 |
| `group` | TEXT | `group` | NULL | 可空 | 分组 |
| `pattern` | TEXT | `pattern` | `""` | NOT NULL | 匹配模式（正则表达式或普通文本） |
| `replacement` | TEXT | `replacement` | `""` | NOT NULL | 替换内容 |
| `scope` | TEXT | `scope` | NULL | 可空 | 作用范围（URL正则，限定哪些URL生效） |
| `scopeTitle` | INTEGER | `scopeTitle` | `0` | NOT NULL | 作用于标题（1=是, 0=否） |
| `scopeContent` | INTEGER | `scopeContent` | `1` | NOT NULL | 作用于正文（1=是, 0=否） |
| `excludeScope` | TEXT | `excludeScope` | NULL | 可空 | 排除范围（URL正则，排除指定URL） |
| `isEnabled` | INTEGER | `isEnabled` | `1` | NOT NULL | 是否启用（1=启用, 0=禁用） |
| `isRegex` | INTEGER | `isRegex` | `1` | NOT NULL | 是否正则表达式（1=正则, 0=普通文本） |
| `timeoutMillisecond` | INTEGER | `timeoutMillisecond` | `3000` | NOT NULL | 超时时间（毫秒，<=0时使用默认3000ms） |
| `order` | INTEGER | `sortOrder` | `0` | NOT NULL | 排序（列名映射为 `sortOrder`） |

**索引：**
- `(id)` — 规则ID索引

---

### 3.7 searchBooks（搜索书籍缓存表）

**表名：** `searchBooks`  
**说明：** 搜索结果的缓存表，存储搜索到的书籍信息，关联书源。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `bookUrl` | TEXT | `""` | **PRIMARY KEY** | 详情页URL |
| `origin` | TEXT | `""` | NOT NULL, **FOREIGN KEY** | 书源URL，关联 `book_sources(bookSourceUrl)` |
| `originName` | TEXT | `""` | NOT NULL | 书源名称 |
| `type` | INTEGER | `BookType.text` | NOT NULL | 书籍类型 |
| `name` | TEXT | `""` | NOT NULL | 书名 |
| `author` | TEXT | `""` | NOT NULL | 作者 |
| `kind` | TEXT | NULL | 可空 | 分类信息 |
| `coverUrl` | TEXT | NULL | 可空 | 封面URL |
| `intro` | TEXT | NULL | 可空 | 简介 |
| `wordCount` | TEXT | NULL | 可空 | 字数 |
| `latestChapterTitle` | TEXT | NULL | 可空 | 最新章节标题 |
| `tocUrl` | TEXT | `""` | NOT NULL | 目录页URL |
| `time` | INTEGER | `System.currentTimeMillis()` | NOT NULL | 搜索时间（毫秒时间戳） |
| `variable` | TEXT | NULL | 可空 | 自定义变量（JSON） |
| `originOrder` | INTEGER | `0` | NOT NULL | 书源排序 |
| `chapterWordCountText` | TEXT | NULL | 可空 | 章节字数字面文本 |
| `chapterWordCount` | INTEGER | `-1` | NOT NULL | 章节字数（-1表示未知） |
| `respondTime` | INTEGER | `-1` | NOT NULL | 响应时间（毫秒，-1表示未知） |

**外键：** `origin` → `book_sources(bookSourceUrl)` ON DELETE **CASCADE**（删除书源时自动删除缓存）  
**索引：**
- `UNIQUE (bookUrl)` — 书籍URL唯一索引
- `(origin)` — 书源索引

---

### 3.8 search_keywords（搜索关键词表）

**表名：** `search_keywords`  
**说明：** 搜索关键词历史记录，按使用频率和最近使用时间排序。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `word` | TEXT | `""` | **PRIMARY KEY** | 搜索关键词 |
| `usage` | INTEGER | `1` | NOT NULL | 使用次数 |
| `lastUseTime` | INTEGER | `System.currentTimeMillis()` | NOT NULL | 最后一次使用时间（毫秒时间戳） |

**索引：**
- `UNIQUE (word)` — 关键词唯一索引

---

### 3.9 book_search_keywords（书籍搜索关键词表）

**表名：** `book_search_keywords`  
**说明：** 书籍搜索关键词历史记录，与 `search_keywords` 结构相同但独立存储。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `word` | TEXT | `""` | **PRIMARY KEY** | 搜索关键词 |
| `usage` | INTEGER | `1` | NOT NULL | 使用次数 |
| `lastUseTime` | INTEGER | `System.currentTimeMillis()` | NOT NULL | 最后一次使用时间（毫秒时间戳） |

**索引：**
- `UNIQUE (word)` — 关键词唯一索引

---

### 3.10 cookies（Cookie表）

**表名：** `cookies`  
**说明：** 存储各URL的Cookie信息，用于保持登录状态。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `url` | TEXT | `""` | **PRIMARY KEY** | URL |
| `cookie` | TEXT | `""` | NOT NULL | Cookie值 |

**索引：**
- `UNIQUE (url)` — URL唯一索引

---

### 3.11 rssSources（RSS源表）

**表名：** `rssSources`  
**说明：** RSS订阅源规则配置，定义RSS文章列表、内容解析规则。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `sourceUrl` | TEXT | `""` | **PRIMARY KEY** | 订阅源地址 |
| `sourceName` | TEXT | `""` | NOT NULL | 名称 |
| `sourceIcon` | TEXT | `""` | NOT NULL | 图标URL |
| `sourceGroup` | TEXT | NULL | 可空 | 分组（逗号分隔） |
| `sourceComment` | TEXT | NULL | 可空 | 注释 |
| `enabled` | INTEGER | `1` | NOT NULL | 是否启用（1=启用, 0=禁用） |
| `variableComment` | TEXT | NULL | 可空 | 自定义变量说明 |
| `jsLib` | TEXT | NULL | 可空 | JS库 |
| `enabledCookieJar` | INTEGER | `0` | NULLABLE | 启用OkHttp CookieJar自动保存Cookie |
| `concurrentRate` | TEXT | NULL | 可空 | 并发率 |
| `header` | TEXT | NULL | 可空 | 请求头（JSON格式） |
| `loginUrl` | TEXT | NULL | 可空 | 登录地址 |
| `loginUi` | TEXT | NULL | 可空 | 登录UI配置（JSON格式） |
| `loginCheckJs` | TEXT | NULL | 可空 | 登录检测JS代码 |
| `coverDecodeJs` | TEXT | NULL | 可空 | 封面解密JS代码 |
| `sortUrl` | TEXT | NULL | 可空 | 分类URL |
| `singleUrl` | INTEGER | `0` | NOT NULL | 是否单URL源（1=是, 0=否） |
| `articleStyle` | INTEGER | `0` | NOT NULL | 列表样式：0=列表, 1=卡片, 2=图文 |
| `ruleArticles` | TEXT | NULL | 可空 | 列表规则 |
| `ruleNextPage` | TEXT | NULL | 可空 | 下一页规则 |
| `ruleTitle` | TEXT | NULL | 可空 | 标题规则 |
| `rulePubDate` | TEXT | NULL | 可空 | 发布日期规则 |
| `ruleDescription` | TEXT | NULL | 可空 | 描述规则 |
| `ruleImage` | TEXT | NULL | 可空 | 图片规则 |
| `ruleLink` | TEXT | NULL | 可空 | 链接规则 |
| `ruleContent` | TEXT | NULL | 可空 | 正文规则 |
| `contentWhitelist` | TEXT | NULL | 可空 | 正文URL白名单 |
| `contentBlacklist` | TEXT | NULL | 可空 | 正文URL黑名单 |
| `shouldOverrideUrlLoading` | TEXT | NULL | 可空 | URL跳转拦截JS（返回true拦截） |
| `style` | TEXT | NULL | 可空 | WebView样式（CSS） |
| `enableJs` | INTEGER | `1` | NOT NULL | 启用JS（1=启用, 0=禁用） |
| `loadWithBaseUrl` | INTEGER | `1` | NOT NULL | 使用BaseURL加载（1=是, 0=否） |
| `injectJs` | TEXT | NULL | 可空 | 注入JS代码 |
| `lastUpdateTime` | INTEGER | `0` | NOT NULL | 最后更新时间（毫秒时间戳） |
| `customOrder` | INTEGER | `0` | NOT NULL | 自定义排序 |

**索引：**
- `(sourceUrl)` — 订阅源URL索引

---

### 3.12 bookmarks（书签表）

**表名：** `bookmarks`  
**说明：** 书签管理，存储阅读位置标记和选中文本。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `time` | INTEGER | `System.currentTimeMillis()` | **PRIMARY KEY** | 创建时间（毫秒时间戳） |
| `bookName` | TEXT | `""` | NOT NULL | 书名 |
| `bookAuthor` | TEXT | `""` | NOT NULL | 作者 |
| `chapterIndex` | INTEGER | `0` | NOT NULL | 章节索引 |
| `chapterPos` | INTEGER | `0` | NOT NULL | 章节内位置 |
| `chapterName` | TEXT | `""` | NOT NULL | 章节名称 |
| `bookText` | TEXT | `""` | NOT NULL | 选中文本 |
| `content` | TEXT | `""` | NOT NULL | 备注内容 |

**索引：**
- `(bookName, bookAuthor)` — 按书名+作者查询书签

---

### 3.13 rssArticles（RSS文章表）

**表名：** `rssArticles`  
**说明：** 存储RSS订阅获取的文章内容。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `origin` | TEXT | `""` | **PRIMARY KEY（复合）** | 订阅源URL |
| `link` | TEXT | `""` | **PRIMARY KEY（复合）** | 文章链接 |
| `sort` | TEXT | `""` | NOT NULL | 分类 |
| `title` | TEXT | `""` | NOT NULL | 标题 |
| `order` | INTEGER | `0` | NOT NULL | 排序序号 |
| `pubDate` | TEXT | NULL | 可空 | 发布日期 |
| `description` | TEXT | NULL | 可空 | 描述 |
| `content` | TEXT | NULL | 可空 | 正文 |
| `image` | TEXT | NULL | 可空 | 图片URL |
| `group` | TEXT | `"默认分组"` | NOT NULL | 分组 |
| `read` | INTEGER | `0` | NOT NULL | 是否已读（1=已读, 0=未读） |
| `variable` | TEXT | NULL | 可空 | 变量（JSON格式） |

**复合主键：** `(origin, link)`  
**说明：** 实现 `BaseRssArticle` 接口，支持规则变量存储。

---

### 3.14 rssReadRecords（RSS阅读记录表）

**表名：** `rssReadRecords`  
**说明：** 存储RSS文章的阅读记录，用于判断文章是否已读。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `record` | TEXT | — | **PRIMARY KEY** | 记录标识（通常为文章链接的某种标识） |
| `title` | TEXT | NULL | 可空 | 标题 |
| `readTime` | INTEGER | NULL | 可空 | 阅读时间（毫秒时间戳） |
| `read` | INTEGER | `1` | NOT NULL | 是否已读（1=已读, 0=未读） |

---

### 3.15 rssStars（RSS收藏表）

**表名：** `rssStars`  
**说明：** 存储用户收藏的RSS文章。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `origin` | TEXT | `""` | **PRIMARY KEY（复合）** | 订阅源URL |
| `link` | TEXT | `""` | **PRIMARY KEY（复合）** | 文章链接 |
| `sort` | TEXT | `""` | NOT NULL | 分类 |
| `title` | TEXT | `""` | NOT NULL | 标题 |
| `starTime` | INTEGER | `0` | NOT NULL | 收藏时间（毫秒时间戳） |
| `pubDate` | TEXT | NULL | 可空 | 发布日期 |
| `description` | TEXT | NULL | 可空 | 描述 |
| `content` | TEXT | NULL | 可空 | 正文 |
| `image` | TEXT | NULL | 可空 | 图片URL |
| `group` | TEXT | `"默认分组"` | NOT NULL | 分组 |
| `variable` | TEXT | NULL | 可空 | 变量（JSON格式） |

**复合主键：** `(origin, link)`  
**说明：** 实现 `BaseRssArticle` 接口，可与 `RssArticle` 互相转换（`toRssArticle()` / `toStar()`）。

---

### 3.16 txtTocRules（TXT目录规则表）

**表名：** `txtTocRules`  
**说明：** TXT文本文件的目录识别规则，通过正则表达式匹配章节标题。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `id` | INTEGER | `System.currentTimeMillis()` | **PRIMARY KEY** | 规则ID |
| `name` | TEXT | `""` | NOT NULL | 名称 |
| `rule` | TEXT | `""` | NOT NULL | 规则（正则表达式） |
| `example` | TEXT | NULL | 可空 | 示例 |
| `serialNumber` | INTEGER | `-1` | NOT NULL | 序列号 |
| `enable` | INTEGER | `1` | NOT NULL | 是否启用（1=启用, 0=禁用） |

---

### 3.17 readRecord（阅读记录表）

**表名：** `readRecord`  
**说明：** 存储阅读时长统计，按设备+书名分组，用于阅读统计。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `deviceId` | TEXT | `""` | **PRIMARY KEY（复合）** | 设备ID |
| `bookName` | TEXT | `""` | **PRIMARY KEY（复合）** | 书名 |
| `readTime` | INTEGER | `0` | NOT NULL | 阅读时长（毫秒） |
| `lastRead` | INTEGER | `0` | NOT NULL | 最后阅读时间（毫秒时间戳） |

**复合主键：** `(deviceId, bookName)`  
**历史：** 此表历经多次迁移：v15 仅 `(bookName)` 单主键 → v18 改为 `(androidId, bookName)` → v30 改为 `(deviceId, bookName)`。

---

### 3.18 httpTTS（在线朗读引擎表）

**表名：** `httpTTS`  
**说明：** 在线TTS（文本转语音）引擎配置，用于朗读功能。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `id` | INTEGER | `System.currentTimeMillis()` | **PRIMARY KEY** | 引擎ID |
| `name` | TEXT | `""` | NOT NULL | 名称 |
| `url` | TEXT | `""` | NOT NULL | 合成接口地址 |
| `contentType` | TEXT | NULL | 可空 | 响应格式（如 `audio/mpeg`） |
| `concurrentRate` | TEXT | `"0"` | NULLABLE | 并发率 |
| `loginUrl` | TEXT | NULL | 可空 | 登录地址 |
| `loginUi` | TEXT | NULL | 可空 | 登录UI配置（JSON格式） |
| `header` | TEXT | NULL | 可空 | 请求头 |
| `jsLib` | TEXT | NULL | 可空 | JS库 |
| `enabledCookieJar` | INTEGER | `0` | NULLABLE | 启用Cookie管理（1=启用, 0=禁用） |
| `loginCheckJs` | TEXT | NULL | 可空 | 登录检测JS |
| `lastUpdateTime` | INTEGER | `0` | NOT NULL | 最后更新时间（毫秒时间戳） |

**说明：** 实现 `BaseSource` 接口，支持登录、Header管理、JS执行等通用书源功能。

---

### 3.19 caches（缓存表）

**表名：** `caches`  
**说明：** 通用键值缓存表，支持过期时间。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `key` | TEXT | `""` | **PRIMARY KEY** | 缓存键 |
| `value` | TEXT | NULL | 可空 | 缓存值 |
| `deadline` | INTEGER | `0` | NOT NULL | 过期时间戳（0表示永不过期） |

**索引：**
- `UNIQUE (key)` — 缓存键唯一索引

---

### 3.20 ruleSubs（规则订阅表）

**表名：** `ruleSubs`  
**说明：** 规则订阅管理，用于自动更新书源/规则。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `id` | INTEGER | `System.currentTimeMillis()` | **PRIMARY KEY** | 订阅ID |
| `name` | TEXT | `""` | NOT NULL | 名称 |
| `url` | TEXT | `""` | NOT NULL | 订阅URL |
| `type` | INTEGER | `0` | NOT NULL | 类型 |
| `customOrder` | INTEGER | `0` | NOT NULL | 排序 |
| `autoUpdate` | INTEGER | `0` | NOT NULL | 自动更新（1=是, 0=否） |
| `update` | INTEGER | `System.currentTimeMillis()` | NOT NULL | 更新时间（毫秒时间戳） |

**历史：** 最初名为 `sourceSubs`，v25 迁移时重命名为 `ruleSubs` 并添加 `autoUpdate` 和 `update` 字段。

---

### 3.21 dictRules（词典规则表）

**表名：** `dictRules`  
**说明：** 词典查词规则，定义查询接口和结果解析规则。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `name` | TEXT | `""` | **PRIMARY KEY** | 词典名称 |
| `urlRule` | TEXT | `""` | NOT NULL | 查词接口规则（支持 `$key` 等变量替换） |
| `showRule` | TEXT | `""` | NOT NULL | 结果解析规则 |
| `enabled` | INTEGER | `1` | NOT NULL | 是否启用（1=启用, 0=禁用） |
| `sortNumber` | INTEGER | `0` | NOT NULL | 排序 |

---

### 3.22 keyboardAssists（键盘辅助表）

**表名：** `keyboardAssists`  
**说明：** 键盘辅助按键配置，用于自定义工具栏按钮。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `type` | INTEGER | `0` | **PRIMARY KEY（复合）** | 类型 |
| `key` | TEXT | `""` | **PRIMARY KEY（复合）** | 键名 |
| `value` | TEXT | `""` | NOT NULL | 键值 |
| `serialNo` | INTEGER | `0` | NOT NULL | 序列号 |

**复合主键：** `(type, key)`  
**说明：** 首次打开数据库时，如果该表为空，会自动插入默认键盘辅助数据（`DefaultData.keyboardAssists`）。

---

### 3.23 servers（服务器表）

**表名：** `servers`  
**说明：** 服务器配置，用于WebDAV等同步服务。

| 字段名 | 数据类型 | 默认值 | 约束 | 说明 |
|--------|---------|--------|------|------|
| `id` | INTEGER | `System.currentTimeMillis()` | **PRIMARY KEY** | 服务器ID |
| `name` | TEXT | `""` | NOT NULL | 名称 |
| `type` | TEXT | `WEBDAV` | NOT NULL | 类型（枚举：`WEBDAV`） |
| `config` | TEXT | NULL | 可空 | 配置（JSON格式） |
| `sortNumber` | INTEGER | `0` | NOT NULL | 排序 |

**type 枚举值：** `WEBDAV`（WebDAV协议同步）

**WebDavConfig 内嵌对象（JSON 序列化）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `url` | String | WebDAV 服务器地址 |
| `username` | String | 用户名 |
| `password` | String | 密码 |

---

## 4. 索引汇总

| 表名 | 索引名 | 索引列 | 类型 | 说明 |
|------|--------|--------|------|------|
| `books` | `index_books_name_author` | `(name, author)` | **UNIQUE** | 防止重复书籍 |
| `books` | `index_books_type` | `(type)` | 普通 | 按类型筛选 |
| `chapters` | `index_chapters_bookUrl` | `(bookUrl)` | 普通 | 按书籍查章节 |
| `chapters` | `index_chapters_bookUrl_index` | `(bookUrl, index)` | **UNIQUE** | 章节序号唯一 |
| `book_sources` | `index_book_sources_bookSourceUrl` | `(bookSourceUrl)` | 普通 | 书源URL索引 |
| `searchBooks` | `index_searchBooks_bookUrl` | `(bookUrl)` | **UNIQUE** | 搜索缓存唯一 |
| `searchBooks` | `index_searchBooks_origin` | `(origin)` | 普通 | 按书源查缓存 |
| `search_keywords` | `index_search_keywords_word` | `(word)` | **UNIQUE** | 关键词唯一 |
| `book_search_keywords` | `index_book_search_keywords_word` | `(word)` | **UNIQUE** | 书籍搜索关键词唯一 |
| `cookies` | `index_cookies_url` | `(url)` | **UNIQUE** | URL唯一 |
| `caches` | `index_caches_key` | `(key)` | **UNIQUE** | 缓存键唯一 |
| `rssSources` | `index_rssSources_sourceUrl` | `(sourceUrl)` | 普通 | RSS源URL索引 |
| `bookmarks` | `index_bookmarks_bookName_bookAuthor` | `(bookName, bookAuthor)` | 普通 | 按书名+作者查书签 |
| `replace_rules` | `index_replace_rules_id` | `(id)` | 普通 | 规则ID索引 |

---

## 5. 外键关系

| 子表 | 外键列 | 父表 | 父表列 | 删除策略 | 说明 |
|------|--------|------|--------|---------|------|
| `chapters` | `bookUrl` | `books` | `bookUrl` | **CASCADE** | 删除书籍时自动删除所有章节 |
| `searchBooks` | `origin` | `book_sources` | `bookSourceUrl` | **CASCADE** | 删除书源时自动删除搜索缓存 |

**逻辑关联（非外键约束）：**

| 子表 | 关联列 | 父表 | 父表列 | 说明 |
|------|--------|------|--------|------|
| `books` | `origin` | `book_sources` | `bookSourceUrl` | 书籍所属书源（逻辑关联，非数据库外键） |
| `books` | `group` | `book_groups` | `groupId` | 书籍所属分组（逻辑关联，非数据库外键） |
| `books` | `tags`（位掩码） | `book_tags` | `tagId` | 书籍标签（位掩码组合，非数据库外键） |
| `rssArticles` | `origin` | `rssSources` | `sourceUrl` | RSS文章所属订阅源（逻辑关联） |
| `rssStars` | `origin` | `rssSources` | `sourceUrl` | RSS收藏所属订阅源（逻辑关联） |

---

## 6. 视图定义

### 6.1 book_sources_part（书源部分字段视图）

**视图名：** `book_sources_part`  
**用途：** 提供书源的部分字段视图，用于书源列表展示，避免加载完整的规则JSON数据。

**SQL 定义：**
```sql
SELECT 
    bookSourceUrl, 
    bookSourceName, 
    bookSourceGroup, 
    customOrder, 
    enabled, 
    enabledExplore, 
    (loginUrl IS NOT NULL AND TRIM(loginUrl) <> '') AS hasLoginUrl, 
    lastUpdateTime, 
    respondTime, 
    weight, 
    (exploreUrl IS NOT NULL AND TRIM(exploreUrl) <> '') AS hasExploreUrl 
FROM book_sources
```

**字段说明：**

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `bookSourceUrl` | TEXT | 书源地址 |
| `bookSourceName` | TEXT | 书源名称 |
| `bookSourceGroup` | TEXT | 分组 |
| `customOrder` | INTEGER | 手动排序编号 |
| `enabled` | INTEGER | 是否启用 |
| `enabledExplore` | INTEGER | 启用发现 |
| `hasLoginUrl` | INTEGER | 是否有登录地址（计算列） |
| `lastUpdateTime` | INTEGER | 最后更新时间 |
| `respondTime` | INTEGER | 响应时间 |
| `weight` | INTEGER | 智能排序权重 |
| `hasExploreUrl` | INTEGER | 是否有发现URL（计算列） |

---

## 7. 数据库迁移历史概要

### 7.1 手动迁移（v10 → v43）

| 版本 | 迁移内容 |
|------|---------|
| 10→11 | 重建 `txtTocRules` 表（添加 `example` 字段） |
| 11→12 | `rssSources` 添加 `style` 字段 |
| 12→13 | `rssSources` 添加 `articleStyle` 字段 |
| 13→14 | 重建 `books` 表（移除 `useReplaceRule` 字段） |
| 14→15 | `bookmarks` 添加 `bookAuthor` 字段 |
| 15→17 | 新建 `readRecord` 表 |
| 17→18 | 新建 `httpTTS` 表 |
| 18→19 | `readRecord` 主键改为 `(androidId, bookName)` |
| 19→20 | `book_sources` 添加 `bookSourceComment` 字段 |
| 20→21 | `book_groups` 添加 `show` 字段 |
| 21→22 | 重建 `books` 表（`useReplaceRule` 替换为 `readConfig` JSON字段） |
| 22→23 | `chapters` 添加 `baseUrl` 字段 |
| 23→24 | 新建 `caches` 表 |
| 24→25 | 新建 `sourceSubs` 表 |
| 25→26 | `sourceSubs` 重命名为 `ruleSubs`，添加 `autoUpdate` 和 `update` 字段 |
| 26→27 | `rssSources` 添加 `singleUrl`；重建 `bookmarks`（移除 `bookUrl`，添加 `bookText`） |
| 27→28 | `rssArticles` 和 `rssStars` 添加 `variable` 字段 |
| 28→29 | `rssSources` 添加 `sourceComment` 字段 |
| 29→30 | `chapters` 添加 `startFragmentId`、`endFragmentId`；新建 `epubChapters` 表 |
| 30→31 | `readRecord` 主键改为 `(deviceId, bookName)` |
| 31→32 | 删除 `epubChapters` 表 |
| 32→33 | 重建 `bookmarks`（移除 `bookUrl`，通过 JOIN `books` 获取 `bookName`/`bookAuthor`） |
| 33→34 | `book_groups` 添加 `cover` 字段 |
| 34→35 | `book_sources` 添加 `concurrentRate` 字段 |
| 35→36 | `book_sources` 添加 `loginUi`、`loginCheckJs` 字段 |
| 36→37 | `rssSources` 添加 `loginUrl`、`loginUi`、`loginCheckJs` 字段 |
| 37→38 | `book_sources` 添加 `respondTime` 字段（默认 180000） |
| 38→39 | `rssSources` 添加 `concurrentRate` 字段 |
| 39→40 | `chapters` 添加 `isVip`、`isPay` 字段 |
| 40→41 | `httpTTS` 添加 `loginUrl`、`loginUi`、`loginCheckJs`、`header`、`concurrentRate` 字段 |
| 41→42 | `httpTTS` 添加 `contentType` 字段 |
| 42→43 | `chapters` 添加 `isVolume` 字段 |

### 7.2 AutoMigration（v43 → v79）

| 版本范围 | 迁移方式 | 特殊说明 |
|---------|---------|---------|
| 43→44 | AutoMigration | — |
| 44→45 | AutoMigration | — |
| 45→46 | AutoMigration | — |
| 46→47 | AutoMigration | — |
| 47→48 | AutoMigration | — |
| 48→49 | AutoMigration | — |
| 49→50 | AutoMigration | — |
| 50→51 | AutoMigration | — |
| 51→52 | AutoMigration | — |
| 52→53 | AutoMigration | — |
| 53→54 | AutoMigration | — |
| **54→55** | AutoMigration + Spec | `Migration_54_55`：将 `books.type` 从 `BookSourceType` 迁移到 `BookType` 体系，同时为本地书籍设置 `type \| BookType.local` 标志位 |
| 55→56 | AutoMigration | — |
| 56→57 | AutoMigration | — |
| 57→58 | AutoMigration | — |
| 58→59 | AutoMigration | — |
| 59→60 | AutoMigration | — |
| 60→61 | AutoMigration | — |
| 61→62 | AutoMigration | — |
| 62→63 | AutoMigration | — |
| 63→64 | AutoMigration | — |
| **64→65** | AutoMigration + Spec | `Migration_64_65`：删除 `book_sources.enabledReview` 列 |
| 65→66 | AutoMigration | — |
| 66→67 | AutoMigration | — |
| 67→68 | AutoMigration | — |
| 68→69 | AutoMigration | — |
| 69→70 | AutoMigration | — |
| 70→71 | AutoMigration | — |
| 71→72 | AutoMigration | — |
| 72→73 | AutoMigration | — |
| 73→74 | AutoMigration | — |
| 74→75 | AutoMigration | — |
| 75→76 | AutoMigration | — |
| 76→77 | AutoMigration | — |
| **77→78** | AutoMigration + Spec | `Migration_77_78`：删除 `books.tags` 列（旧版tags列，被新版 `tags` 位掩码字段替代） |
| 78→79 | AutoMigration | — |

### 7.3 数据库初始化回调

`AppDatabase.dbCallback` 在数据库 `onOpen` 时执行以下操作：

1. **系统预设分组**：自动插入 `book_groups` 的6个系统预设分组（全部、本地、音频、网络未分组、本地未分组、更新失败）
2. **数据清理**：清理 `book_sources`、`rssSources`、`httpTTS` 中 `loginUi = 'null'` 的无效数据
3. **数据修复**：修复 `httpTTS` 中 `concurrentRate` 为 NULL 的记录
4. **默认数据**：如果 `keyboardAssists` 表为空，自动插入默认键盘辅助数据

---

## 附录：字段类型映射

| Room 注解类型 | Kotlin 类型 | SQLite 存储类型 |
|--------------|-------------|----------------|
| `Int` | `Int` / `Boolean` | `INTEGER` |
| `Long` | `Long` | `INTEGER` |
| `String` | `String` | `TEXT` |
| `String?` | `String?` | `TEXT`（可空） |
| `Long?` | `Long?` | `INTEGER`（可空） |
| JSON 对象 | `ReadConfig` / `ExploreRule` 等 | `TEXT`（通过 `@TypeConverter` 序列化） |
| `TYPE` | `Server.TYPE` 枚举 | `TEXT`（存储枚举名称） |

**说明：** SQLite 布尔值使用 `INTEGER` 存储，`1` 表示 `true`，`0` 表示 `false`。Room 框架自动处理 Kotlin `Boolean` 与 SQLite `INTEGER` 之间的转换。