package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfGridTitleHeightTest {

    @Test
    fun `标题最小高度随最大行数伸缩`() {
        assertEquals(20.dp, bookshelfGridTitleHeight(Density(1f), 20.sp, 1))
        assertEquals(100.dp, bookshelfGridTitleHeight(Density(1f), 20.sp, 5))
    }

    @Test
    fun `标题最大行数钳制到 1 到 5`() {
        assertEquals(20.dp, bookshelfGridTitleHeight(Density(1f), 20.sp, 0))
        assertEquals(100.dp, bookshelfGridTitleHeight(Density(1f), 20.sp, 6))
    }
}
