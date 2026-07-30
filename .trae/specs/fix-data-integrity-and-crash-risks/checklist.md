# Checklist

## 事务完整性验证
- [x] 1.1 ReadBook.upToc() 使用 runInTransaction 包裹 delByBook + insert + update
- [x] 1.2 ReadManga.upToc() 使用 runInTransaction 包裹 delByBook + insert + update
- [x] 1.3 BookController.refreshToc() 两处目录更新使用 runInTransaction 包裹
- [x] 1.4 ReadBookViewModel 两处目录更新使用 runInTransaction 包裹
- [x] 1.5 ReadMangaViewModel 目录更新使用 runInTransaction 包裹
- [x] 1.6 BookInfoViewModel 两处目录更新使用 runInTransaction 包裹
- [x] 1.7 MainViewModel 目录更新使用 runInTransaction 包裹
- [x] 1.8 AudioPlayViewModel 目录更新使用 runInTransaction 包裹
- [x] 1.9 TocViewModel 目录更新使用 runInTransaction 包裹
- [x] 1.10 ExportBookService 目录更新使用 runInTransaction 包裹

## 非空断言消除验证
- [x] 2.1 ReadBook.loadContent() 中 book!! 替换为安全的空值检查，book 为 null 时不崩溃
- [x] 2.2 ReadBook.loadContentAwait() 中 book!! 和 chapter!! 替换为安全的空值检查
- [x] 2.3 ReadBook.downloadAwait() 中 book!! 替换为安全的空值检查
- [x] 2.4 ReadManga.loadContent() 中 book!! 替换为安全的空值检查

## 并发修复验证
- [x] 3.1 ConcurrentRateLimiter.concurrentRecordMap 改为 ConcurrentHashMap
- [x] 3.2 fetchStart() 使用 computeIfAbsent 原子操作创建 ConcurrentRecord
- [x] 3.3 多线程并发访问同一书源时，ConcurrentRecord 唯一且状态正确

## EPUB 解析验证
- [x] 4.1 EpubReader 中资源（ZipFile、InputStream）在异常路径中被正确关闭
- [x] 4.2 ZipFileWrapper.getInputStream() 对 null 返回有安全处理
- [x] 4.3 损坏的 EPUB 文件不会导致应用崩溃

## 测试验证
- [x] 5.1 ReadBook 空值安全测试：book 为 null 时 loadContent 不崩溃
- [x] 5.2 ConcurrentRateLimiter 并发测试：多线程并发创建 ConcurrentRecord 正确
- [x] 5.3 EPUB 解析测试：损坏文件不崩溃且资源正确释放