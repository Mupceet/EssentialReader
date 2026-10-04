package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 契约源兼容性守护：简繁转换成员（转发键 + 能力声明）全部带默认实现，
 * 只实现既有成员面的宿主设置无需改动即可编译——AAR 消费方升级零破坏
 * （ReaderSyncContractCompatTest 同款守护）。LegacyGlobalSettings 刻意
 * 不实现 chineseConverterType / supportsChineseConverter，编译通过即
 * 守护成立；行为断言钉死降级语义：恒 0 档（关闭）、能力 false、写入
 * 丢弃不抛。
 */
class GlobalSettingsChineseConverterCompatTest {

    private class LegacyGlobalSettings : GlobalSettings {
        override val threadCount: Int get() = 1
        override var autoRefreshBook: Boolean
            get() = false
            set(value) {}
        override var defaultToRead: Boolean
            get() = false
            set(value) {}
        override var volumeKeyPage: Boolean
            get() = false
            set(value) {}
        override var useDefaultCover: Boolean
            get() = false
            set(value) {}
        override var pullDownBookmark: Boolean
            get() = false
            set(value) {}
        override var hideStatusBar: Boolean
            get() = false
            set(value) {}
        override var showReviewBubbles: Boolean
            get() = true
            set(value) {}
        override val useAntiAlias: Boolean get() = false
        override val preDownloadChapterCount: Int get() = 0
        override val changeSourceCheckAuthor: Boolean get() = true
        override var fontScaleSetting: Int?
            get() = null
            set(value) {}
    }

    @Test
    fun `legacy settings 编译通过且新成员默认关闭降级`() {
        val settings = LegacyGlobalSettings()
        assertEquals("旧宿主恒 0 档（关闭）", 0, settings.chineseConverterType)
        assertFalse("旧宿主能力 false（入口行不渲染）", settings.supportsChineseConverter)
        settings.chineseConverterType = 2
        assertEquals("写入丢弃不抛不落", 0, settings.chineseConverterType)
    }
}
