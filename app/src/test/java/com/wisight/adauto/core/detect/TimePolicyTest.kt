package com.wisight.adauto.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间性策略的回归测试。
 *
 * 这些状态机原来散在 `AdDetector` 的字段里、只能靠真机日志推断；
 * 现在 `now` 是显式入参，可以直接把“几秒之后该不该动手”钉死。
 */
class TimePolicyTest {

    // ---------------- AdSwipeChain ----------------

    @Test
    fun `首次上滑进入入场期并给出最短补扫间隔`() {
        val chain = AdSwipeChain()
        assertEquals(700L, chain.onSwipe(1_000L))
        assertEquals(1, chain.streak)
        assertFalse(chain.isWatchdog)
        assertFalse("间隔未到不能补扫", chain.allowsRetry(1_000L))
        assertTrue(chain.allowsRetry(1_700L))
    }

    @Test
    fun `前 7 次用最短间隔，之后转看门狗慢节奏`() {
        val chain = AdSwipeChain()
        var delay: Long? = null
        for (i in 0 until 7) delay = chain.onSwipe(i * 700L)
        assertEquals(1_500L, delay)
        assertTrue(chain.isWatchdog)
    }

    @Test
    fun `总预算用尽后停止补扫`() {
        val chain = AdSwipeChain()
        assertEquals(700L, chain.onSwipe(0L))
        assertNull("超出 40s 总预算应停止补扫", chain.onSwipe(40_001L))
    }

    @Test
    fun `硬上限用尽后停止补扫`() {
        val chain = AdSwipeChain(maxAttempts = 3)
        chain.onSwipe(0L)
        chain.onSwipe(700L)
        assertNull(chain.onSwipe(1_400L))
    }

    @Test
    fun `距上次上滑过久视为新一串，重新计数`() {
        val chain = AdSwipeChain()
        chain.onSwipe(0L)
        chain.onSwipe(100L)
        assertEquals(2, chain.streak)
        chain.resetIfStale(100L + 10_001L)
        assertEquals(0, chain.streak)
        // 未超时则保留
        chain.onSwipe(20_000L)
        chain.resetIfStale(20_000L + 5_000L)
        assertEquals(1, chain.streak)
    }

    // ---------------- ActionCooldown ----------------

    @Test
    fun `默认冷却 1_5s，穿山甲与直播用 6s`() {
        val cooldown = ActionCooldown()
        assertTrue(cooldown.allows(0L))
        cooldown.arm(1_000L, longCooldown = false)
        assertFalse(cooldown.allows(2_499L))
        assertTrue(cooldown.allows(2_500L))
        assertEquals(1_000L, cooldown.lastActionAt)

        cooldown.arm(10_000L, longCooldown = true)
        assertFalse(cooldown.allows(15_999L))
        assertTrue(cooldown.allows(16_000L))
    }

    @Test
    fun `清掉冷却后可立即动作`() {
        val cooldown = ActionCooldown()
        cooldown.arm(1_000L, longCooldown = true)
        assertFalse(cooldown.allows(1_100L))
        cooldown.clear()
        assertTrue(cooldown.allows(1_100L))
    }

    @Test
    fun `冷却提示按 500ms 节流`() {
        val cooldown = ActionCooldown()
        assertTrue(cooldown.noticeDue(1_000L))
        assertFalse(cooldown.noticeDue(1_400L))
        assertTrue(cooldown.noticeDue(1_500L))
    }

    // ---------------- PersistenceGate ----------------

    @Test
    fun `需要连续观测满确认时长才放行`() {
        val gate = PersistenceGate()
        assertNull("状态不成立时无需推迟", gate.observe(1_000L, active = false))

        val first = requireNotNull(gate.observe(1_000L, active = true))
        assertEquals(0L, first.heldMs)
        assertEquals(800L, first.remainingMs)

        val mid = requireNotNull(gate.observe(1_500L, active = true))
        assertEquals(500L, mid.heldMs)
        assertEquals(300L, mid.remainingMs)

        assertNull("满 800ms 视为确认", gate.observe(1_800L, active = true))
    }

    @Test
    fun `状态中断后重新计时`() {
        val gate = PersistenceGate()
        gate.observe(1_000L, active = true)
        gate.observe(1_400L, active = false)
        val again = requireNotNull(gate.observe(1_500L, active = true))
        assertEquals(0L, again.heldMs)
    }

    // ---------------- ScreenshotBudget ----------------

    @Test
    fun `预算内 1_5s 一次，用尽后降到 8s`() {
        val budget = ScreenshotBudget(maxPolls = 2)
        val first = requireNotNull(budget.takePoll(0L, cooldownUntil = 0L))
        assertEquals(1_500L, first.waitMs)
        assertEquals(1, first.pollsLeft)
        assertFalse(first.justExhausted)

        assertNull("未到下一次轮询时刻", budget.takePoll(1_000L, cooldownUntil = 0L))

        val second = requireNotNull(budget.takePoll(1_500L, cooldownUntil = 0L))
        assertTrue(second.justExhausted)
        assertEquals(0, second.pollsLeft)
        assertEquals(8_000L, second.waitMs)

        val idle = requireNotNull(budget.takePoll(9_500L, cooldownUntil = 0L))
        assertEquals(8_000L, idle.waitMs)
        assertFalse(idle.justExhausted)
    }

    @Test
    fun `动作冷却未结束时轮询要让路`() {
        val budget = ScreenshotBudget()
        val tick = requireNotNull(budget.takePoll(0L, cooldownUntil = 3_000L))
        assertEquals(3_000L, tick.waitMs)
    }

    @Test
    fun `结果有效期随预算用完降频`() {
        val budget = ScreenshotBudget(maxPolls = 1)
        assertEquals(2_500L, budget.freshWindowMs())
        budget.takePoll(0L, cooldownUntil = 0L)
        assertEquals(8_000L, budget.freshWindowMs())
    }

    // ---------------- ScreenshotProbe ----------------

    @Test
    fun `同一张图只计一次，命中后结果作废`() {
        val probe = ScreenshotProbe()
        assertTrue(probe.allowsRequest(10_000L))
        probe.markRequested(10_000L, hintWanted = true, pillarWanted = false)
        assertFalse("飞行中不重复请求", probe.allowsRequest(10_000L))

        probe.publish(emptyOrange(), emptyHint(), emptyPillar(), 10_100L)
        assertFalse("刚请求过要节流", probe.allowsRequest(10_200L))
        assertEquals(1L, probe.seq)
        assertTrue(probe.isFresh(10_200L))

        probe.consume()
        assertFalse("已作废的结果不能再用", probe.isFresh(10_200L))
    }

    @Test
    fun `换窗口后结果与预算一起作废`() {
        val probe = ScreenshotProbe()
        probe.markRequested(0L, hintWanted = false, pillarWanted = false)
        probe.publish(emptyOrange(), emptyHint(), emptyPillar(), 10L)
        probe.retryPending = true
        probe.takePoll(10L, cooldownUntil = 0L)

        probe.resetForNewWindow()
        assertFalse(probe.isFresh(20L))
        assertFalse(probe.retryPending)
        assertEquals(40, probe.pollsLeft)
    }
}
