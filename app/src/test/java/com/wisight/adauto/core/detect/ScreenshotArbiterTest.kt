package com.wisight.adauto.core.detect

import com.wisight.adauto.core.AdRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图判据表 + 裁决器的回归测试。
 *
 * 这三条判据原来是 `detectAndAct` 里三段几乎一样的 if 阶梯（各带一套 count/seq 字段），
 * 现在是一张表 + 一个裁决器，所以“两张不同截图才动手”“命中后不改判”这类规则
 * 可以脱离真机验证。
 */
class ScreenshotArbiterTest {

    private val landscapeOnly = Ambiguity(landscape = true, contentless = false)
    private val both = Ambiguity(landscape = true, contentless = true)
    private val neither = Ambiguity(landscape = false, contentless = false)

    @Test
    fun `必须两张不同截图都命中才动手`() {
        val arbiter = ScreenshotArbiter()
        val first = arbiter.evaluate(probeFrame(1L, orange = true), landscapeOnly)
        assertNull(first.fired)
        assertTrue("一张图命中后要再取一张确认", first.needConfirm)
        assertEquals(1, first.logs.first { it.logLabel == "横屏主策略" }.count)

        val second = arbiter.evaluate(probeFrame(2L, orange = true), landscapeOnly)
        assertEquals(AdRules.REASON_ORANGE_AD, second.fired?.reason)
        assertFalse(second.needConfirm)
        assertEquals(2, second.logs.first { it.logLabel == "横屏主策略" }.count)
    }

    @Test
    fun `同一张截图重复评估不会重复计数`() {
        val arbiter = ScreenshotArbiter()
        arbiter.evaluate(probeFrame(7L, orange = true), landscapeOnly)
        val again = arbiter.evaluate(probeFrame(7L, orange = true), landscapeOnly)
        assertNull(again.fired)
        assertTrue("同一张图不再刷日志", again.logs.isEmpty())
    }

    @Test
    fun `闸门关闭时不参与判定`() {
        val arbiter = ScreenshotArbiter()
        arbiter.evaluate(probeFrame(1L, orange = true, hint = true), neither)
        val second = arbiter.evaluate(probeFrame(2L, orange = true, hint = true), neither)
        assertNull(second.fired)
    }

    @Test
    fun `信箱（结构判据）优先于橙色，且命中后不改判`() {
        val arbiter = ScreenshotArbiter()
        arbiter.evaluate(probeFrame(1L, pillar = true, orange = true), landscapeOnly)
        val second = arbiter.evaluate(probeFrame(2L, pillar = true, orange = true), landscapeOnly)
        assertEquals(AdRules.REASON_PILLARBOX_AD, second.fired?.reason)
    }

    /**
     * 历史不对称：提示栏在“已被前面的判据定下结论”时**不清零**（原实现是
     * `else if (!contentlessAmbiguous) hintHitCount = 0`）。
     * 如果这里被“顺手统一”成清零，提示栏就需要更多轮截图才能命中。
     */
    @Test
    fun `被前面判据短路时提示栏计数保留`() {
        val arbiter = ScreenshotArbiter()
        // seq1：信箱 1 张、提示栏 1 张
        arbiter.evaluate(probeFrame(1L, pillar = true, hint = true), both)
        // seq2：信箱第二张 → 命中；此时提示栏被跳过，计数应保留
        val pillar = arbiter.evaluate(probeFrame(2L, pillar = true), both)
        assertEquals(AdRules.REASON_PILLARBOX_AD, pillar.fired?.reason)
        // seq3：提示栏第二张 → 直接命中（若 seq2 时被清零，这里只会到 1）
        val hint = arbiter.evaluate(probeFrame(3L, hint = true), both)
        assertEquals(AdRules.REASON_HINT_BAR_AD, hint.fired?.reason)
    }

    @Test
    fun `橙色判据被信箱短路时清零`() {
        val arbiter = ScreenshotArbiter()
        arbiter.evaluate(probeFrame(1L, pillar = true, orange = true), landscapeOnly)
        arbiter.evaluate(probeFrame(2L, pillar = true, orange = true), landscapeOnly) // 信箱命中，橙色被清零
        val next = arbiter.evaluate(probeFrame(3L, orange = true), landscapeOnly)
        assertNull("橙色计数已被清零，这张只算第 1 张", next.fired)
    }

    @Test
    fun `resetCounts 清掉所有计数但保留“已消费序号”`() {
        val arbiter = ScreenshotArbiter()
        arbiter.evaluate(probeFrame(1L, orange = true), landscapeOnly)
        arbiter.resetCounts()
        val next = arbiter.evaluate(probeFrame(2L, orange = true), landscapeOnly)
        assertNull(next.fired)
        assertEquals(1, next.logs.first { it.logLabel == "横屏主策略" }.count)
    }

    @Test
    fun `换窗口只作废橙色计数，提示栏计数保留`() {
        val arbiter = ScreenshotArbiter()
        arbiter.evaluate(probeFrame(1L, orange = true, hint = true), both)
        arbiter.resetCountFor(AdRules.REASON_ORANGE_AD)
        val r = arbiter.evaluate(probeFrame(2L, orange = true, hint = true), both)
        assertEquals(
            "橙色被清零过，这张只算第 1 张",
            1,
            r.logs.first { it.logLabel == "横屏主策略" }.count,
        )
        assertEquals(
            "提示栏跨窗口保留，这张直接到 2",
            2,
            r.logs.first { it.logLabel == "底部提示栏" }.count,
        )
        assertEquals(AdRules.REASON_HINT_BAR_AD, r.fired?.reason)
    }

    @Test
    fun `一张都没命中时不需要确认`() {
        val arbiter = ScreenshotArbiter()
        val r = arbiter.evaluate(probeFrame(1L), landscapeOnly)
        assertNull(r.fired)
        assertFalse(r.needConfirm)
        assertTrue("未命中也要记一行“看到了什么”", r.logs.isNotEmpty())
    }
}
