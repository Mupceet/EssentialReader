package io.legado.app.eink.app

import androidx.lifecycle.ViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航控制器的栈语义与条目 ViewModelStore 生命周期：
 * push 新建存储（再进 == 首进）、pop 复用下层存储、出栈/替换/清空时
 * 释放存储（触发 ViewModel.onCleared 的前置条件）。
 */
class EInkNavControllerTest {

    private fun newController(vararg screens: EInkScreen): EInkNavController =
        EInkNavController(screens.toList())

    @Test
    fun `初始栈为空时拒绝构造`() {
        var error: IllegalArgumentException? = null
        try {
            EInkNavController(emptyList())
        } catch (e: IllegalArgumentException) {
            error = e
        }
        assertEquals("initialStack must not be empty", error?.message)
    }

    @Test
    fun `单条目栈不可返回`() {
        val controller = newController(EInkScreen.Home)
        assertFalse(controller.canPop)
        assertFalse(controller.pop())
        assertSame(EInkScreen.Home, controller.screen)
    }

    @Test
    fun `navigate 入栈并切换当前屏`() {
        val controller = newController(EInkScreen.Home)
        controller.navigate(EInkScreen.Search)
        assertSame(EInkScreen.Search, controller.screen)
        assertTrue(controller.canPop)
        assertTrue(controller.pop())
        assertSame(EInkScreen.Home, controller.screen)
        assertFalse(controller.canPop)
    }

    @Test
    fun `条目 id 单调递增且跨操作唯一`() {
        val controller = newController(EInkScreen.Home, EInkScreen.Search)
        val id0 = controller.currentEntryId
        controller.navigate(EInkScreen.ThemeDebug)
        val id1 = controller.currentEntryId
        controller.pop()
        controller.navigate(EInkScreen.ComponentGallery)
        val id2 = controller.currentEntryId
        assertTrue(0 <= id0 && id0 < id1 && id1 < id2)
    }

    @Test
    fun `pop 返回时复用原条目存储`() {
        val controller = newController(EInkScreen.Home)
        controller.navigate(EInkScreen.Toc("book-a"))
        val tocStore = controller.currentViewModelStore
        val viewModel = TestViewModel()
        tocStore.put("vm", viewModel)
        controller.navigate(EInkScreen.Reader("book-a"))

        controller.pop()

        assertSame("返回时复用原存储（保留界面状态）", tocStore, controller.currentViewModelStore)
        assertSame("存储内容未释放", viewModel, tocStore.get("vm"))
        assertEquals(EInkScreen.Toc("book-a"), controller.screen)
    }

    @Test
    fun `再次进入同一屏是新建存储`() {
        val controller = newController(EInkScreen.Home)
        controller.navigate(EInkScreen.Search)
        val firstVisitStore = controller.currentViewModelStore
        firstVisitStore.put("vm", TestViewModel())

        controller.pop()
        controller.navigate(EInkScreen.Search)

        assertTrue("再进 == 首进：不复用旧条目存储", controller.currentViewModelStore !== firstVisitStore)
    }

    @Test
    fun `pop 清理出栈条目的 ViewModelStore`() {
        val controller = newController(EInkScreen.Home)
        controller.navigate(EInkScreen.Search)
        val searchStore = controller.currentViewModelStore
        searchStore.put("vm", TestViewModel())

        controller.pop()

        assertNull(searchStore.get("vm"))
    }

    @Test
    fun `navigateAndClear 清空整栈并重建单条目`() {
        val controller = newController(EInkScreen.Home, EInkScreen.Search)
        val searchStore = controller.currentViewModelStore
        searchStore.put("vm", TestViewModel())
        controller.navigate(EInkScreen.ThemeDebug)
        val themeStore = controller.currentViewModelStore
        themeStore.put("vm", TestViewModel())

        controller.navigateAndClear(EInkScreen.Home)

        assertFalse(controller.canPop)
        assertSame(EInkScreen.Home, controller.screen)
        assertNull("旧条目存储全部释放", searchStore.get("vm"))
        assertNull(themeStore.get("vm"))
        assertTrue(controller.currentViewModelStore !== searchStore)
    }

    @Test
    fun `replaceTop 替换栈顶并清理旧条目存储`() {
        val controller = newController(EInkScreen.Home, EInkScreen.Toc("book-a"))
        val tocStore = controller.currentViewModelStore
        tocStore.put("vm", TestViewModel())

        controller.replaceTop(EInkScreen.Reader("book-a"))

        assertTrue(controller.canPop)
        controller.pop()
        assertFalse("栈深恰为 2（替换而非入栈）", controller.canPop)
        assertSame(EInkScreen.Home, controller.screen)
        assertNull("被替换条目的存储已清理", tocStore.get("vm"))
    }

    @Test
    fun `replaceTop 在根栈时等效入栈且不清理根存储`() {
        val controller = newController(EInkScreen.Home)
        val homeStore = controller.currentViewModelStore
        val viewModel = TestViewModel()
        homeStore.put("vm", viewModel)

        controller.replaceTop(EInkScreen.Reader("book-a"))

        assertTrue("根栈 replaceTop 是入栈而非替换", controller.canPop)
        controller.pop()
        assertSame("返回回到根条目", homeStore, controller.currentViewModelStore)
        assertSame("根存储未被清理", viewModel, homeStore.get("vm"))
    }

    @Test
    fun `同帧连续两次 pop 弹出两层并回到下层既有屏`() {
        // EInkApp Note 跳转回阅读页：同一回调内 pop 掉 Note+Toc 两层。
        // 锚定 pop 的栈同步性：第二次 pop 读到第一次 pop 之后的栈
        //（backStack 为普通同步列表，非快照态），无旧栈问题
        val controller = newController(EInkScreen.Home)
        controller.navigate(EInkScreen.Reader("book-a"))
        val readerStore = controller.currentViewModelStore
        controller.navigate(EInkScreen.Toc("book-a", fromReader = true))
        val tocStore = controller.currentViewModelStore
        controller.navigate(EInkScreen.Note("book-a", fromReader = true))
        val noteStore = controller.currentViewModelStore
        noteStore.put("vm", TestViewModel())
        tocStore.put("vm", TestViewModel())

        assertTrue(controller.pop())
        assertTrue(controller.pop())

        assertEquals("两次 pop 后回到既有阅读页", EInkScreen.Reader("book-a"), controller.screen)
        assertSame("阅读页条目存储复用（保留既有状态）", readerStore, controller.currentViewModelStore)
        assertNull("Note 条目存储已释放", noteStore.get("vm"))
        assertNull("Toc 条目存储已释放", tocStore.get("vm"))
    }

    @Test
    fun `pop 后 replaceTop 替换下层中间页并保留更下层`() {
        // EInkApp Note fromReader=false 跳转：先 pop 掉 Note，再 replaceTop
        // 用阅读页替换目录页，返回栈回到详情页（对齐目录页跳转结果）
        val controller = newController(EInkScreen.Home)
        controller.navigate(EInkScreen.BookDetail("名", "作者", "book-a"))
        val detailStore = controller.currentViewModelStore
        detailStore.put("vm", TestViewModel())
        controller.navigate(EInkScreen.Toc("book-a", fromReader = false))
        controller.navigate(EInkScreen.Note("book-a", fromReader = false))

        assertTrue(controller.pop())
        controller.replaceTop(EInkScreen.Reader("book-a"))

        assertEquals(EInkScreen.Reader("book-a"), controller.screen)
        assertTrue("详情页保留在栈中", controller.canPop)
        controller.pop()
        assertEquals(EInkScreen.BookDetail("名", "作者", "book-a"), controller.screen)
        assertSame("详情页存储未被清理", detailStore, controller.currentViewModelStore)
    }

    @Test
    fun `clearAll 清空全部条目存储`() {
        val controller = newController(EInkScreen.Home, EInkScreen.Search)
        val searchStore = controller.currentViewModelStore
        controller.navigate(EInkScreen.ThemeDebug)
        val themeStore = controller.currentViewModelStore
        searchStore.put("a", TestViewModel())
        themeStore.put("b", TestViewModel())

        controller.clearAll()

        assertEquals(0, searchStore.keys().size)
        assertEquals(0, themeStore.keys().size)
        assertFalse(controller.canPop)
    }

    @Test
    fun `EInkNavViewModel 复用控制器`() {
        val holder = EInkNavViewModel()
        assertNull("创建前无控制器", holder.controller)
        val first = holder.getOrCreate(listOf(EInkScreen.Home))

        assertSame("重复 getOrCreate 返回同一控制器", first, holder.getOrCreate(listOf(EInkScreen.Search)))
        assertSame("初栈仅首次生效", first, holder.controller)
        assertSame(EInkScreen.Home, first.screen)
    }
}

/** ViewModel 基类为 abstract（lifecycle 2.9+ KMP），测试用具体子类作存储载荷。 */
private class TestViewModel : ViewModel()
