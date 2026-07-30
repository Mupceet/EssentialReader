# Tasks

## Task 1: 为目录更新操作添加数据库事务保护
为所有 `delByBook` + `insert` 模式的目录更新操作添加 `appDb.runInTransaction` 包裹，确保数据原子性。

### 受影响文件（共11处，按优先级排列）：
- [x] 1.1 `app/src/main/java/io/legado/app/model/ReadBook.kt` - `upToc()` 方法（第880-882行）
- [x] 1.2 `app/src/main/java/io/legado/app/model/ReadManga.kt` - `upToc()` 方法（第477-482行）
- [x] 1.3 `app/src/main/java/io/legado/app/api/controller/BookController.kt` - `refreshToc()` 方法（第162-177行，两处）
- [x] 1.4 `app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt` - 目录更新（第193-196行，第223-225行）
- [x] 1.5 `app/src/main/java/io/legado/app/ui/book/manga/ReadMangaViewModel.kt` - 目录更新（第126-130行）
- [x] 1.6 `app/src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt` - 目录更新（第224-225行，第250-251行）
- [x] 1.7 `app/src/main/java/io/legado/app/ui/main/MainViewModel.kt` - 目录更新（第175-176行）
- [x] 1.8 `app/src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt` - 目录更新（第76-77行）
- [x] 1.9 `app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt` - 目录更新（第42行）
- [x] 1.10 `app/src/main/java/io/legado/app/service/ExportBookService.kt` - 目录更新（第222-223行）

## Task 2: 消除非空断言（!!）防止崩溃
将 `ReadBook.kt` 和 `ReadManga.kt` 中的 `!!` 非空断言替换为安全的空值检查。

- [x] 2.1 `app/src/main/java/io/legado/app/model/ReadBook.kt` - `loadContent()` 方法（第573行）：将 `val book = book!!` 替换为 `val book = ReadBook.book ?: return@async`
- [x] 2.2 `app/src/main/java/io/legado/app/model/ReadBook.kt` - `loadContentAwait()` 方法（第604-605行）：将 `val book = book!!` 和 `val chapter = ...!!` 替换为安全空值检查
- [x] 2.3 `app/src/main/java/io/legado/app/model/ReadBook.kt` - `downloadAwait()` 方法（第665行）：将 `val book = book!!` 替换为安全空值检查
- [x] 2.4 `app/src/main/java/io/legado/app/model/ReadManga.kt` - `loadContent()` 方法（第177行）：将 `val book = book!!` 替换为安全空值检查

## Task 3: 修复 ConcurrentRateLimiter 双重检查锁定缺陷
修复 `fetchStart()` 中的 DCL 缺陷，使用 `ConcurrentHashMap` 的原子操作或正确的同步机制。

- [x] 3.1 `app/src/main/java/io/legado/app/help/ConcurrentRateLimiter.kt` - 将 `concurrentRecordMap` 类型从 `HashMap` 改为 `ConcurrentHashMap`，使用 `computeIfAbsent` 替代手动的双重检查锁定

## Task 4: 增强 EPUB 文件解析的异常处理
为 EPUB 文件解析添加 try-with-resources 和异常处理。

- [x] 4.1 `modules/book/src/main/java/me/ag2s/epublib/epub/EpubReader.java` - 为 `processPackageResource` 添加 null 检查，防止 NPE
- [x] 4.2 `modules/book/src/main/java/me/ag2s/epublib/util/zip/ZipFileWrapper.java` - 为 `getInputStream()` 添加 null 检查，防止 NPE；修复 `getComment()` 中 AndroidZipFile 分支的 bug（getName → getComment）

## Task 5: 编写测试验证修复
- [x] 5.1 为 `ConcurrentRateLimiter` 的线程安全修复编写并发测试（5 个测试用例）
- [x] 5.2 为 `ConcurrentHashMap` 的 computeIfAbsent 原子性编写测试
- [x] 5.3 为 `ConcurrentRecord` 的 synchronized 同步正确性编写测试

# Task Dependencies
- Task 5 依赖 Task 1, 2, 3, 4 完成
- Task 1, 2, 3, 4 之间无依赖，可并行执行