package com.zzupocket.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全应用字号缩放档位的口径回归。 */
class AppFontScaleTest {

    @Test
    fun `跟随系统不覆盖fontScale`() {
        assertNull(AppFontScale.SYSTEM.scale)
    }

    @Test
    fun `档位倍率单调递增且标准档为一`() {
        val explicit = listOf(
            AppFontScale.SMALL,
            AppFontScale.STANDARD,
            AppFontScale.LARGE,
            AppFontScale.EXTRA_LARGE,
        )
        val scales = explicit.map { requireNotNull(it.scale) }
        assertEquals(1.0f, scales[1])
        assertTrue(scales.zipWithNext().all { (a, b) -> a < b })
    }
}
