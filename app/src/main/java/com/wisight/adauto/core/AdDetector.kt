package com.wisight.adauto.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 广告检测器：遍历当前屏幕上各窗口的节点树，按 AdRules 匹配并执行跳过动作。
 */
class AdDetector(private val service: AccessibilityService) {

    companion object {
        const val TAG = "AdDetector"
        /**
         * 永不当作广告去检测的包：本应用自身 + 系统界面。
         * - 自身设置页/悬浮球含“自动跳过广告/广告快跳”等文字；
         * - 系统界面（通知栏/控制中心）会显示我们的常驻通知“正在自动跳过广告”，
         *   不排除会被误判成广告并点击，从而点开通知跳到设置页。
         */
        private val NON_AD_PACKAGES = setOf(
            "com.wisight.adauto",
            "com.android.systemui",
            "miui.systemui.plugin",
        )
        /** 窗口切换后延迟重扫的间隔 */
        private const val RETRY_DELAY_MS = 350L
        /** 倒计时结束后重扫的缓冲：给“上滑继续观看”等文案留出渲染时间 */
        private const val COUNTDOWN_BUFFER_MS = 300L
        /** 超过该秒数的倒计时不精确等待（异常文案，避免长时间挂起） */
        private const val MAX_COUNTDOWN_SECONDS = 30
        /** 点中心暂停后，等待“中间播放按钮出现”的确认超时（毫秒） */
        private const val PAUSE_CONFIRM_TIMEOUT_MS = 4_000L
        /** 已确认暂停后，等待底部“上滑”提示出现的最长时间（毫秒），超时则回退原逻辑 */
        private const val PAUSED_PROMPT_TIMEOUT_MS = 6_000L
        /** 一次暂停流程失败后，本广告内不再尝试点暂停的冷却时长（毫秒） */
        private const val PAUSE_FLOW_DISABLED_MS = 20_000L
        /** 暂停流程中各阶段的重扫间隔（毫秒） */
        private const val PAUSE_RESCAN_MS = 300L
        /**
         * 广告上滑补扫的两段节奏（毫秒）：
         * - **入场期**（前 [AD_SWIPE_FAST_ATTEMPTS] 次）：用最短间隔快速补扫，
         *   覆盖“上滑落在入场动画上没生效”的窗口；
         * - **看门狗期**（之后）：广告仍在屏但划不走——典型是“5s后可继续上滑观看”这类
         *   **滑动锁**（锁期内滑动一律无效，实测横屏广告锁期约 12s），改用较慢节奏一直重试，
         *   直到总预算/硬上限用尽。
         *
         * 为什么需要看门狗（2026-10-08 实测）：旧实现快速补扫 7 次（≈5s）就把预算烧光，
         * 然后掉进穿山甲 6000ms 冷却，白等到 12s 才划走 —— 用户感知就是
         * “明明锁早解除了、还要再等几秒”。
         */
        private const val AD_SWIPE_MIN_RETRY_GAP_MS = 700L
        private const val AD_SWIPE_FAST_ATTEMPTS = 7
        private const val AD_SWIPE_WATCHDOG_GAP_MS = 1_500L
        /** 一串广告的总补扫预算：超过就不再补扫，回到正常冷却（毫秒） */
        private const val AD_SWIPE_TOTAL_BUDGET_MS = 40_000L
        /** 一串广告里连续上滑的硬上限：兜底防病态循环 */
        private const val AD_SWIPE_MAX_ATTEMPTS = 30
        /** 距上次广告上滑超过该时长视为整串结束，重新计数（毫秒） */
        private const val AD_SWIPE_STREAK_RESET_MS = 10_000L

        /**
         * 悬浮球正在被拖动：拖动期间暂停广告检测，避免检测占用主线程导致拖动卡顿。
         * 拖动结束后由悬浮球触发一次补扫。
         */
        @Volatile
        var isDragging = false
    }

    /** 延迟重扫 / 倒计时等待的调度器（主线程） */
    private val handler = Handler(Looper.getMainLooper())
    private var retryPending = false
    /**
     * 倒计时等待的截止时刻（uptimeMillis）：进入倒计时后，滑动会一直推迟到这个时刻才执行。
     * 只在首次观测、或倒计时更早结束、或新一轮倒计时时更新，防止卡住的文案把等待无限拉长。
     */
    private var countdownDeadlineAt = 0L
    /** 上次解析到的倒计时秒数：用于识别新一轮倒计时（值变大 = 广告重新开始） */
    private var lastParsedSeconds = -1

    /**
     * 广告“点中心暂停”流程状态：
     * - NONE：正常 / 未进入；
     * - WAIT_PAUSE：已点击屏幕中间，等待中间播放按钮出现（确认已暂停）；
     * - PAUSED：已确认暂停（检测到播放按钮），等待底部“上滑继续观看”提示出现后按原逻辑划走。
     */
    private enum class PauseState { NONE, WAIT_PAUSE, PAUSED }

    private var pauseState = PauseState.NONE
    /** 点击屏幕中间的时刻（用于判断暂停确认是否超时） */
    private var pauseTappedAt = 0L
    /** 确认暂停的时刻（用于限制“已暂停但提示未出现”的等待时间） */
    private var pauseConfirmedAt = 0L
    /** 暂停流程失败后的禁用截止时刻：本广告内不再反复点中心，回退原倒计时逻辑 */
    private var pauseFlowDisabledUntil = 0L

    /** 延迟重扫任务：到点后重新检测一次（广告文案晚渲染 / 倒计时结束时的兜底） */
    private val retryRunnable = Runnable {
        retryPending = false
        if (SettingsManager.adSkipEnabled) {
            detectAndAct(isWindowStateChange = false)
        }
    }

    /** 两次跳过动作之间的最小间隔，避免重复触发 */
    private val minActionInterval = 1500L
    /**
     * 穿山甲广告（"立即领取"触发）的冷却时间：
     * 广告被滑走后"立即领取"按钮可能短暂残留，若用默认 1500ms 会重复滑动，
     * 这里给更长的冷却以留出广告消失/界面切换的时间。
     */
    private val pangleAdCooldown = 6000L
    /** 下次允许执行动作的时间戳 */
    private var nextAllowedAt = 0L
    private var lastActionAt = 0L
    /**
     * 广告连续上滑次数：“划了没生效就补扫再划”的计数（上限 AD_SWIPE_MAX_ATTEMPTS）。
     * 覆盖所有上滑类广告动作（直播渲染视图 / 穿山甲角标 / 立即领取 / “上滑继续观看”）。
     * 为 0 = 当前没有待补扫的广告。
     */
    private var adSwipeStreak = 0
    /** 上一次广告上滑的时刻：间隔过久视为新一轮广告，重新计数 */
    private var lastAdSwipeAt = 0L
    /** 当前“广告上滑链”的起始时刻：用于总预算判定（0 = 无进行中的链） */
    private var adSwipeChainStartedAt = 0L
    /** “冷却中”提示/补扫的节流时刻：content change 每秒可来十几次，不节流会刷爆日志 */
    private var lastCooldownNoticeAt = 0L

    /** 结束当前“广告上滑链”（窗口切换 / 锁期等待 / 换广告时调用） */
    private fun resetAdSwipeChain() {
        adSwipeStreak = 0
        adSwipeChainStartedAt = 0L
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!SettingsManager.adSkipEnabled) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "自动跳过已关闭，界面变化不检测")
            }
            return
        }
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // 拖动悬浮球期间不检测，避免占用主线程
        if (isDragging) return

        // 窗口切换时打印窗口结构，用于排查“穿山甲广告窗口”的可检测特征
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            logWindows()
            // 新窗口 = 新上下文：重置倒计时等待/暂停流程状态，避免跨广告沿用旧状态
            countdownDeadlineAt = 0L
            lastParsedSeconds = -1
            pauseState = PauseState.NONE
            pauseTappedAt = 0L
            pauseConfirmedAt = 0L
            pauseFlowDisabledUntil = 0L
            resetAdSwipeChain()
        }

        // 内容变化事件非常频繁，做节流
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastActionAt < 300) return

        detectAndAct(isWindowStateChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    /** 打印当前所有无障碍窗口的结构，排查广告窗口特征 */
    private fun logWindows() {
        try {
            val wins = service.windows
            val sb = StringBuilder("windows[${wins.size}]:")
            for (w in wins) {
                val root = w.root
                val pkg = root?.packageName?.toString().orEmpty()
                val cls = root?.className?.toString().orEmpty()
                sb.append(" {t=${w.type},a=${w.isActive},pkg=$pkg,cls=$cls}")
            }
            Log.i(TAG, sb.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "logWindows failed: $t")
        }
    }

    /** 供“立即检测”按钮调用 */
    fun scanOnce(onResult: (String) -> Unit = {}) {
        detectAndAct(onResult)
    }

    private fun detectAndAct(onResult: (String) -> Unit = {}, isWindowStateChange: Boolean = false) {
        // 拖动悬浮球期间暂停检测，保证拖动流畅；结束后由悬浮球补扫一次
        if (isDragging) return
        // 前台应用包名：先排除我们自己（设置页里含“自动跳过/广告快跳”等文字，会被误判成广告）。
        // 例如在设置页打开开关会触发界面变化事件，若不排除就会点到自己界面上的“跳过”/“关闭”。
        val fgPkg = foregroundPackage()
        if (fgPkg == null) {
            onResult("无障碍服务尚未就绪")
            return
        }
        if (fgPkg in NON_AD_PACKAGES) {
            onResult("当前界面是广告快跳自身或系统界面，无需检测")
            return
        }
        // 只在前台 App 属于“支持的包名”时才检测；通用模式下才对所有 App 检测（误触风险更高）。
        // 避免在普通 App（聊天/设置/桌面等）里把页面上的“广告/关闭/跳过”等字样误判成广告而自动点击。
        if (!SettingsManager.genericModeEnabled && fgPkg !in SettingsManager.supportedPackagesList()) {
            onResult("当前 App（$fgPkg）不在自动跳过范围内")
            return
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectFromAllWindows(nodes, fgPkg)
        if (nodes.isEmpty()) {
            onResult("无障碍服务尚未就绪")
            return
        }

        // 页面文本只拼接一次：关键字匹配与倒计时解析共用，避免重复遍历节点树导致主线程卡顿
        val pageText = AdRules.pageTextOf(nodes)
        val countdownFound = AdRules.hasCountdown(pageText)
        // 排查用：本次到底看到了什么（节点数 / 文字长度 / 是否含直播广告渲染视图）
        Log.d(
            TAG,
            "scan: fg=$fgPkg nodes=${nodes.size} textLen=${pageText.length} " +
                "renderView=${AdRules.hasAdRenderView(nodes)} " +
                "texView=${AdRules.hasAdRenderSurface(nodes)} " +
                "countdown=$countdownFound streak=$adSwipeStreak",
        )
        // 传入“活动窗口屏幕范围”，供“广告角标”等位置敏感特征做过滤。
        // 用窗口根节点边界（与节点坐标同一坐标系），比 displayMetrics 更可靠（横竖屏通用）。
        val frame = screenFrame()
        val action = AdRules.match(nodes, pageText, frame?.width() ?: 0, frame?.height() ?: 0)
        if (action != null) {
            // 直播样式广告（渲染视图判定）：页面无任何文字，既读不到倒计时、也没有可用的
            // 播放按钮，而点屏幕正中有“误入直播间”的风险 —— 清掉倒计时/暂停状态，直接划走。
            if (action.immediate) {
                if (countdownDeadlineAt != 0L || pauseState != PauseState.NONE) {
                    Log.i(TAG, "直播广告：清除倒计时/暂停状态，直接执行 ${action.type}")
                }
                countdownDeadlineAt = 0L
                lastParsedSeconds = -1
                pauseState = PauseState.NONE
            }
            // 已通过“点中心暂停”确认暂停的广告：滑动锁定已解除，即使底部仍有倒计时文案
            // 也直接划走（暂停后“上滑”提示即满足条件）。
            val pausedAndSwipe = pauseState == PauseState.PAUSED && action.type == AdActionType.SWIPE_UP
            // 命中“上滑”但被倒计时锁住 = “需要倒计时等待”的广告：
            // 新版策略是点击屏幕中间暂停视频（暂停后出现播放按钮），暂停即可直接划走，
            // 不必被动等倒计时走完。暂停流程若超时/不适用，则回退到原来的“等倒计时结束再滑”。
            if (!pausedAndSwipe && action.type == AdActionType.SWIPE_UP && withinCountdownWindow(pageText)) {
                // 倒计时/滑动锁（如“5s后可继续上滑观看”）期间不划：
                // 结束当前上滑链 + 清掉旧冷却，保证锁一解除就能用“全新预算”立即划走。
                resetAdSwipeChain()
                nextAllowedAt = 0
                // 显式“滑动锁”（如“5s后可继续上滑观看”）期间不点中心暂停：
                // 这种锁按时间解锁，暂停视频并不能解锁，反而在横屏全屏广告上有误触风险。
                if (!AdRules.hasSwipeLock(pageText) && enterPauseBeforeSwipe(fgPkg)) {
                    onResult("检测到需倒计时的广告，正在点中心暂停后直接划走")
                    recycleAll(nodes)
                    return
                }
                // 暂停流程不可用：按原逻辑等倒计时结束再滑动。
                // 同时清除旧动作的冷却，避免倒计时结束瞬间被上一次动作（如穿山甲 6000ms）挡住而延迟几秒。
                nextAllowedAt = 0
                val remain = countdownDeadlineAt - SystemClock.uptimeMillis()
                Log.i(TAG, "检测到倒计时，滑动推迟 ${remain}ms 后执行")
                scheduleRetry(remain.coerceAtLeast(50L), "倒计时结束")
                recycleAll(nodes)
                return
            }
            if (pauseState != PauseState.NONE) {
                // 已有可执行动作（能划走/能点），退出暂停等待流程
                Log.i(TAG, "暂停流程结束，执行动作 ${action.type} (${action.reason})")
                pauseState = PauseState.NONE
            }
            val isLiveAd = action.reason == AdRules.REASON_LIVE_AD
            // “广告上滑链式补扫”：不再只限直播广告 —— **所有上滑类广告动作**都适用
            // （直播渲染视图 / 穿山甲角标 / 立即领取 / “上滑继续观看”）。
            // 起因（2026-10-08）：横屏穿山甲角标广告连续上滑间隔实测 6~7s，正是 pangle 冷却；
            // 2026-09 加的补扫只挂在直播广告上，角标/立即领取拿不到，只能干等 6s。
            val isAdSwipe = action.type == AdActionType.SWIPE_UP
            val now = SystemClock.uptimeMillis()
            val sinceLastAdSwipe = now - lastAdSwipeAt
            // 距上次广告上滑已过很久 → 上一串广告结束，重新计数
            if (isAdSwipe && sinceLastAdSwipe > AD_SWIPE_STREAK_RESET_MS) resetAdSwipeChain()
            val chainElapsed = if (adSwipeChainStartedAt == 0L) 0L else now - adSwipeChainStartedAt
            // 入场期用最短间隔快速补扫；之后转入看门狗慢节奏（锁期很长，快划无意义且浪费事件）
            val chainGap = if (adSwipeStreak < AD_SWIPE_FAST_ATTEMPTS) {
                AD_SWIPE_MIN_RETRY_GAP_MS
            } else {
                AD_SWIPE_WATCHDOG_GAP_MS
            }
            // 补扫重试：上一次上滑后广告仍在（没划走，或紧接着又是同形态广告）→ 不吃冷却直接再划。
            // 安全性：能走到这里说明 AdRules.match 确实返回了广告动作（正剧保护在前），
            // 且页面里既没有正剧播放控件、也没有未结束的倒计时。
            val adSwipeRetry = isAdSwipe &&
                adSwipeStreak in 1 until AD_SWIPE_MAX_ATTEMPTS &&
                chainElapsed <= AD_SWIPE_TOTAL_BUDGET_MS &&
                sinceLastAdSwipe >= chainGap
            if (now < nextAllowedAt && !adSwipeRetry) {
                // 冷却中不再“静默 return”：排一次“冷却结束即补扫”。
                // 否则广告若是静止画面（没有 content change 事件），就只能干等下一条无障碍
                // 事件（实测静止广告可十几秒无事件），表现为“莫名其妙等几秒”。
                // 日志按 500ms 节流：content change 每秒可来十几次，不节流会把日志刷爆。
                if (now - lastCooldownNoticeAt >= 500L) {
                    lastCooldownNoticeAt = now
                    val remain = (nextAllowedAt - now).coerceAtLeast(50L)
                    Log.d(TAG, "动作冷却中（剩余 ${remain}ms），已排定冷却结束补扫：${action.reason}")
                    scheduleRetry(remain, "冷却结束补扫")
                }
                recycleAll(nodes)
                return
            }
            // 穿山甲广告（角标/“立即领取”触发）与直播样式广告都用更长冷却：
            // 广告被划走后按钮/渲染视图可能残留，若用默认 1500ms 会重复滑动。
            // 敏捷性由上面的“广告上滑链式补扫”保证（窗口内不吃冷却），
            // 长冷却只作为连划上限用尽后的兜底。
            val cooldown = if (isLiveAd || action.reason.contains("穿山甲广告")) {
                pangleAdCooldown
            } else {
                minActionInterval
            }
            nextAllowedAt = now + cooldown
            lastActionAt = now
            // 标记改成纯 ASCII，便于排查时 grep（控制台会把中文转码成乱码）
            val swipeTag = if (adSwipeRetry) {
                " [retry#${adSwipeStreak + 1} elapsed=${chainElapsed}ms" +
                    if (adSwipeStreak >= AD_SWIPE_FAST_ATTEMPTS) " watchdog]" else "]"
            } else {
                ""
            }
            Log.i(
                TAG,
                "匹配到广告: ${action.type} (${action.reason}) in $fgPkg$swipeTag",
            )
            // 打印匹配节点文本，便于排查（注意：穿山甲 SurfaceView 视频广告的文字不在无障碍树里）
            action.node?.let { n ->
                Log.i(TAG, "匹配节点 text=${n.text?.toString().orEmpty().take(20)} class=${n.className}")
            }
            val ok = perform(action)
            Log.i(TAG, "执行${if (ok) "成功" else "失败"}: ${action.type} (${action.reason})")
            onResult(if (ok) "检测到广告，已自动跳过（${action.reason}）" else "跳过动作执行失败")
            if (isAdSwipe) {
                // 上滑类广告（直播渲染视图 / 穿山甲角标 / 立即领取 / “上滑继续观看”）：
                // 上滑后主动补扫。若广告没被划走（同样的广告动作仍能命中），下一次检测会
                // 不吃冷却地再划 —— 解决“上滑落在入场动画/滑动锁期内没生效，结果被冷却拖住几秒”。
                // 入场期快节奏 → 看门狗慢节奏，直到总预算或硬上限用尽。
                if (adSwipeChainStartedAt == 0L) adSwipeChainStartedAt = now
                adSwipeStreak += 1
                lastAdSwipeAt = now
                val chainElapsedNow = now - adSwipeChainStartedAt
                val inBudget = adSwipeStreak < AD_SWIPE_MAX_ATTEMPTS &&
                    chainElapsedNow <= AD_SWIPE_TOTAL_BUDGET_MS
                if (!inBudget) {
                    Log.i(TAG, "连续上滑 $adSwipeStreak 次 / 累计 ${chainElapsedNow}ms，停止补扫（转正常冷却）")
                } else if (adSwipeStreak < AD_SWIPE_FAST_ATTEMPTS) {
                    scheduleRetry(AD_SWIPE_MIN_RETRY_GAP_MS, "广告上滑补扫")
                } else {
                    scheduleRetry(AD_SWIPE_WATCHDOG_GAP_MS, "广告滑动锁看门狗")
                }
            }
        } else {
            // 注意：这里**不**重置 adSwipeStreak —— 已排定的“广告上滑补扫”可能就在几百毫秒后，
            // 若此刻清零，补扫那一次就会被冷却挡住，等于白排。改由 AD_SWIPE_STREAK_RESET_MS
            // （距上次广告上滑超过该时长）自动重置，既简单又不会误绕冷却。
            // 没有可直接点击/划走的按钮，需要区分两类情况：
            // A) 正剧播放中的前置提示“X秒后进入广告/即将播放广告” —— 广告还没开始、
            //    正剧仍在播放。此时绝不能点中心暂停（会把正剧暂停），只能照常等倒计时；
            // B) 真正的广告页（倒计时等待型）→ 进入“点中心暂停”流程。
            val upcomingAd = AdRules.hasUpcomingAd(pageText)
            val inPlayback = AdRules.hasPlaybackControls(pageText)
            // 暂停流程进行中若发现已回到正剧 / “即将进入广告”前置界面，说明不是可暂停的广告，
            // 退出暂停流程，避免误暂停正剧后一直挂在等待里。
            if (pauseState != PauseState.NONE && (inPlayback || upcomingAd)) {
                Log.i(TAG, "已回到正剧/“即将进入广告”前置界面，退出暂停流程")
                pauseState = PauseState.NONE
            }
            // 只在真正的广告页（非前置提示、非正剧播放）里尝试“点中心暂停”。
            // 显式“滑动锁”（如“5s后可继续上滑观看”）期间不点中心暂停：
            // 这种锁按时间解锁，暂停视频并不能解锁；在横屏全屏广告上点中心还有误触广告创意的风险，
            // 而且暂停流程本身的确认超时（4s+6s）会先白等十秒。交给倒计时逻辑：锁一解除立即划走。
            val swipeLocked = AdRules.hasSwipeLock(pageText)
            if (swipeLocked) Log.d(TAG, "广告处于滑动锁状态（后可继续上滑观看），等待锁解除")
            if ((countdownFound && !upcomingAd && !inPlayback && !swipeLocked) ||
                pauseState != PauseState.NONE
            ) {
                if (enterPauseBeforeSwipe(fgPkg)) {
                    onResult("检测到广告上下文，正在暂停视频以直接跳过")
                    recycleAll(nodes)
                    return
                }
            }
            onResult("当前界面未检测到广告")
            maybeScheduleRetry(fgPkg, isWindowStateChange, pageText)
        }
        recycleAll(nodes)
    }

    /**
     * 当前前台应用的包名：优先取活动窗口的根节点包名，失败时退回 rootInActiveWindow。
     * 用于把检测范围限定在前台应用，丢弃后台应用/系统设置窗口的残留文字。
     */
    private fun foregroundPackage(): String? {
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            emptyList()
        }
        for (w in windows) {
            if (w.isActive) {
                w.root?.packageName?.toString()?.let { return it }
            }
        }
        return try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 当前“屏幕范围”：活动应用窗口根节点的边界（与节点坐标同一坐标系）。
     * 用于给“广告角标”等位置敏感特征做过滤；横竖屏都正确。
     * 找不到活动应用窗口时退回 displayMetrics 的宽高。
     */
    private fun screenFrame(): Rect? {
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            emptyList()
        }
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive) continue
            val root = w.root ?: continue
            try {
                val r = Rect()
                root.getBoundsInScreen(r)
                if (!r.isEmpty) return r
            } finally {
                @Suppress("DEPRECATION")
                try { root.recycle() } catch (_: Throwable) {}
            }
        }
        // 兜底：用资源里的屏幕尺寸
        val dm = service.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /**
     * 未命中广告时安排一次延迟重扫，时机优先由倒计时精确控制：
     * - 有倒计时（如“5秒后可继续”）：等它到 0 再重扫（+小缓冲），把滑动时机精确控制在倒计时结束瞬间；
     *   倒计时文案逐秒刷新，content change 事件会不断刷新这里的调度，保证始终跟踪最新剩余时间。
     * - 无倒计时：保留原有兜底——窗口切换后 350ms 重扫一次（仅已知短剧应用，广告文案常晚 1~2 帧渲染）。
     */
    private fun maybeScheduleRetry(
        fgPkg: String,
        isWindowStateChange: Boolean,
        pageText: String,
    ) {
        if (withinCountdownWindow(pageText)) {
            // 倒计时说明广告仍在展示：清除旧动作冷却，避免“倒计时结束瞬间”被上一次动作挡住而延迟几秒。
            // 同时结束上滑链：锁期（如“5s后可继续上滑观看”）内的划动一律无效，
            // 若把锁期当成“划了没生效”消耗掉补扫预算，锁一解除就会掉进 6s 冷却 ——
            // 这正是“明明锁已经解除、还要再等几秒才划走”的根因（2026-10-08）。
            nextAllowedAt = 0
            resetAdSwipeChain()
            val remain = countdownDeadlineAt - SystemClock.uptimeMillis()
            Log.i(TAG, "检测到倒计时，${remain}ms 后重扫")
            scheduleRetry(remain.coerceAtLeast(50L), "倒计时结束")
            return
        }
        if (!isWindowStateChange) return
        // 窗口切换延迟重扫只在支持的短剧/视频 App 内做（广告文案常晚 1~2 帧渲染）；
        // 通用模式下其它 App 不做额外重扫，减少误触与主线程开销。
        if (fgPkg !in SettingsManager.supportedPackagesList()) return
        scheduleRetry(RETRY_DELAY_MS, "窗口切换")
    }

    /**
     * 更新倒计时等待状态，并判断当前是否应“等倒计时结束再动作”。
     * 返回 true = 还在倒计时内（推迟动作）；false = 无倒计时 / 倒计时已到点（可以动手了）。
     * 关键设计：
     * - 一旦进入倒计时等待，即使某次快照没抓到倒计时文字，也保持等待到截止时刻，避免文案闪烁导致提前滑动；
     * - 截止时刻只在“首次观测”或“更早结束”时提前，卡住的文案不会无限拉长等待；
     * - 解析到更大的秒数（新一轮倒计时 / 广告重新开始）会更新截止时刻。
     */
    private fun withinCountdownWindow(pageText: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val remaining = AdRules.remainingCountdownSeconds(pageText)
        if (remaining != null && remaining in 1..MAX_COUNTDOWN_SECONDS) {
            val deadline = now + remaining * 1000L + COUNTDOWN_BUFFER_MS
            val isRestart = remaining > lastParsedSeconds // 比上次更长 = 新一轮倒计时
            val isEarlier = countdownDeadlineAt != 0L && deadline < countdownDeadlineAt
            if (countdownDeadlineAt == 0L || isRestart || isEarlier) {
                countdownDeadlineAt = deadline
            }
            lastParsedSeconds = remaining
        }
        if (countdownDeadlineAt != 0L && now < countdownDeadlineAt) return true
        countdownDeadlineAt = 0L
        lastParsedSeconds = -1
        return false
    }

    /** 取消旧任务并重新安排一次延迟重扫（始终替换，保证跟踪最新剩余时间） */
    private fun scheduleRetry(delay: Long, reason: String) {
        handler.removeCallbacks(retryRunnable)
        retryPending = true
        Log.d(TAG, "延迟重扫：${delay}ms 后 ($reason)")
        handler.postDelayed(retryRunnable, delay)
    }

    /**
     * “需要倒计时等待”的广告：通过“点击屏幕中间让视频暂停”立即解锁滑动。
     *
     * 策略：遇到需倒计时等待的广告，先点一下屏幕中间暂停视频（暂停状态下中间会出现
     * 播放按钮，用它判定“已暂停”）；暂停后底部“上滑继续观看短剧”提示立即变为可滑动，
     * 后续仍走原逻辑——检测到提示满足后自动上滑划走，无需被动等倒计时走完。
     *
     * @return true=已接管调度（已点暂停/正在等待确认/已暂停等待提示）；
     *         false=确认超时/不适用，调用方应回退到原“倒计时等待”逻辑。
     */
    private fun enterPauseBeforeSwipe(fgPkg: String): Boolean {
        val now = SystemClock.uptimeMillis()
        when (pauseState) {
            PauseState.NONE -> {
                // 本广告的暂停流程已失败过：不再反复点中心，直接回退原倒计时逻辑
                if (now < pauseFlowDisabledUntil) return false
                if (hasCenterPlayButton(fgPkg)) {
                    Log.i(TAG, "广告已处于暂停态（中间出现播放按钮），等待下方上滑提示")
                    pauseState = PauseState.PAUSED
                    pauseConfirmedAt = now
                } else {
                    Log.i(TAG, "倒计时广告：点击屏幕中间暂停视频")
                    tapCenter()
                    pauseState = PauseState.WAIT_PAUSE
                    pauseTappedAt = now
                }
                scheduleRetry(PAUSE_RESCAN_MS, "暂停确认/等待")
                return true
            }
            PauseState.WAIT_PAUSE -> {
                if (hasCenterPlayButton(fgPkg)) {
                    Log.i(TAG, "已确认暂停（检测到中间播放按钮），等待下方上滑提示出现")
                    pauseState = PauseState.PAUSED
                    pauseConfirmedAt = now
                    scheduleRetry(PAUSE_RESCAN_MS, "已暂停等待提示")
                } else if (now - pauseTappedAt < PAUSE_CONFIRM_TIMEOUT_MS) {
                    scheduleRetry(PAUSE_RESCAN_MS, "等待播放按钮出现")
                } else {
                    Log.w(TAG, "暂停确认超时，本广告回退倒计时等待逻辑")
                    pauseState = PauseState.NONE
                    pauseFlowDisabledUntil = now + PAUSE_FLOW_DISABLED_MS
                    return false
                }
                return true
            }
            PauseState.PAUSED -> {
                // 已暂停：短周期重扫，直到底部“上滑继续观看”提示渲染出来（由 action 分支划走）。
                // 若提示长时间未出现，说明该广告暂停并不能解锁滑动，回退原倒计时逻辑。
                if (now - pauseConfirmedAt < PAUSED_PROMPT_TIMEOUT_MS) {
                    scheduleRetry(PAUSE_RESCAN_MS, "等待上滑提示")
                    return true
                }
                Log.w(TAG, "已暂停但长时间未出现上滑提示，本广告回退倒计时等待逻辑")
                pauseState = PauseState.NONE
                pauseConfirmedAt = 0L
                pauseFlowDisabledUntil = now + PAUSE_FLOW_DISABLED_MS
                return false
            }
        }
    }

    /** 点击屏幕正中央（用于暂停倒计时广告的视频） */
    private fun tapCenter(): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels * 0.5f
        val cy = dm.heightPixels * 0.5f
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        Log.i(TAG, "tapCenter($cx,$cy)")
        return service.dispatchGesture(gesture, null, null)
    }

    /**
     * 检测屏幕中央是否出现“播放按钮”（视频已暂停的标志）。
     * 倒计时广告点击屏幕暂停后，视频层中央会出现一个居中的播放图标
     * （多为无文字的 ImageView），用它判定“视频已暂停”。
     * 返回 true 表示中央区域存在播放按钮。
     */
    private fun hasCenterPlayButton(fgPkg: String): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        // 播放按钮精确居中，允许的中央区域（观察值约为屏宽 15~20% 的方块）
        val halfW = dm.widthPixels * 0.24f
        val halfH = dm.heightPixels * 0.14f
        val minSize = dm.widthPixels * 0.06f
        val maxSize = dm.widthPixels * 0.5f
        val visited = ArrayList<AccessibilityNodeInfo>()
        try {
            val windows = try {
                service.windows
            } catch (_: Throwable) {
                emptyList()
            }
            var found = false

            fun scan(root: AccessibilityNodeInfo) {
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                stack.add(root)
                while (stack.isNotEmpty() && !found) {
                    if (isDragging) return
                    val node = stack.removeLast()
                    visited.add(node)
                    if (!node.isVisibleToUser) continue
                    val cls = node.className?.toString().orEmpty()
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    val isPlayText = text.contains("播放") || desc.contains("播放") ||
                        text.contains("play", ignoreCase = true) || desc.contains("play", ignoreCase = true)
                    // 候选：无文字的居中图片节点（播放按钮），或文字/描述含“播放”的节点
                    val isImageCandidate = cls.contains("Image") && text.isEmpty() && desc.isEmpty()
                    if (isImageCandidate || isPlayText) {
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        if (!r.isEmpty) {
                            val w = r.width().toFloat()
                            val h = r.height().toFloat()
                            if (w >= minSize && w <= maxSize && h >= minSize && h <= maxSize) {
                                val nx = (r.left + r.right) / 2f
                                val ny = (r.top + r.bottom) / 2f
                                if (kotlin.math.abs(nx - cx) <= halfW &&
                                    kotlin.math.abs(ny - cy) <= halfH
                                ) {
                                    Log.i(TAG, "hasCenterPlayButton: $cls [${r.left},${r.top}][${r.right},${r.bottom}]")
                                    found = true
                                }
                            }
                        }
                    }
                    for (i in 0 until node.childCount) {
                        stack.add(node.getChild(i) ?: continue)
                    }
                }
            }

            for (win in windows) {
                if (found) break
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.type != AccessibilityWindowInfo.TYPE_SYSTEM
                ) continue
                val root = win.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg in NON_AD_PACKAGES) continue
                if (!win.isActive && pkg != fgPkg) continue
                scan(root)
            }
            // 兜底：windows 列表为空时退回活动窗口
            if (!found) {
                service.rootInActiveWindow?.let { scan(it) }
            }
            return found
        } catch (t: Throwable) {
            Log.w(TAG, "hasCenterPlayButton failed: $t")
            return false
        } finally {
            recycleAll(visited)
        }
    }

    private fun perform(action: AdAction): Boolean {
        return when (action.type) {
            AdActionType.CLICK -> {
                val node = action.node ?: return false
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) {
                    false
                }
            }
            AdActionType.SWIPE_UP -> swipe(up = true)
            AdActionType.SWIPE_DOWN -> swipe(up = false)
            AdActionType.SWIPE_LEFT -> swipe(horizontal = true, toRight = false)
            AdActionType.SWIPE_RIGHT -> swipe(horizontal = true, toRight = true)
            AdActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun swipe(up: Boolean = true, horizontal: Boolean = false, toRight: Boolean = true): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val path = Path()
        if (horizontal) {
            val fromX = if (toRight) w * 0.2f else w * 0.8f
            val toX = if (toRight) w * 0.8f else w * 0.2f
            path.moveTo(fromX, h * 0.5f)
            path.lineTo(toX, h * 0.5f)
        } else {
            val fromY = if (up) h * 0.75f else h * 0.25f
            val toY = if (up) h * 0.25f else h * 0.75f
            path.moveTo(w * 0.5f, fromY)
            path.lineTo(w * 0.5f, toY)
            Log.i(TAG, "swipe: display=${w}x$h path=(${w * 0.5f},$fromY)->(${w * 0.5f},$toY)")
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun collectNodes(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            // 拖动悬浮球已开始：立即中止本次检测，把主线程让给拖动
            if (isDragging) return
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            // 只保留“带文字/内容描述”或“带资源 id”的节点，可大幅减少节点数与主线程开销（拖动悬浮球更跟手）。
            // 必须额外保留带资源 id 的节点：直播样式广告的渲染视图（TextureView
            // `ttlive_player_render_view`）没有任何文字，只能靠资源 id 识别（见 AdRules.hasAdRenderView）。
            // 同理必须保留 TextureView 类节点：2026-10 的试玩/游戏广告把创意画在**没有 id 的**
            // TextureView 上，只能靠类名识别（见 AdRules.hasAdRenderSurface）。
            if (!node.text.isNullOrEmpty() ||
                !node.contentDescription.isNullOrEmpty() ||
                !node.viewIdResourceName.isNullOrEmpty() ||
                node.className?.toString()?.contains(AdRules.AD_RENDER_VIEW_CLASS) == true
            ) {
                out.add(node)
            }
            for (i in 0 until node.childCount) {
                stack.add(node.getChild(i) ?: continue)
            }
        }
    }

    /**
     * 收集当前可见的交互窗口节点，只保留两类：
     * 1) 活动窗口（广告弹窗、穿山甲广告 Activity 通常是活动窗口）
     * 2) 与前台应用同包名的窗口（同应用内的弹窗/WebView 浮层）
     * 丢弃后台应用/系统设置窗口里残留的广告文字，避免在非播放场景误触发滑动/点击。
     */
    private fun collectFromAllWindows(out: MutableList<AccessibilityNodeInfo>, fgPkg: String) {
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            emptyList()
        }
        for (win in windows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                win.type != AccessibilityWindowInfo.TYPE_SYSTEM
            ) continue
            val root = win.root ?: continue
            val pkg = root.packageName?.toString()
            // 跳过系统界面/自身窗口：通知栏里会出现我们的通知文案，避免误判成广告点击
            if (pkg in NON_AD_PACKAGES) continue
            if (!win.isActive && pkg != fgPkg) continue
            collectNodes(root, out)
        }
        // 兜底：某些设备上 windows 列表为空时退回活动窗口
        if (out.isEmpty()) {
            service.rootInActiveWindow?.let { collectNodes(it, out) }
        }
    }

    @Suppress("DEPRECATION") // recycle() 对旧系统仍必要，API 33+ 上为 no-op
    private fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        for (n in nodes) {
            try {
                n.recycle()
            } catch (_: Throwable) {
            }
        }
    }
}
