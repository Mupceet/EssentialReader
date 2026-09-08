package io.legado.app.eink.contract

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.lang.reflect.Proxy

/**
 * 引擎端口注册表（service locator）：未注册访问抛指名异常、install
 * 后端口可达、重复 install 整体替换（keyEventHub 一并重置）、更新端口
 * 可选缺席。
 *
 * 端口桩经 JDK 动态代理生成（install 只存引用不调方法，桩方法返回
 * null 即可），避免为 8 个接口手写假实现。
 *
 * 注册表为进程级单例且无卸载 API，测试按名称排序执行：未注册断言
 * 必须先于 install 用例。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EInkEngineRegistryTest {

    @Test
    fun `a_未注册端口访问抛指名异常`() {
        val expectedPorts = listOf(
            "GlobalSettings" to { EInkEngineRegistry.globalSettings },
            "ReaderEngine" to { EInkEngineRegistry.readerEngine },
            "BookshelfEngine" to { EInkEngineRegistry.bookshelfEngine },
        )
        expectedPorts.forEach { (port, getter) ->
            try {
                getter()
                fail("访问 $port 应抛未注册异常")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "异常应指名缺失端口 $port，实际：${e.message}",
                    e.message!!.contains(port),
                )
                assertTrue("异常应指名修复入口 install", e.message!!.contains("install"))
            }
        }
    }

    @Test
    fun `b_install 后端口可达且重复 install 整体替换`() {
        val settings = stub<GlobalSettings>()
        val bookshelf = stub<BookshelfEngine>()
        val search = stub<SearchEngine>()
        val toc = stub<TocEngine>()
        val detail = stub<BookDetailEngine>()
        val changeSource = stub<ChangeSourceEngine>()
        val cover = stub<CoverEngine>()
        val reader = stub<ReaderEngine>()
        val appUpdate = stub<AppUpdateEngine>()

        EInkEngineRegistry.install(
            globalSettings = settings,
            bookshelfEngine = bookshelf,
            searchEngine = search,
            tocEngine = toc,
            bookDetailEngine = detail,
            changeSourceEngine = changeSource,
            coverEngine = cover,
            readerEngine = reader,
            appUpdateEngine = appUpdate,
        )

        assertSame(settings, EInkEngineRegistry.globalSettings)
        assertSame(bookshelf, EInkEngineRegistry.bookshelfEngine)
        assertSame(search, EInkEngineRegistry.searchEngine)
        assertSame(toc, EInkEngineRegistry.tocEngine)
        assertSame(detail, EInkEngineRegistry.bookDetailEngine)
        assertSame(changeSource, EInkEngineRegistry.changeSourceEngine)
        assertSame(cover, EInkEngineRegistry.coverEngine)
        assertSame(reader, EInkEngineRegistry.readerEngine)
        assertSame(appUpdate, EInkEngineRegistry.appUpdateEngine)

        val hubBefore = EInkEngineRegistry.keyEventHub
        EInkEngineRegistry.install(
            globalSettings = stub(),
            bookshelfEngine = stub(),
            searchEngine = stub(),
            tocEngine = stub(),
            bookDetailEngine = stub(),
            changeSourceEngine = stub(),
            coverEngine = stub(),
            readerEngine = stub(),
        )
        assertTrue("整体替换：设置端口指向新桩", EInkEngineRegistry.globalSettings !== settings)
        assertTrue("keyEventHub 随 install 重置", EInkEngineRegistry.keyEventHub !== hubBefore)
    }

    @Test
    fun `c_更新端口缺省为空`() {
        EInkEngineRegistry.install(
            globalSettings = stub(),
            bookshelfEngine = stub(),
            searchEngine = stub(),
            tocEngine = stub(),
            bookDetailEngine = stub(),
            changeSourceEngine = stub(),
            coverEngine = stub(),
            readerEngine = stub(),
        )
        assertNull(EInkEngineRegistry.appUpdateEngine)
    }

    @Test
    fun `d_按键枢纽恒可用且默认无人处理`() {
        val hub = EInkEngineRegistry.keyEventHub
        assertNotNull(hub)
        assertNull("默认无注册处理器（按键放行系统）", hub.handler)
    }
}

/** JDK 动态代理生成端口桩：只用于注册表存取断言，桩方法一律返回 null。 */
private inline fun <reified T : Any> stub(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, _, _ -> null } as T
