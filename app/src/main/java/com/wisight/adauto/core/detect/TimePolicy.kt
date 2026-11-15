package com.wisight.adauto.core.detect

import com.wisight.adauto.core.vision.HintBarEvidence
import com.wisight.adauto.core.vision.OrangeEvidence
import com.wisight.adauto.core.vision.PillarboxEvidence

/**
 * “广告上滑链”：一次「划了没生效就补扫再划」的会话状态。
 *
 * 两段节奏：
 * - **入场期**（前 [fastAttempts] 次）：用最短间隔快速补扫，覆盖“上滑落在入场动画上没生效”；
 * - **看门狗期**（之后）：广告仍在屏但划不走——典型是“5s后可继续上滑观看”这类**滑动锁**
 *   （锁期内滑动一律无效，实测横屏广告锁期约 12s），改用较慢节奏一直重试，直到预算用尽。
 *
 * 为什么需要看门狗（2026-10-08 实测）：旧实现快速补扫 7 次（≈5s）就把预算烧光，
 * 然后掉进穿山甲 6000ms 冷却，白等到 12s 才划走 —— 用户感知就是
 * “明明锁早解除了、还要再等几秒”。
 *
 * 所有方法都显式接收 `now`，不读系统时钟 —— 时间政策可以脱离真机单测。
 */
class AdSwipeChain(
    private val minRetryGapMs: Long = 700L,
    private val fastAttempts: Int = 7,
    private val watchdogGapMs: Long = 1_500L,
    private val totalBudgetMs: Long = 40_000L,
    private val maxAttempts: Int = 30,
    private val streakResetMs: Long = 10_000L,
) {
    var streak = 0
        private set
    var chainStartedAt = 0L
        private set
    var lastSwipeAt = 0L
        private set

    private var active = false

    val isWatchdog: Boolean get() = streak >= fastAttempts

    /** 结束当前链（窗口切换 / 锁期等待 / 换广告时调用）。 */
    fun reset() {
        streak = 0
        chainStartedAt = 0L
        active = false
    }

    /** 距上次广告上滑超过 [streakResetMs] → 视为上一串广告结束，重新计数。 */
    fun resetIfStale(now: Long) {
        if (now - lastSwipeAt > streakResetMs) reset()
    }

    fun chainElapsed(now: Long): Long = if (!active) 0L else now - chainStartedAt

    private fun currentGap(): Long = if (streak < fastAttempts) minRetryGapMs else watchdogGapMs

    /**
     * 补扫重试：上一次上滑后广告仍在（没划走，或紧接着又是同形态广告）→ **不吃冷却**直接再划。
     * 安全性：能走到这里说明规则确实返回了广告动作（正剧保护在前），
     * 且页面里既没有正剧播放控件、也没有未结束的倒计时。
     */
    fun allowsRetry(now: Long): Boolean =
        streak in 1 until maxAttempts &&
            chainElapsed(now) <= totalBudgetMs &&
            now - lastSwipeAt >= currentGap()

    /** 排查用标记（纯 ASCII，方便 grep）。 */
    fun retryTag(isRetry: Boolean, now: Long): String {
        if (!isRetry) return ""
        return " [retry#${streak + 1} elapsed=${chainElapsed(now)}ms" +
            if (isWatchdog) " watchdog]" else "]"
    }

    /**
     * 记一次上滑，并给出下一次补扫的延迟。
     * @return `null` = 预算/硬上限用尽，停止补扫转正常冷却
     */
    fun onSwipe(now: Long): Long? {
        if (!active) {
            chainStartedAt = now
            active = true
        }
        streak += 1
        lastSwipeAt = now
        val inBudget = streak < maxAttempts && now - chainStartedAt <= totalBudgetMs
        return when {
            !inBudget -> null
            streak < fastAttempts -> minRetryGapMs
            else -> watchdogGapMs
        }
    }
}

/**
 * 动作冷却：两次跳过动作之间的最小间隔。
 *
 * 穿山甲广告（角标 / “立即领取”）与直播样式广告用更长冷却：
 * 广告被划走后按钮/渲染视图可能残留，若用默认 1500ms 会重复滑动。
 * 敏捷性由 [AdSwipeChain] 的链式补扫保证（窗口内不吃冷却），长冷却只作连划上限用尽后的兜底。
 */
class ActionCooldown(
    private val minIntervalMs: Long = 1_500L,
    private val pangleCooldownMs: Long = 6_000L,
    private val noticeIntervalMs: Long = 500L,
) {
    var nextAllowedAt = 0L
        private set

    /** 上一次真正执行动作的时刻（事件节流也读它） */
    var lastActionAt = 0L
        private set

    private var lastNoticeAt = 0L

    fun allows(now: Long): Boolean = now >= nextAllowedAt

    fun remaining(now: Long): Long = (nextAllowedAt - now).coerceAtLeast(50L)

    /** 倒计时/锁期等待时清掉旧冷却，保证锁一解除就能用“全新预算”立即划走。 */
    fun clear() {
        nextAllowedAt = 0L
    }

    fun arm(now: Long, longCooldown: Boolean) {
        nextAllowedAt = now + if (longCooldown) pangleCooldownMs else minIntervalMs
        lastActionAt = now
    }

    /**
     * “冷却中”提示/补扫的日志节流：content change 每秒可来十几次，
     * 不节流会把 logcat 刷爆。
     */
    fun noticeDue(now: Long): Boolean {
        if (now - lastNoticeAt < noticeIntervalMs) return false
        lastNoticeAt = now
        return true
    }
}

/** 持久性确认的一轮结果。 */
class PersistedHold(val heldMs: Long, val remainingMs: Long)

/**
 * 持久性确认：某个“缺失型”判据要**连续观测满** [confirmMs] 才允许动手。
 *
 * 稀疏树：广告→正剧的过渡帧实测 `nodes=26~28 textLen=0`、只持续约 400ms 也会满足。
 * 直播渲染视图：横屏正剧可能因为采集残留短暂读到 `rv=true`，同样是 400ms 量级。
 * 真实广告持续数秒以上，所以“连续满足 800ms”足以区分。
 */
class PersistenceGate(private val confirmMs: Long = 800L) {
    private var sinceAt = 0L

    /**
     * @param active 本轮是否仍处于该状态
     * @return `null` = 无需推迟（已确认 / 状态不成立）；否则给出还需等待多久
     */
    fun observe(now: Long, active: Boolean): PersistedHold? {
        if (!active) {
            sinceAt = 0L
            return null
        }
        if (sinceAt == 0L) sinceAt = now
        val held = now - sinceAt
        if (held >= confirmMs) return null
        return PersistedHold(held, confirmMs - held)
    }

    fun clear() {
        sinceAt = 0L
    }
}

/** 一次轮询预算的取用结果。 */
class PollTick(val waitMs: Long, val pollsLeft: Int, val justExhausted: Boolean)

/**
 * 截图轮询预算：静止广告的“续命轮询”节奏。
 *
 * 起因（2026-10-27）：横屏广告的视频层画在 surface 上、叠加文字不再刷新时，
 * 无障碍**一个事件都没有**；一旦上滑补扫链结束，服务就彻底停摆
 * （实测现场 logcat 静默 10 分钟以上，广告一直挂在那里不划）。
 *
 * 预算内 [pollMs] 一次；预算用完后降到 [idleMs] 一次（不完全停，保证“看了很久正剧
 * 之后突然出广告”也能发现）。同样按 时间戳/降频 两个量纲区分结果新鲜度。
 */
class ScreenshotBudget(
    val maxPolls: Int = 40,
    val pollMs: Long = 1_500L,
    val idleMs: Long = 8_000L,
    private val freshMs: Long = 2_500L,
) {
    var pollsLeft = maxPolls
        private set

    var nextPollAt = 0L
        private set

    fun reset() {
        pollsLeft = maxPolls
        nextPollAt = 0L
    }

    /** 结果有效期：预算内 [freshMs]（及时）；预算用完降到 [idleMs]。 */
    fun freshWindowMs(): Long = if (pollsLeft > 0) freshMs else idleMs

    /**
     * 取一次轮询预算。
     * @return `null` = 还没到下一次轮询时刻
     */
    fun takePoll(now: Long, cooldownUntil: Long): PollTick? {
        if (now < nextPollAt) return null
        var exhausted = false
        if (pollsLeft > 0) {
            pollsLeft--
            if (pollsLeft == 0) exhausted = true
        }
        val wait = when {
            now < cooldownUntil -> (cooldownUntil - now).coerceAtLeast(200L)
            pollsLeft > 0 -> pollMs
            else -> idleMs
        }
        nextPollAt = now + wait
        return PollTick(wait, pollsLeft, exhausted)
    }
}

/**
 * 截图探测的传输层：请求节流、飞行中防重入、结果发布与新鲜度。
 *
 * 字段跨两个线程（主线程请求 / 探测线程回调），所以是 `@Volatile` 的。
 * 这里的判定（[isFresh] / [allowsRetry]）都不读系统时钟，`now` 由调用方传入。
 */
class ScreenshotProbe(
    private val budget: ScreenshotBudget = ScreenshotBudget(),
    private val intervalMs: Long = 1_200L,
    val retryDelayMs: Long = 1_300L,
) {
    /** 每次成功测量递增：用于要求“两张不同截图”都命中 */
    @Volatile
    var seq = 0L
        private set

    @Volatile
    var last: ProbeFrame? = null
        private set

    /** 本次测量的时刻（0 = 当前无有效结果） */
    @Volatile
    var resultAt = 0L
        private set

    @Volatile
    var inFlight = false
        private set

    /** 本次截图是否要额外算提示栏 / 信箱（省一次全图遍历 / 色域转换） */
    @Volatile
    var hintWanted = false
        private set

    @Volatile
    var pillarWanted = false
        private set

    /** 是否已为当前测量排了“等结果回来再扫一次”的任务 */
    var retryPending = false

    private var lastRequestAt = 0L

    val pollsLeft: Int get() = budget.pollsLeft

    /** 轮询预算上限 / 降频间隔（日志与排查用） */
    val maxPolls: Int get() = budget.maxPolls
    val idleMs: Long get() = budget.idleMs

    fun allowsRequest(now: Long): Boolean = !inFlight && now - lastRequestAt >= intervalMs

    fun markRequested(now: Long, hintWanted: Boolean, pillarWanted: Boolean) {
        inFlight = true
        this.hintWanted = hintWanted
        this.pillarWanted = pillarWanted
        lastRequestAt = now
    }

    /** 探测线程回调：发布结果并释放飞行锁。 */
    fun publish(
        orange: OrangeEvidence,
        hint: HintBarEvidence,
        pillar: PillarboxEvidence,
        now: Long,
    ) {
        seq++
        last = ProbeFrame(seq, orange, hint, pillar)
        resultAt = now
        inFlight = false
    }

    fun isFresh(now: Long): Boolean =
        resultAt != 0L && now - resultAt <= budget.freshWindowMs()

    /** 结果已被消费（命中后作废，强制下一轮重新取图）。 */
    fun consume() {
        resultAt = 0L
    }

    fun takePoll(now: Long, cooldownUntil: Long): PollTick? = budget.takePoll(now, cooldownUntil)

    /** 已经动作过 = 新的一段广告，轮询预算重置。 */
    fun onActionTaken() {
        budget.reset()
    }

    /** 换窗口 / 换广告：结果与预算一起作废。 */
    fun resetForNewWindow() {
        resultAt = 0L
        retryPending = false
        budget.reset()
    }
}
