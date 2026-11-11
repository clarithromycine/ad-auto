package com.wisight.adauto.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

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
         * “稀疏树广告”的持久性确认时长（毫秒）。
         * 该判据是“缺失型”的（节点少 + 文字少 + 无正剧控件），**广告 → 正剧的过渡帧**
         * 实测 `nodes=26~28 textLen=0`、只持续约 400ms 也会满足；而真实广告持续数秒以上。
         * 因此要求该状态连续满足至少这么久才动手，避免把正剧划走。
         */
        private const val AD_ITEM_CONFIRM_MS = 800L

        /**
         * 横屏“橙色按钮”视觉兜底（截图 + 颜色统计，不做 OCR）。
         * 用于横屏全屏时正剧控件被隐藏、节点树分不出广告/正剧的情况
         * （实测横屏广告的无障碍树：50 节点 / 0 文字，连“上滑继续看剧集”都读不到）。
         *
         * 判据不是“橙色像素占比”（实测正剧画面反而更高：横屏正剧 9.8% > 广告 7.2%），
         * 而是“能不能看到一个实心橙色宽条”，即最大橙色连通块要：
         * - 填充率高（实心按钮 vs 画面里散的橙色物体）
         * - 够宽、够矮、宽高比大（横条按钮）
         * - 位置在中下部（不在顶部标题区）
         * 实测：“查看详情”按钮 = 2082x108、填充率 0.97；横屏正剧最大块 = 336x360、填充率 0.40。
         */
        private const val ORANGE_SAMPLE_STEP = 4
        /** 连通块填充率下限（实心按钮） */
        private const val ORANGE_FILL_MIN = 0.80f
        /** 宽度至少占屏宽的比例 */
        private const val ORANGE_MIN_WIDTH_FRAC = 0.25f
        /** 高度绝对下限（像素） */
        private const val ORANGE_MIN_HEIGHT_PX = 20
        /** 高度上限占屏高比例（再高就不是按钮了，是整块画面） */
        private const val ORANGE_MAX_HEIGHT_FRAC = 0.30f
        /** 宽高比下限（横条） */
        private const val ORANGE_MIN_ASPECT = 2.5f
        /** 连通块中心 Y 至少在这个比例以下（避让顶部标题/角标） */
        private const val ORANGE_MIN_CENTER_Y_FRAC = 0.30f

        /**
         * 形态 B：**右下角的小实心橙色按钮**（2026-10-27 新增）。
         * 实测样本：横屏直播/电商广告右下角「查看详情」CTA = `216x84`（屏 2670x1200，
         * 即宽 8.1% / 高 7.0%）、填充 0.82、中心X 0.85、中心Y 0.92 ——
         * 宽度远不到形态 A 的 25% 屏宽门槛，且**不是画面里最大的橙色块**
         * （最大的那块是 224x132 填充 0.56 的零碎橙色，会把它盖掉），
         * 所以必须逐块判定 + 单独一套几何约束。
         * 对照横屏正剧（同机实测）：右下角区域最大块 200x124 填充 0.61、中心X 0.42 → 不命中。
         */
        private const val ORANGE_CTA_FILL_MIN = 0.75f
        private const val ORANGE_CTA_MIN_WIDTH_FRAC = 0.05f
        private const val ORANGE_CTA_MAX_WIDTH_FRAC = 0.20f
        private const val ORANGE_CTA_MIN_HEIGHT_FRAC = 0.03f
        private const val ORANGE_CTA_MAX_HEIGHT_FRAC = 0.15f
        /** 中心 X 在屏幕右侧（≥ 该比例） */
        private const val ORANGE_CTA_MIN_CENTER_X_FRAC = 0.75f
        /** 中心 Y 在屏幕底部（≥ 该比例） */
        private const val ORANGE_CTA_MIN_CENTER_Y_FRAC = 0.80f
        /** 小于该格数的连通块视为噪点，不参与判定 */
        private const val ORANGE_MIN_CELLS = 4
        /**
         * **颜色纯度**判据（2026-10-27 用户提出）：“短剧画面里的橙色块不会是一个色值，
         * 但按钮色块一定是同一个色值”。把橙块内像素相对块内均值的偏离限定在 ±该值内，
         * 再统计带内占比。实测（步长 4px、屏幕 2670x1200）:
         * - 广告右下角 CTA：均值 #EF8E3E，最大标准差 6.9，±12 带内占比 **0.98**
         * - 广告里的视频橙块：均值 #BD8953，标准差 17.8，带内占比 **0.38**
         * - 正剧里的橙块：均值 #A1744F，标准差 12.8，带内占比最高 **0.76**
         * 所以纯度是比“形状/填充率”分离度大得多的判据。
         */
        private const val ORANGE_COLOR_TOL = 12
        /** 纯度下限：低于它就不叫“按钮”而是画面里的同色系物体 */
        private const val ORANGE_UNIFORMITY_MIN = 0.85f
        /**
         * **近似矩形**判据（2026-10-27 用户提出：“按钮一定是一个近似矩形”）。
         * 只看填充率不够 —— 斜向色带/L 形/圆弧也能填满外接框一半。矩形要做的是
         * “每一行的横向跨度都接近外接框宽度，每一列的纵向跨度都接近外接框高度”。
         * 实测: 广告 CTA 行跨 1.00 / 列跨 0.94；视频橙块行跨 0.00~0.59、列跨 0.26~0.70。
         */
        /** 单行/单列的跨度达到外接框该方向尺寸的这个比例，才算“这一行是满的” */
        private const val ORANGE_RECT_SPAN_FRAC = 0.80f
        /**
         * 行/列攠“满”的比例下限。
         * 实测（**App 内截图像素**，盒平均后）：真广告按钮 行跨 1.00 / 列跨 0.98、
         * 填充 0.97；正剧块 填充≤0.50、行跨≤0.30、列跨≤0.35 → 分离度极大。
         * 注意：这两个几何量在 `adb screencap` 与 App `takeScreenshot` 两条路径下
         * **完全一致**（同一根按钮两边都是填充 0.97 / 行跨 1.00 / 列跨 0.98），
         * 所以几何是可靠判据，颜色不是（见下方说明）。
         */
        private const val ORANGE_RECT_ROW_COVER_MIN = 0.90f
        private const val ORANGE_RECT_COL_COVER_MIN = 0.88f
        /** 截图探测最小间隔（系统本身也限制截图频率） */
        private const val ORANGE_PROBE_INTERVAL_MS = 1_200L
        /** 截图结果的有效期 */
        private const val ORANGE_RATIO_FRESH_MS = 2_500L
        /** 探测后重扫的延迟（等截图回调回来） */
        private const val ORANGE_RETRY_MS = 1_300L
        /** 需要两张“不同截图”都命中才判定（避免视频画面偶然偏橙误判） */
        private const val ORANGE_HITS_REQUIRED = 2

        /**
         * 底部「∧ 上滑继续看短剧」提示栏的视觉判据（截图 + 亮度统计，不做 OCR）。
         *
         * 为什么需要：2026-11-07 用户提供的两条**游戏/试玩广告**（横屏 1280x720、竖屏各一）
         * 创意由游戏引擎自绘，无障碍树 `textLen=0`，所有读字规则全废，而画面底部
         * 明明盖着一条纯黑底 + 居中白字的「∧ 上滑继续看短剧」提示栏。
         *
         * ⚠️ **2026-11-08 修正（横屏正剧被划走）**：这条判据曾用「栏高 ≤20%、黑 ≥0.90」，
         * 结果在横屏正剧上误判 —— 实测现场 `栏高 19.8% 黑 0.91 白 0.0096 跨度 0.12 中心 0.50`
         * 五条全部擦边通过。那条 19.8% 的"黑带"其实是**暗场景视频内容 + 居中白色字幕**：
         * 字幕烧在视频帧里，无障碍读不到（所以 `textLen=0` 挡不住），像素统计却和提示栏一模一样。
         * 现在加两道护栏：
         * 1. **必须有视频层的页面才不参判** —— 由调用方保证（见 contentlessAmbiguous）：
         *    正剧播放必然挂 SurfaceView/TextureView，提示栏只出现在自绘创意上；
         * 2. 阈值收紧：栏高上限 0.20→**0.14**、黑底下限 0.90→**0.95**
         *    （广告实测 9.5%/10.5%、0.970/0.984；正剧误判帧 19.8%、0.91）。
         *
         * 阈值来源（两份广告样本实测）：
         * - 栏高占比 9.5% / 10.5%         → 区间 [4%, 14%]（排除系统手势条 ~1.5%、排除字幕黑区 ~20%）
         * - 黑底占比 0.970 / 0.984        → 下限 0.95
         * - 白字占比 0.0019 / 0.0047      → 区间 [0.0006, 0.10]（纯黑边 0 会被排除）
         * - 白字横向跨度 0.17 / 0.10      → 区间 [0.05, 0.55]
         *   （系统三键导航栏的图标横跨约 0.8，会被这一条排除）
         * - 白字水平中心 0.54 / 0.50      → 区间 [0.30, 0.70]（提示栏文字是居中的）
         */
        private const val HINT_BAR_SAMPLE_STEP = 4
        private const val HINT_BAR_ROW_DARK_MIN = 0.75f
        private const val HINT_BAR_MIN_HEIGHT_FRAC = 0.04f
        private const val HINT_BAR_MAX_HEIGHT_FRAC = 0.14f
        private const val HINT_BAR_DARK_MIN = 0.95f
        private const val HINT_BAR_BRIGHT_MIN = 0.0006f
        private const val HINT_BAR_BRIGHT_MAX = 0.10f
        private const val HINT_BAR_TEXT_SPAN_MIN = 0.05f
        private const val HINT_BAR_TEXT_SPAN_MAX = 0.55f
        private const val HINT_BAR_TEXT_CENTER_MIN = 0.30f
        private const val HINT_BAR_TEXT_CENTER_MAX = 0.70f
        /**
         * 底色纯度：只统计暗像素，按 4bit/通道量化（16×16×16 = 4096 桶），
         * 要求主色桶占比 ≥ 该值。提示栏是 App 画的纯色 UI 条（实测 0.99、5 个桶），
         * 暗场景视频是渐变+噪点（实测 0.48~0.51、40+ 个桶）。
         * 竖屏广告样本经聊天软件二次压缩后降到 0.716，所以阈值取 0.65，两侧都留余量。
         */
        private const val HINT_BAR_DOMINANT_MIN = 0.65f
        private const val HINT_BAR_HISTOGRAM_BUCKETS = 4096
        /** 与橙色按钮一致：需要两张不同截图都命中 */
        private const val HINT_BAR_HITS_REQUIRED = 2
        /**
         * 「横屏容器里塞了一个竖屏素材」（pillarbox）的视觉判据（截图，不做 OCR）。
         *
         * 为什么需要（2026-11-09 用户样本）：横屏 3200x2136 里放一个竖屏直播电商创意，
         * 左右用同一帧的模糊放大填充。无障碍树 `textLen=0`，而四条结构判据全灭：
         * TextureView 判据仅限竖屏；稀疏树也仅限竖屏且要求 `!scrollable`（本例 `scroll=true`）；
         * 横屏无视频层要求 `surface=false`（本例 `surface=true`）。
         *
         * 判据：**中部横条**内比较左/中/右三个窗口的横向梯度能量。
         * - 竖屏素材的左右边界是一条竖直线，任意水平切片都看得到 → 裁掉上下不丢信息；
         * - **必须裁**：底部电商信息/讲解条会把中央能量抬高（假阳性），
         *   而“全新正品 全国联保 七天无理由”这类横排小字会横跨左右模糊带（假阴性），
         *   顶部返回箭头/菜单图标则正好落在左右窗口里（同样压低保真度）；
         * - 用中位数而不是均值：残余的高对比文字不会把统计拉偏。
         */
        private const val PILLAR_SAMPLE_STEP = 4
        /** 中部条带上下界（占屏高比例）：取中间 50%，避开顶部 chrome 与底部信息区 */
        private const val PILLAR_BAND_TOP_FRAC = 0.25f
        private const val PILLAR_BAND_BOTTOM_FRAC = 0.75f
        /** 左/中/右三个窗口（占屏宽比例）。9:16 满高素材在 3:2 与 16:10 屏上都覆盖 32%~68% */
        private const val PILLAR_LEFT_X0 = 0.02f
        private const val PILLAR_LEFT_X1 = 0.10f
        private const val PILLAR_CENTER_X0 = 0.42f
        private const val PILLAR_CENTER_X1 = 0.58f
        private const val PILLAR_RIGHT_X0 = 0.90f
        private const val PILLAR_RIGHT_X1 = 0.98f
        /** 中央/两侧能量比下限（保守初值：宁漏勿误划，现场打点后再放宽） */
        private const val PILLAR_SPREAD_MIN = 3.0f
        /** 中央窗口“纵向贯通”的行占比下限：挡住“只有中间几行有字（烧录字幕）”的误判 */
        private const val PILLAR_FILL_MIN = 0.90f
        /** 中央窗口能量绝对下限（相邻采样列亮度差中位数）：挡住纯黑/纯白/纯色转场帧 */
        private const val PILLAR_ENERGY_MIN = 4.0f
        /** 判“该行有内容”时，行能量相对中央中位数的比例 */
        private const val PILLAR_ROW_FILL_RATIO = 0.35f
        /**
         * 内容带宽度上限（占屏宽）。
         *
         * ⚠️ **2026-11-11 实测修正（误划横屏正剧）**：先前假设“正剧横屏铺满”，实际
         * 小米 23127PN0CC 横屏是 2670x1200（2.2:1），而剧集是 16:9 → 红果**保比例居中播放**，
         * 左右各留约 10% **纯黑边**（现场日志 `L 1.24 R 0.00 spread 20.66 fill 1.00`）——
         * 正剧本就是“信箱形态”。所以单靠“中间高两侧低”不能成立，必须再加上：
         * 1. **内容带足够窄**（正剧 16:9 在 2.2:1 屏上占 0.80；竖屏素材约 0.38）；
         * 2. **两侧是“有内容的模糊填充”而不是纯黑边**（黑边亮度 ≈0，模糊填充是可见画面）。
         * 两者同时要求，才能把“带黑边的保比例正剧”与“竖屏素材广告”分开。
         *
         * **0.50 的来源（2026-11-11 用户提出“竖屏广告宽度不会超过总宽 50%”）**：
         * 9:16 素材满高播放时，band 只取决于屏幕比例 —— 2.2:1 屏 25%、3:2 屏 38%、
         * 16:10 屏 35%、最极端的 4:3 屏也只有 42%；而 16:9 正剧在 2.2:1 屏上是 78%~83%。
         * 取 0.50 两边各留 8 个点以上余量。（代价：4:5 / 3:4 这类“偏胖”的竖屏创意会被漏掉，
         * 例如 3:4 在 3:2 屏上恰为 50.1% —— 按“宁漏勿误划”接受这个取舍。）
         */
        private const val PILLAR_BAND_MAX_FRAC = 0.50f
        /** 判定内容带边界时使用的列能量比例（相对最强列） */
        private const val PILLAR_BAND_COL_RATIO = 0.30f
        /** 判定内容带边界时要求连续达标的列数（含起点，故实际为 N+1 列） */
        private const val PILLAR_BAND_RUN = 3
        /** 两侧窗口平均亮度下限（0-255）：黑边 ≈0，模糊填充通常 40+ */
        private const val PILLAR_SIDE_LUMA_MIN = 24f
        /** 与橙色/提示栏一致：需要两张不同截图都命中 */
        private const val PILLAR_HITS_REQUIRED = 2
        /**
         * 横屏模糊态的**自主轮询**间隔 / 次数上限。
         * 起因（2026-10-27）：横屏广告的视频层画在 surface 上、叠加文字不再刷新时，
         * 无障碍**一个事件都没有**；一旦上滑补扫链结束，服务就彻底停摆
         * （实测现场 logcat 静默 10 分钟以上，广告一直挂在那里不划）。
         */
        private const val LANDSCAPE_POLL_MS = 1_500L
        private const val LANDSCAPE_POLL_MAX = 40
        /**
         * “安静期”探测间隔（毫秒）：一轮轮询预算用完后把频率降下来。
         * 原因（2026-10-27）：`landscapePollLeft` 只约束“无事件时的自我轮询”，
         * 而由无障碍事件驱动的那条路径（结果过期就再取一张）本来没有总上限 ——
         * 横屏正剧一直在播（弹幕/画面持续产生 content change）时，截图会以约
         * 1 张 / 2.5s 无限持续（实测 15s 内 5 张）。现在改为：预算用完后降频到
         * 1 张 / 8s，既能显著省电，又不会在“停了很久之后又出广告”时完全漏掉。
         */
        private const val ORANGE_IDLE_PROBE_MS = 8_000L
        /**
         * 低频看门狗：多久无条件重扫一次（毫秒）。
         * 上面那条轮询是在 `detectAndAct` **内部** 排的 —— 如果界面连一个无障碍事件都没有
         * （典型：横屏静止广告，视频画在 surface 上），`detectAndAct` 根本不会被调用，
         * 轮询也就永远启不动（实测重装后 20s 内日志 0 行）。所以需要这个不依赖事件的定时器。
         */
        private const val WATCHDOG_MS = 3_000L
        /** 距上次检测不足该时长时看门狗不重复扫（避免与事件路径重复工作） */
        private const val WATCHDOG_MIN_IDLE_MS = 2_500L
        /** “闸门未通过”日志的节流间隔（内容变化事件每秒十几次，不能每条都打） */
        private const val GATE_LOG_INTERVAL_MS = 3_000L

        /**
         * **横屏策略（2026-10-27 实验结论）**：横屏全屏、控件隐藏时，“有一个实心橙色按钮”
         * 就是最强的正向证据（正剧全屏状态本来就没有任何按钮）—— 它已经从“兜底”升级为主策略。
         *
         * 实测结果（真机 2670x1200）：
         * - 只靠橙色：`2084x108` 宽条 行跨 1.00/列跨 0.98 → `SWIPE_UP (广告(橙色按钮))` 成功；
         *   `216x88` 右下角 CTA 行跨 1.00/列跨 0.96 → 同样成功。
         * - 但经历了一次“判据调试期”：几何/颜色阈值未标定好时，单轨策略会让真广告
         *   挂在屏幕上 **3 分钟以上**（角标判据被忽略）—— 单策略一旦失效就是全失效。
         * - 角标 `desc="广告"` 比截图判定快 2~3s（同一广告：角标 11:49:50.8 可见、橙色 11:49:53.7 才确认）。
         *
         * 所以最终策略：**保留角标快路径 + 橙色作主策略**，只把真正有风险的
         * “渲染视图”类判据在横屏禁用（SDK 渲染视图可能残留、与正剧共存，
         * 而横屏读不到文字使得 `hasPlaybackControls` 保护失效）。
         */
        private const val LANDSCAPE_IGNORE_RENDER_VIEW = true

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
     * 广告暂停态检测（**只读**，不主动点击）：
     * - NONE：正常 / 未进入；
     * - PAUSED：检测到中央播放按钮（广告已暂停），等待底部“上滑继续观看”提示出现后按原逻辑划走。
     *
     * ⚠️ 2026-10-23 已移除“主动点屏幕正中暂停”的动作：实测反馈某些直播类/落地页广告
     * 会把“点中心”当成点击广告创意，过几秒就自动进入直播间 —— 风险远大于收益，
     * 现在回退为“等倒计时结束再滑动”。
     */
    private enum class PauseState { NONE, PAUSED }

    private var pauseState = PauseState.NONE
    /** 确认暂停的时刻（用于限制“已暂停但提示未出现”的等待时间） */
    private var pauseConfirmedAt = 0L
    /** 暂停流程失败后的禁用截止时刻：本广告内不再反复点中心，回退原倒计时逻辑 */
    private var pauseFlowDisabledUntil = 0L

    /** 延迟重扫任务：到点后重新检测一次（广告文案晚渲染 / 倒计时结束时的兜底） */
    private val retryRunnable = Runnable {
        retryPending = false
        if (detectionEnabled()) {
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
    /** “稀疏树广告”状态的起始时刻：用于持久性确认（0 = 当前不是该状态） */
    private var sparseAdSinceAt = 0L
    /** “横屏无视频层广告”状态的起始时刻：用于持久性确认（0 = 当前不是该状态） */
    private var landscapeNoSurfaceSinceAt = 0L

    /** 截图探测用的单线程执行器（像素统计不能放主线程） */
    private val probeExecutor = Executors.newSingleThreadExecutor()
    /** 每次成功测量递增的序号：用于要求“两张不同截图”都命中 */
    @Volatile private var orangeSeq = 0L
    /** 本次测量是否看到一个“实心橙色宽条” */
    @Volatile private var orangeIsButton = false
    /** 排查用描述（占比 / 外接矩形 / 填充率 / 长宽比） */
    @Volatile private var orangeInfo = ""
    /** 本次测量的时刻（0 = 当前无有效结果） */
    @Volatile private var orangeAt = 0L
    /** 截图请求是否在飞行中（避免重复请求） */
    @Volatile private var orangeProbeInFlight = false
    /** 本次截图是否要额外算「底部提示栏」（只有“整页读不到字”的待定态才需要，省一次全图遍历） */
    @Volatile private var hintProbeWanted = false
    /** 上次发起截图请求的时刻（节流） */
    private var lastOrangeProbeAt = 0L
    /** 已被消费过的测量序号（同一张图不重复计命中） */
    private var lastCountedOrangeSeq = 0L
    /** 命中次数（需要两张不同截图都命中） */
    private var orangeHitCount = 0
    /** 是否已为当前测量排了“等结果回来再扫一次”的任务 */
    private var orangeRetryPending = false
    /** 已打印过日志的测量序号（同一张截图不重复刷日志） */
    private var lastLoggedOrangeSeq = 0L
    /** 本次测量是否看到“底部「∧ 上滑继续看短剧」提示栏”（与橙色共用同一次截图） */
    @Volatile private var hintBarHit = false
    /** 提示栏排查用描述（栏高 / 黑底占比 / 白字占比 / 跨度 / 中心） */
    @Volatile private var hintBarInfo = ""
    /** 已被消费过的测量序号（同一张图不重复计命中） */
    private var lastCountedHintSeq = 0L
    /** 提示栏命中次数（需要两张不同截图都命中） */
    private var hintHitCount = 0
    /** 已打印过提示栏日志的测量序号 */
    private var lastLoggedHintSeq = 0L
    /** 本次测量是否判定为「横屏里的竖屏素材」（与橙色/提示栏共用同一次截图） */
    @Volatile private var pillarHit = false
    /** 信箱判据排查用描述（三窗口能量 / 比值 / 贯通度） */
    @Volatile private var pillarInfo = ""
    /** 本次截图是否要额外算信箱判据（只有横屏模糊态才需要，省一次全图色域转换） */
    @Volatile private var pillarProbeWanted = false
    /** 已被消费过的测量序号（同一张图不重复计命中） */
    private var lastCountedPillarSeq = 0L
    /** 信箱判据命中次数（需要两张不同截图都命中） */
    private var pillarHitCount = 0
    /** 已打印过信箱判据日志的测量序号 */
    private var lastLoggedPillarSeq = 0L
    /** 横屏模糊态剩余可轮询次数（0 = 停止轮询，等待新的无障碍事件） */
    private var landscapePollLeft = LANDSCAPE_POLL_MAX
    /** 下一次允许计一次轮询预算的时刻：内容变化事件每秒可来十几次，不加闸门会 4 秒烧完预算 */
    private var nextLandscapePollAt = 0L
    /** 上一次真正执行检测的时刻（看门狗据此避让事件路径） */
    private var lastDetectAt = 0L
    /** 闸门日志节流：同一条消息 GATE_LOG_INTERVAL_MS 内只打一次 */
    private var lastGateLogAt = 0L
    private var lastGateMsg = ""
    /** 本次检测的“特征快照”文本（仅调试浮层开启时拼装，否则为空串 → 零开销） */
    private var traceBase = ""

    /**
     * 是否需要检测。
     *
     * - **调试浮层开启时：始终检测**（与“自动跳过”/悬浮球状态无关）—— 排查“为什么跳不过”时
     *   必须在关闭自动跳过的状态下也能看到判定过程；
     * - 否则：仅在“自动跳过”开启时检测（关闭时不做任何检测）。
     *
     * 注意：检测 ≠ 执行，执行由 [actionAllowed] 单独控制。
     */
    private fun detectionEnabled(): Boolean =
        SettingsManager.adSkipEnabled || SettingsManager.debugOverlayEnabled

    /**
     * 是否允许真正执行动作（滑走/点击）。
     * 调试观察模式下（自动跳过关闭、只开着调试浮层）**只看不动**，避免排查时把内容划走。
     */
    private fun actionAllowed(): Boolean = SettingsManager.adSkipEnabled

    /** 结束当前“广告上滑链”（窗口切换 / 锁期等待 / 换广告时调用） */
    private fun resetAdSwipeChain() {
        adSwipeStreak = 0
        adSwipeChainStartedAt = 0L
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!detectionEnabled()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "自动跳过已关闭且未开调试浮层，界面变化不检测")
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
            pauseConfirmedAt = 0L
            pauseFlowDisabledUntil = 0L
            resetAdSwipeChain()
            sparseAdSinceAt = 0L
            landscapeNoSurfaceSinceAt = 0L
            // 换了窗口（换广告/回正剧）：橙色探测结果与计数作废
            orangeAt = 0L
            orangeHitCount = 0
            orangeRetryPending = false
            landscapePollLeft = LANDSCAPE_POLL_MAX
            nextLandscapePollAt = 0L
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

    /**
     * 启动低频看门狗：不依赖无障碍事件的周期性检测。
     * 用于“界面完全静止、一个事件都不来”的场景（横屏静止广告最为典型）。
     * 成本很低：非支持范围内的 App 在包名闸门处就直接返回，不会遍历节点树。
     */
    fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (detectionEnabled() && !isDragging) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastDetectAt >= WATCHDOG_MIN_IDLE_MS) {
                        detectAndAct(isWindowStateChange = false)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "看门狗检测异常: $t")
            } finally {
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    /**
     * 闸门日志（带节流）。
     * 背景（2026-10-31 小米平板 5 反馈“红果广告跳不过”）：这些前置闸门以前**只调 onResult**，
     * 而事件路径/看门狗传的是空 lambda → 于是“跳不过，且 logcat 一片空白”，
     * 完全看不出是哪一道门没通过。必须打出日志才能定位。
     */
    private fun logGate(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (msg == lastGateMsg && now - lastGateLogAt < GATE_LOG_INTERVAL_MS) return
        lastGateMsg = msg
        lastGateLogAt = now
        Log.d(TAG, "闸门未通过：$msg")
        if (SettingsManager.debugOverlayEnabled) DebugTrace.update("闸门未通过：$msg")
    }

    /** 调试浮层：在“特征快照”后面追加本次的判定结论 */
    private fun traceOutcome(outcome: String) {
        if (!SettingsManager.debugOverlayEnabled) return
        DebugTrace.update(if (traceBase.isEmpty()) outcome else "$traceBase\n$outcome")
    }

    /** 当前所有无障碍窗口的简要结构（排查“前台包名为空”用） */
    private fun windowSummary(): String {
        return try {
            val ws = service.windows
            val sb = StringBuilder("windows=${ws.size}")
            for (w in ws) {
                val root = w.root
                val pkg = root?.packageName?.toString().orEmpty()
                val cls = root?.className?.toString()?.substringAfterLast('.') ?: "-"
                sb.append(" {t=").append(w.type)
                    .append(",a=").append(w.isActive)
                    .append(",pkg=").append(if (pkg.isEmpty()) "-" else pkg)
                    .append(",cls=").append(cls)
                    .append("}")
            }
            sb.toString()
        } catch (t: Throwable) {
            "windows 读取失败: $t"
        }
    }

    private fun detectAndAct(onResult: (String) -> Unit = {}, isWindowStateChange: Boolean = false) {
        // 拖动悬浮球期间暂停检测，保证拖动流畅；结束后由悬浮球补扫一次
        if (isDragging) return
        lastDetectAt = SystemClock.uptimeMillis()
        // 前台应用包名：先排除我们自己（设置页里含“自动跳过/广告快跳”等文字，会被误判成广告）。
        // 例如在设置页打开开关会触发界面变化事件，若不排除就会点到自己界面上的“跳过”/“关闭”。
        val fgPkg = foregroundPackage()
        if (fgPkg == null) {
            logGate("前台包名为空（可能是锁屏/全屏视频/平板多窗口）→ ${windowSummary()}")
            onResult("无障碍服务尚未就绪")
            return
        }
        if (fgPkg in NON_AD_PACKAGES) {
            logGate("前台是自身或系统界面：$fgPkg")
            onResult("当前界面是广告快跳自身或系统界面，无需检测")
            return
        }
        // 只在前台 App 属于“支持的包名”时才检测；通用模式下才对所有 App 检测（误触风险更高）。
        // 避免在普通 App（聊天/设置/桌面等）里把页面上的“广告/关闭/跳过”等字样误判成广告而自动点击。
        if (!SettingsManager.genericModeEnabled && fgPkg !in SettingsManager.supportedPackagesList()) {
            logGate("前台 App 不在支持范围：$fgPkg（允许列表=${SettingsManager.supportedPackagesList()}）")
            onResult("当前 App（$fgPkg）不在自动跳过范围内")
            return
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        val stats = NodeStats()
        // 类名直方图只在调试浮层开启时统计（默认零开销）
        collectFromAllWindows(nodes, fgPkg, stats, SettingsManager.debugOverlayEnabled)
        if (nodes.isEmpty()) {
            onResult("无障碍服务尚未就绪")
            return
        }

        // 页面文本只拼接一次：关键字匹配与倒计时解析共用，避免重复遍历节点树导致主线程卡顿
        val pageText = AdRules.pageTextOf(nodes)
        val countdownFound = AdRules.hasCountdown(pageText)
        // 传入“活动窗口屏幕范围”，供“广告角标”等位置敏感特征做过滤。
        // 用窗口根节点边界（与节点坐标同一坐标系），比 displayMetrics 更可靠（横竖屏通用）。
        val frame = screenFrame()
        val frameW = frame?.width() ?: 0
        val frameH = frame?.height() ?: 0
        // 排查用：本次到底看到了什么（节点数 / 文字长度 / 各渲染视图 / 是否像“稀疏树广告”）
        val hasRenderView = AdRules.hasAdRenderView(nodes)
        val hasTexView = AdRules.hasAdRenderSurface(nodes)
        val hasAdItem = AdRules.hasAdItemContainer(nodes)
        val looksSparse = AdRules.looksLikeSparseAdItem(
            pageText, frameW, frameH,
            stats.hasVideoSurface, stats.hasScrollableContent,
        )
        val looksLandscapeNoSurface = AdRules.looksLikeLandscapeAdWithoutSurface(
            pageText, frameW, frameH, stats.hasVideoSurface,
        )
        Log.d(
            TAG,
            "scan: fg=$fgPkg nodes=${nodes.size} rawNodes=${stats.visibleNodeCount} " +
                "textLen=${pageText.length} frame=${frameW}x$frameH " +
                "surface=${stats.hasVideoSurface} scroll=${stats.hasScrollableContent} " +
                "webView=${stats.hasWebView} " +
                "renderView=$hasRenderView texView=$hasTexView adItem=$hasAdItem " +
                "sparse=$looksSparse landNoSurface=$looksLandscapeNoSurface " +
                "countdown=$countdownFound streak=$adSwipeStreak",
        )
        // 调试浮层（设置页开关）：记录本次“看到了什么”。关闭时 traceBase 为空串，零开销。
        traceBase = if (SettingsManager.debugOverlayEnabled) {
            // 顶部标注当前模式：自动跳过关闭时是“仅调试·不执行”（只看不动）
            val mode = if (SettingsManager.adSkipEnabled) "" else "【仅调试·不执行】\n"
            val cls = stats.classHistogram()
            mode +
                "fg=$fgPkg  ${frameW}x$frameH\n" +
                "nodes=${nodes.size}/${stats.visibleNodeCount} textLen=${pageText.length}\n" +
                "rv=$hasRenderView tex=$hasTexView adItem=$hasAdItem\n" +
                "sparse=$looksSparse cd=$countdownFound landNoSurf=$looksLandscapeNoSurface\n" +
                "surface=${stats.hasVideoSurface} scroll=${stats.hasScrollableContent} " +
                "webView=${stats.hasWebView}" +
                (if (cls.isEmpty()) "" else "\ncls=$cls")
        } else {
            ""
        }
        var action = AdRules.match(
            nodes, pageText, frameW, frameH, fgPkg,
            stats.hasVideoSurface, stats.hasScrollableContent,
        )
        // **横屏策略（2026-10-31 修正）**：1.0.16 曾在这里无条件丢弃“渲染视图”判据，
        // 结果在小米平板 5 上翻车：实测那条直播样式的广告 `renderView=true adItem=true`，
        // 而 `AdRules.match` 只返回**第一个**命中的分支（渲染视图），丢弃后不会继续去试
        // “广告容器”，可这条广告橙色占比只有 0.11%（没有橙色 CTA）→ 三个信号全废 → 跳不过。
        // 现在改成：只有当**没有别的独立正向证据**（广告容器/角标/上滑关键字）时才丢弃渲染视图，
        // 保留“渲染视图残留、横屏误划正剧”的防护，同时不会把真广告漏掉。
        if (action != null && LANDSCAPE_IGNORE_RENDER_VIEW && frameW > frameH &&
            fgPkg == AdRules.HONGUO_PACKAGE && action.reason == AdRules.REASON_LIVE_AD
        ) {
            val hasOtherPositive = AdRules.hasAdItemContainer(nodes) ||
                AdRules.hasAdBadge(nodes, frameW, frameH) ||
                AdRules.hasSwipeHint(pageText)
            if (hasOtherPositive) {
                Log.d(TAG, "横屏策略：渲染视图另有独立证据，保留动作 ${action.reason}")
            } else {
                Log.d(TAG, "横屏策略：忽略渲染视图判据 ${action.reason}，改由橙色按钮判定")
                action = null
            }
        }
        // 1) 横屏「读不到字 + 连视频层都没有」= 创意自绘（试玩/落地页广告）。
        //    正剧横屏全屏必然有视频层（SurfaceView/TextureView），缓冲/加载/暂停时它都还在；
        //    没有视频层说明当前画面不是播放器渲染的。详见 AdRules.looksLikeLandscapeAdWithoutSurface。
        //    放在橙色探测之前：这类广告通常**没有橙色 CTA**（vivo 样本的 CTA 是灰白/亮绿胶囊），
        //    橙色兜底对它无效。
        if (action == null && fgPkg == AdRules.HONGUO_PACKAGE && looksLandscapeNoSurface) {
            action = AdAction(
                AdActionType.SWIPE_UP,
                null,
                reason = AdRules.REASON_LANDSCAPE_NO_SURFACE,
                immediate = true,
            )
        }
        // 用“画面里是否有一个实心橙色按钮 / 底部上滑提示栏”来区分广告/正剧（截图 + 统计，无 OCR）。
        // 只在待定态 & 非动作冷却期才截图；要求**两张不同截图**都命中才动手。
        val nowOrange = SystemClock.uptimeMillis()
        // 横屏“分不出来”的状态：正剧控件被隐藏 + 没有任何可用的正向广告特征
        val landscapeAmbiguous = action == null &&
            isLandscapeAmbiguous(frameW, frameH, pageText, fgPkg)
        // **竖屏也有一模一样的“读不到字”全屏广告**（2026-11-07 用户样本：竖屏游戏试玩）。
        // 所以待定态不再只限横屏：只要整页读不到字、没有正剧控件、也没命中任何规则，
        // 就值得截一张图看看底部有没有「∧ 上滑继续看短剧」提示栏。
        //
        // ⚠️ **必须要求“没有视频层”**（2026-11-08 修正）：横屏正剧实测
        // `surface=true textLen=0`，而底部暗场景画面 + 烧在视频帧里的居中白色字幕，
        // 像素统计与提示栏完全撞车（栏高 19.8% / 黑 0.91 / 白 0.0096 / 中心 0.50），
        // 导致正剧被反复划走。正剧播放**必然**挂 SurfaceView/TextureView，
        // 提示栏只出现在“创意自绘、没有任何视频层”的页面上 —— 用这个结构差异把它彻底隔开。
        val contentlessAmbiguous = action == null &&
            fgPkg == AdRules.HONGUO_PACKAGE &&
            AdRules.isContentlessPage(pageText) &&
            !stats.hasVideoSurface &&
            !AdRules.hasPlaybackControls(pageText)
        val probeAmbiguous = landscapeAmbiguous || contentlessAmbiguous
        if (probeAmbiguous && nowOrange >= nextAllowedAt) {
            // 结果有效期：预算内用 2.5s（及时）；预算用完（说明看着正剧很久了）降频到 8s
            val freshMs = if (landscapePollLeft > 0) ORANGE_RATIO_FRESH_MS else ORANGE_IDLE_PROBE_MS
            val fresh = orangeAt != 0L && nowOrange - orangeAt <= freshMs
            if (!fresh) {
                // 还没有可用截图：请求一次，并在结果回来后补扫一次
                requestOrangeProbe(contentlessAmbiguous, landscapeAmbiguous)
                if (!orangeRetryPending) {
                    orangeRetryPending = true
                    scheduleRetry(ORANGE_RETRY_MS, "截图探测")
                }
                recycleAll(nodes)
                return
            }
            // 结果已被消费：允许为下一次测量再排补扫
            orangeRetryPending = false
            // ---- 判据 0：信箱（横屏容器里塞了一个竖屏素材）----
            // 这是**结构**判据，比颜色可靠，所以排在橙色之前；命中后不再允许颜色判据改判。
            // 现场样本（2026-11-09 小米 M656BA / 3200x2136 / 红果 7.4.3.32）：
            // 横屏里放竖屏直播电商创意，左右是同一帧的模糊放大填充，
            // 调试浮层 `textLen=0 tex=true surface=true scroll=true` → 四条结构判据全灭。
            if (landscapeAmbiguous) {
                if (pillarHit && orangeSeq != lastCountedPillarSeq) {
                    lastCountedPillarSeq = orangeSeq
                    pillarHitCount++
                } else if (!pillarHit) {
                    pillarHitCount = 0
                }
                if (orangeSeq != lastLoggedPillarSeq) {
                    lastLoggedPillarSeq = orangeSeq
                    Log.d(TAG, "信箱判据：$pillarInfo 命中 $pillarHitCount/$PILLAR_HITS_REQUIRED")
                    traceOutcome("柱: $pillarInfo\n命中 $pillarHitCount/$PILLAR_HITS_REQUIRED")
                }
                if (pillarHitCount >= PILLAR_HITS_REQUIRED) {
                    pillarHitCount = 0
                    orangeAt = 0L
                    action = AdAction(
                        AdActionType.SWIPE_UP,
                        null,
                        reason = AdRules.REASON_PILLARBOX_AD,
                        immediate = true,
                    )
                }
            } else {
                pillarHitCount = 0
            }
            // ---- 判据 A：橙色 CTA（只用于横屏策略，竖屏不用它，避免误判）----
            if (action == null && landscapeAmbiguous) {
                if (orangeIsButton && orangeSeq != lastCountedOrangeSeq) {
                    lastCountedOrangeSeq = orangeSeq
                    orangeHitCount++
                } else if (!orangeIsButton) {
                    orangeHitCount = 0
                }
                // 同一张截图只记一行日志（content change 每秒可来十几次）
                if (orangeSeq != lastLoggedOrangeSeq) {
                    lastLoggedOrangeSeq = orangeSeq
                    Log.d(TAG, "横屏主策略：$orangeInfo 命中 $orangeHitCount/$ORANGE_HITS_REQUIRED")
                    traceOutcome("橙: $orangeInfo\n命中 $orangeHitCount/$ORANGE_HITS_REQUIRED")
                }
                if (orangeHitCount >= ORANGE_HITS_REQUIRED) {
                    orangeHitCount = 0
                    orangeAt = 0L
                    action = AdAction(
                        AdActionType.SWIPE_UP,
                        null,
                        reason = AdRules.REASON_ORANGE_AD,
                        immediate = true,
                    )
                }
            } else {
                orangeHitCount = 0
            }
            // ---- 判据 B：底部「∧ 上滑继续看短剧」提示栏（横竖屏通用）----
            if (action == null && contentlessAmbiguous) {
                if (hintBarHit && orangeSeq != lastCountedHintSeq) {
                    lastCountedHintSeq = orangeSeq
                    hintHitCount++
                } else if (!hintBarHit) {
                    hintHitCount = 0
                }
                if (orangeSeq != lastLoggedHintSeq) {
                    lastLoggedHintSeq = orangeSeq
                    Log.d(TAG, "底部提示栏：$hintBarInfo 命中 $hintHitCount/$HINT_BAR_HITS_REQUIRED")
                    traceOutcome("栏: $hintBarInfo\n命中 $hintHitCount/$HINT_BAR_HITS_REQUIRED")
                }
                if (hintHitCount >= HINT_BAR_HITS_REQUIRED) {
                    hintHitCount = 0
                    orangeAt = 0L
                    action = AdAction(
                        AdActionType.SWIPE_UP,
                        null,
                        reason = AdRules.REASON_HINT_BAR_AD,
                        immediate = true,
                    )
                }
            } else if (!contentlessAmbiguous) {
                hintHitCount = 0
            }
            // 命中一次：再取一张新图确认（橙色 / 提示栏共用同一套“两张图”确认）
            val needConfirm = action == null && (
                (landscapeAmbiguous && pillarHit) ||
                    (landscapeAmbiguous && orangeIsButton) ||
                    (contentlessAmbiguous && hintBarHit)
                )
            if (needConfirm) {
                requestOrangeProbe(contentlessAmbiguous, landscapeAmbiguous)
                orangeRetryPending = true
                scheduleRetry(ORANGE_RETRY_MS, "截图确认")
                recycleAll(nodes)
                return
            }
        } else {
            orangeHitCount = 0
            hintHitCount = 0
            pillarHitCount = 0
        }
        // 静止广告的“续命轮询”（2026-10-27）：横屏广告的视频层画在 surface 上、叠加文字不再刷新时，
        // 无障碍**一个事件都没有**；一旦上滑补扫链结束，服务就彻底停摆
        // （实测现场 logcat 静默 10 分钟以上，广告一直挂着不划）。
        // 所以只要仍处于“分不出来”的模糊态且没有拿到动作，就自己定期再扫一次。
        // 预算内 1.5s 一次；预算用完后降到 8s 一次（不完全停，保证“看了很久正剧之后
        // 突然出广告”也能发现）。
        if (probeAmbiguous && action == null && nowOrange >= nextLandscapePollAt) {
            if (landscapePollLeft > 0) {
                landscapePollLeft--
                if (landscapePollLeft == 0) {
                    Log.d(TAG, "截图探测轮询 $LANDSCAPE_POLL_MAX 次用尽，降频为 $ORANGE_IDLE_PROBE_MS ms/次")
                }
            }
            val wait = if (nowOrange < nextAllowedAt) {
                (nextAllowedAt - nowOrange).coerceAtLeast(200L)
            } else if (landscapePollLeft > 0) {
                LANDSCAPE_POLL_MS
            } else {
                ORANGE_IDLE_PROBE_MS
            }
            nextLandscapePollAt = nowOrange + wait
            requestOrangeProbe(contentlessAmbiguous, landscapeAmbiguous)
            scheduleRetry(wait, "横屏主策略轮询(剩 $landscapePollLeft)")
        }
        // “稀疏树”是缺失型判据：广告→正剧的过渡帧也会满足（实测 nodes=26~28 textLen=0，约 400ms），
        // 所以要求该状态连续观测满 AD_ITEM_CONFIRM_MS 才动手；任何其它状态立即清零。
        if (action?.reason != AdRules.REASON_SPARSE_AD) {
            sparseAdSinceAt = 0L
        } else {
            val nowSparse = SystemClock.uptimeMillis()
            if (sparseAdSinceAt == 0L) sparseAdSinceAt = nowSparse
            val held = nowSparse - sparseAdSinceAt
            if (held < AD_ITEM_CONFIRM_MS) {
                Log.d(
                    TAG,
                    "疑似广告(稀疏树 rawNodes=${stats.visibleNodeCount} " +
                        "textLen=${pageText.length} surface=${stats.hasVideoSurface} " +
                        "scroll=${stats.hasScrollableContent}) 已持续 ${held}ms，" +
                        "${AD_ITEM_CONFIRM_MS - held}ms 后确认",
                )
                scheduleRetry((AD_ITEM_CONFIRM_MS - held).coerceAtLeast(50L), "稀疏树确认")
                recycleAll(nodes)
                return
            }
        }
        if (action != null) {
            // **调试观察模式**：自动跳过关闭、只开着调试浮层 → 只显示判定结果，绝不执行动作。
            // 这样排查时可以放心盯着调试浮层看，不会把正在看的内容划走。
            // （检测本身仍持续进行，见 detectionEnabled()）
            if (!actionAllowed()) {
                Log.i(TAG, "仅调试：命中 ${action.type} (${action.reason})，未执行（自动跳过已关闭）")
                traceOutcome("→ ${action.type}（${action.reason}）\n【仅调试·不执行】")
                recycleAll(nodes)
                return
            }
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
                    onResult("检测到需倒计时的广告（视频已暂停），等待上滑提示后直接划走")
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
            // 已经动作过 = 新的一段广告，横屏主策略轮询预算重置
            landscapePollLeft = LANDSCAPE_POLL_MAX
            nextLandscapePollAt = 0L
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
            // 动作后清零：避免紧接着的“广告→正剧”过渡帧（仍满足稀疏条件）被立即再次划走
            if (action.reason == AdRules.REASON_SPARSE_AD) sparseAdSinceAt = 0L
            if (action.reason == AdRules.REASON_LANDSCAPE_NO_SURFACE) landscapeNoSurfaceSinceAt = 0L
            Log.i(TAG, "执行${if (ok) "成功" else "失败"}: ${action.type} (${action.reason})")
            traceOutcome("→ ${action.type}（${action.reason}）${if (ok) "已执行" else "执行失败"}")
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
                    onResult("检测到广告上下文（视频已暂停），等待上滑提示后直接跳过")
                    recycleAll(nodes)
                    return
                }
            }
            onResult("当前界面未检测到广告")
            traceOutcome("→ 未命中任何广告规则")
            maybeScheduleRetry(fgPkg, isWindowStateChange, pageText)
        }
        recycleAll(nodes)
    }

    /**
     * 当前前台应用的包名：活动窗口 → 聚焦窗口 → rootInActiveWindow。
     * 用于把检测范围限定在前台应用，丢弃后台应用/系统设置窗口的残留文字。
     *
     * `isFocused` 这一级是 2026-10-31 加的回退：小米平板 5（MIUI Pad / Android 13）在
     * 全屏广告这种状态下实测 `dumpsys window` 的 `mCurrentFocus=null`，很可能**没有任何
     * 窗口被标成 active**；而原实现只认 `isActive`，于是直接返回 null → 第一道闸门静默
     * 返回 → 规则与橙色探测全部不执行（表现为“广告跳不过、日志一片空白”）。
     * 聚焦窗口（isFocused）与活动窗口语义等价，用它兜一层不会放宽安全边界。
     */
    private fun foregroundPackage(): String? {
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            emptyList()
        }
        for (w in windows) {
            if (w.isActive) {
                w.root?.packageName?.toString()?.let { if (it.isNotEmpty()) return it }
            }
        }
        for (w in windows) {
            if (w.isFocused) {
                w.root?.packageName?.toString()?.let { if (it.isNotEmpty()) return it }
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
    /**
     * 当前“屏幕范围”：活动应用窗口根节点的边界（与节点坐标同一坐标系），
     * 用于给“广告角标”等位置敏感特征做过滤；横竖屏都正确。
     * 找不到活动应用窗口时退回 displayMetrics 的宽高。
     *
     * 注（2026-10-31）：曾怀疑小米平板上“窗口方向 ≠ 显示器方向”，加过一层方向一致性校验，
     * 但用户确认当时**确实竖屏测试过**（日志里的 `sparse=true` 是竖屏下的正常判定），
     * 属于误判，已回退 —— 保持“窗口根节点边界优先”的原始行为，
     * 只把 `frame=WxH` 打进 scan 日志，便于以后一眼看出当时是横屏还是竖屏。
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
     * “需要倒计时等待”的广告：过去会“点一下屏幕正中让视频暂停”以提前解锁滑动。
     *
     * ⚠️ 2026-10-23 起**不再主动点击**（实测反馈：某些直播类/落地页广告会把“点中心”
     * 当成点击广告创意，几秒后就自动进入直播间/落地页）。现在只做两件事：
     * 1) 如果广告**已经**处于暂停态（中央有播放按钮），就等底部“上滑”提示出现后划走；
     * 2) 否则直接返回 false，让调用方回退到“等倒计时结束再滑”的老逻辑（仍能跳过，只是多等几秒）。
     *
     * @return true=已接管调度（已处于暂停态，等待上滑提示）；false=不适用，调用方回退倒计时等待。
     */
    private fun enterPauseBeforeSwipe(fgPkg: String): Boolean {
        val now = SystemClock.uptimeMillis()
        when (pauseState) {
            PauseState.NONE -> {
                // 本广告的暂停流程已判定不适用：直接回退原倒计时逻辑
                if (now < pauseFlowDisabledUntil) return false
                if (hasCenterPlayButton(fgPkg)) {
                    // 广告已经处于暂停态（可能是用户自己点的）：直接等底部“上滑”提示出现即可
                    Log.i(TAG, "广告已处于暂停态（中间出现播放按钮），等待下方上滑提示")
                    pauseState = PauseState.PAUSED
                    pauseConfirmedAt = now
                    scheduleRetry(PAUSE_RESCAN_MS, "暂停确认/等待")
                    return true
                }
                // 不再主动“点屏幕正中”：
                // 该动作在直播类/落地页广告上会被识别为点击广告创意，导致“过几秒自动进入直播间/落地页”。
                // 放弃暂停流程后，调用方会回退到“等倒计时结束再滑动” —— 仍能跳过，只多等几秒，但零误点风险。
                Log.i(TAG, "跳过“点中心暂停”（避免误点广告创意），改为等倒计时结束再划")
                pauseFlowDisabledUntil = now + PAUSE_FLOW_DISABLED_MS
                return false
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

    /**
     * 检测屏幕中央是否出现“播放按钮”（视频已暂停的标志）。
     * **只读判定**：仅用于“广告已经处于暂停态”时直接划走，不会主动点击屏幕。
     * 暂停态下视频层中央会出现一个居中的播放图标（多为无文字的 ImageView）。
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
                // 拒绝“整屏级”点击目标：那几乎都是广告创意/整页容器，点它等于点进落地页/直播间
                // （2026-10-23 用户反馈某些直播类广告会“过几秒点进去”）。
                if (isOversizedClickTarget(node)) return false
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

    /**
     * 拒绝“整屏级”点击目标：面积 ≥ 屏幕 60% 的可点击节点几乎都是**广告创意 / 整页容器**，
     * 点它等于“点进广告落地页/直播间”，而不是点按钮。
     * （2026-10-23：用户反馈某些直播类广告会“过几秒点进去”。）
     */
    private fun isOversizedClickTarget(node: AccessibilityNodeInfo): Boolean {
        val dm = service.resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        if (screenArea <= 0) return false
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (_: Throwable) {
            return false
        }
        if (r.isEmpty) return false
        val area = r.width().toLong() * r.height()
        if (area * 100 < screenArea * 60) return false
        Log.w(TAG, "拒绝点击：目标覆盖 ${area * 100 / screenArea}% 屏幕（疑似广告创意/容器），避免点进落地页")
        return true
    }

    /**
     * 横屏“模糊态”：拿不到正剧控件、也没有任何正向广告特征 —— 只有这种状态才值得截图看颜色。
     * 横屏全屏时红果会把正剧控件全部隐藏（实测正剧：43 节点 / 0 文字 / 0 可点击），
     * 节点树完全分不出广告和正剧。注意：横屏时橙色按钮是**主策略**而不是兜底（见 LANDSCAPE_ORANGE_ONLY）。
     */
    private fun isLandscapeAmbiguous(
        frameW: Int,
        frameH: Int,
        pageText: String,
        fgPkg: String,
    ): Boolean {
        if (frameW <= 0 || frameH <= 0 || frameW <= frameH) return false
        if (fgPkg != AdRules.HONGUO_PACKAGE) return false
        return !AdRules.hasPlaybackControls(pageText)
    }

    /**
     * 请求一次截图并判断“画面里是否有实心橙色宽按钮”（不做 OCR）。
     * - API < 30 或未声明 `canTakeScreenshot` 时直接返回（不会有结果，规则不生效）；
     * - 系统对截图有频率限制，这里再做 1.2s 节流 + 飞行中防重入；
     * - 像素统计放到后台线程（getPixels 按行采样），不占主线程。
     */
    private fun requestOrangeProbe(hintWanted: Boolean = false, pillarWanted: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = SystemClock.uptimeMillis()
        if (orangeProbeInFlight || now - lastOrangeProbeAt < ORANGE_PROBE_INTERVAL_MS) return
        orangeProbeInFlight = true
        hintProbeWanted = hintWanted
        pillarProbeWanted = pillarWanted
        lastOrangeProbeAt = now
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                probeExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var isButton = false
                        var info = "截图解析失败"
                        var hint = false
                        var hintInfo = "截图解析失败"
                        var pillar = false
                        var pillarDesc = "截图解析失败"
                        try {
                            val hw = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            if (bmp != null) {
                                // 排查色彩管线：缓冲区格式 / 色彩空间 / 位图配置
                                Log.d(
                                    TAG,
                                    "截图缓冲: hw=${hw.width}x${hw.height} fmt=${hw.format} " +
                                        "layers=${hw.layers} bmpConf=${bmp.config} " +
                                        "cs=${result.colorSpace?.name} bmpCs=${bmp.colorSpace?.name}",
                                )
                                // 同一次截图同时算两个判据：橙色 CTA（横屏策略）与底部提示栏（横竖屏通用）
                                val r = computeOrangeBar(bmp)
                                isButton = r.first
                                info = r.second
                                if (hintProbeWanted) {
                                    val r2 = computeBottomHintBar(bmp)
                                    hint = r2.first
                                    hintInfo = r2.second
                                }
                                if (pillarProbeWanted) {
                                    val r3 = computePillarbox(bmp)
                                    pillar = r3.first
                                    pillarDesc = r3.second
                                }
                            }
                            try {
                                hw.close()
                            } catch (_: Throwable) {
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "橙色探测：解析截图失败 $t")
                        } finally {
                            onOrangeResult(isButton, info, hint, hintInfo, pillar, pillarDesc)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "橙色探测：截图失败 code=$errorCode")
                        onOrangeResult(
                            false,
                            "截图失败 code=$errorCode",
                            false,
                            "截图失败",
                            false,
                            "截图失败 code=$errorCode",
                        )
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "橙色探测：调用截图异常 $t")
            onOrangeResult(false, "调用截图异常", false, "调用截图异常", false, "调用截图异常")
        }
    }

    private fun onOrangeResult(
        isButton: Boolean,
        info: String,
        hint: Boolean = false,
        hintInfo: String = "",
        pillar: Boolean = false,
        pillarDesc: String = "",
    ) {
        orangeIsButton = isButton
        orangeInfo = info
        hintBarHit = hint
        this.hintBarInfo = hintInfo
        pillarHit = pillar
        pillarInfo = pillarDesc
        orangeSeq++
        orangeAt = SystemClock.uptimeMillis()
        orangeProbeInFlight = false
    }

    /**
     * 把（可能是 HARDWARE / Display-P3 的）截图统一转成**软件 ARGB_8888 + sRGB** 位图。
     * 调用方负责 `recycle()`；失败返回 null。
     *
     * **关键**：无障碍截图可能带 Display P3（广色域）原始像素（手机实测），也可能是 sRGB
     * （平板实测）。P3 不转色域就直接比色，同一根橙色按钮会显得“颜色很散”
     * （±12 纯度 0.68 vs screencap 0.998）。所以统一转成 sRGB 再分析。
     *
     * ⚠️ 顺序很重要：必须**先 copy 成软件位图**，再画到 sRGB 画布。
     * 软件 Canvas 不能直接画 HARDWARE 位图 —— 小米平板 5 / Android 13 实测抛
     * `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`，
     * 而之前的实现正是先 Canvas 后 copy，导致平板上一路退回原图（色域没统一）。
     */
    private fun toSoftwareSrgb(src: Bitmap): Bitmap? {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val soft = try {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.w(TAG, "copy bitmap 失败：$t")
            return null
        } ?: return null
        if (soft.colorSpace == srgb) return soft
        return try {
            val out = Bitmap.createBitmap(
                soft.width,
                soft.height,
                Bitmap.Config.ARGB_8888,
                false,
                srgb,
            )
            Canvas(out).drawBitmap(soft, 0f, 0f, null)
            soft.recycle()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "转 sRGB 失败，退回原图（${soft.colorSpace}）：$t")
            soft
        }
    }

    /**
     * 判断画面底部是否存在「∧ 上滑继续看短剧」那种**纯黑底 + 居中白字**的提示栏。
     *
     * 为什么需要视觉判据：游戏/试玩广告的创意由引擎自绘，整页 `textLen=0`，
     * 而这条提示栏是 App 自己的 UI（即使 uiautomator 能看到文字，无障碍服务也读不到）。
     * 详见 AdRules.REASON_HINT_BAR_AD 与 HINT_BAR_* 常量的阈值来源。
     *
     * 算法（自底向上，单遍）：
     * 1. 逐行统计暗像素（亮度<70）与亮像素（亮度>200）占比；
     * 2. 只要该行暗占比 ≥ [HINT_BAR_ROW_DARK_MIN] 就并入提示栏，否则说明栏顶到了；
     * 3. 校验栏高/黑底/白字占比/白字横向跨度与中心。
     *
     * @return first = 是否命中，second = 排查用描述
     */
    private fun computeBottomHintBar(src: Bitmap): Pair<Boolean, String> {
        val sw = toSoftwareSrgb(src) ?: return false to "copy bitmap 失败"
        try {
            val w = sw.width
            val h = sw.height
            if (w <= 0 || h <= 0) return false to "截图尺寸异常"
            val step = HINT_BAR_SAMPLE_STEP
            val row = IntArray(w)
            var cells = 0
            var darkCells = 0
            var brightCells = 0
            var minBx = Int.MAX_VALUE
            var maxBx = -1
            var bandTop = h
            // 栏高上限对应的行数：超过就停（也顺带避免整屏黑时白扫一遍）
            val maxBandPx = (h * HINT_BAR_MAX_HEIGHT_FRAC).toInt()
            var y = h - 1
            while (y >= 0 && h - y <= maxBandPx) {
                sw.getPixels(row, 0, w, 0, y, w, 1)
                var rowDark = 0
                var rowN = 0
                var rowBright = 0
                var rowMinBx = Int.MAX_VALUE
                var rowMaxBx = -1
                for (x in 0 until w step step) {
                    val c = row[x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    rowN++
                    if (lum < 70f) {
                        rowDark++
                    } else if (lum > 200f) {
                        rowBright++
                        if (x < rowMinBx) rowMinBx = x
                        if (x > rowMaxBx) rowMaxBx = x
                    }
                }
                // ⚠️ 必须先判定“这一行是否属于提示栏”，再累加统计：
                // 否则把栏顶上方那行彩色内容的亮像素也算进来，会让“白字跨度”爆到 0.5+，
                // 把真正的提示栏判成 miss（2026-11-07 样本复刻时实测到的 bug）。
                if (rowN == 0 || rowDark.toFloat() / rowN < HINT_BAR_ROW_DARK_MIN) break
                darkCells += rowDark
                brightCells += rowBright
                if (rowMaxBx > rowMinBx) {
                    if (rowMinBx < minBx) minBx = rowMinBx
                    if (rowMaxBx > maxBx) maxBx = rowMaxBx
                }
                cells += rowN
                bandTop = y
                y -= step
            }
            if (cells == 0) return false to "底部无黑色栏"
            val bandH = h - bandTop
            val hFrac = bandH.toFloat() / h
            val darkFrac = darkCells.toFloat() / cells
            val brightFrac = brightCells.toFloat() / cells
            val span = if (maxBx > minBx) (maxBx - minBx).toFloat() / w else 0f
            val center = if (maxBx > minBx) ((maxBx + minBx) / 2f) / w else 0f
            // **底色纯度**（2026-11-08 用户提出）：提示栏是 App 画的 UI 条，底色是**同一个纯色**；
            // 而暗场景视频内容是渐变 + 噪点，颜色会散落在很多个桶里。
            // 只统计暗像素（排除白字/抗锯齿），按 4bit/通道（4096 桶）量化后取主色桶占比。
            // 实测：横屏广告 0.993（5 个桶）、竖屏广告(经聊天软件二次压缩) 0.716（47 个桶）、
            //       横屏正剧底部暗画面 0.484~0.511（39~45 个桶）→ 阈值取 0.65，两侧都有余量。
            val buckets = IntArray(HINT_BAR_HISTOGRAM_BUCKETS)
            var darkSamples = 0
            var topBucketCount = 0
            var distinctBuckets = 0
            var yy = bandTop
            while (yy < h) {
                sw.getPixels(row, 0, w, 0, yy, w, 1)
                for (x in 0 until w step step) {
                    val c = row[x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (0.299f * r + 0.587f * g + 0.114f * b >= 70f) continue
                    val idx = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    if (buckets[idx] == 0) distinctBuckets++
                    val n = ++buckets[idx]
                    if (n > topBucketCount) topBucketCount = n
                    darkSamples++
                }
                yy += step
            }
            val dominant = if (darkSamples > 0) topBucketCount.toFloat() / darkSamples else 0f
            val desc = "栏高 ${fmtPct(hFrac)} 黑 ${fmt2(darkFrac)} 白 ${fmt4(brightFrac)} " +
                "跨度 ${fmt2(span)} 中心 ${fmt2(center)} " +
                "主色 ${fmt2(dominant)}($distinctBuckets 桶)"
            val hit = hFrac >= HINT_BAR_MIN_HEIGHT_FRAC &&
                hFrac <= HINT_BAR_MAX_HEIGHT_FRAC &&
                darkFrac >= HINT_BAR_DARK_MIN &&
                dominant >= HINT_BAR_DOMINANT_MIN &&
                brightFrac >= HINT_BAR_BRIGHT_MIN &&
                brightFrac <= HINT_BAR_BRIGHT_MAX &&
                span >= HINT_BAR_TEXT_SPAN_MIN &&
                span <= HINT_BAR_TEXT_SPAN_MAX &&
                center >= HINT_BAR_TEXT_CENTER_MIN &&
                center <= HINT_BAR_TEXT_CENTER_MAX
            return hit to desc
        } catch (t: Throwable) {
            Log.w(TAG, "提示栏探测：解析截图失败 $t")
            return false to "提示栏解析失败"
        } finally {
            sw.recycle()
        }
    }

    /**
     * 判断画面是否像「横屏容器里放了一个竖屏素材」（左右为模糊/纯色填充）。
     *
     * 算法（只在中部条带内逐行采样）：
     * 1. 纵向只取 y ∈ [25%, 75%]，避开顶部返回/菜单图标与底部电商信息、字幕；
     * 2. 每行在 L/C/R 三个窗口内算相邻采样列亮度差的均值（= 横向“细节能量”）；
     * 3. 中央行能量取**中位数**（抗残余高对比文字）；两侧各窗口取全带均值；
     * 4. `spread = 中央 / 两侧均值`，`fill = 中央能量达到中位数 35% 的行占比`；
     * 5. `band = 内容带宽度`（从中央列向两侧扩展，直到列能量跌到最强列的 30%）；
     * 6. `mL / mR = 两侧窗口的平均亮度`（区分“纯黑边”与“有内容的模糊填充”）；
     * 7. 命中要求：`ec ≥ 绝对下限`（挡纯黑/纯白/纯色转场）&& `spread ≥ [PILLAR_SPREAD_MIN]`
     *    && `fill ≥ [PILLAR_FILL_MIN]` && `band ≤ [PILLAR_BAND_MAX_FRAC]`
     *    && `mL/mR ≥ [PILLAR_SIDE_LUMA_MIN]`（挡“正剧 16:9 保比例播放留黑边”）。
     *
     * @return first = 是否命中，second = 排查用描述
     */
    private fun computePillarbox(src: Bitmap): Pair<Boolean, String> {
        val sw = toSoftwareSrgb(src) ?: return false to "copy bitmap 失败"
        try {
            val w = sw.width
            val h = sw.height
            if (w <= 0 || h <= 0) return false to "截图尺寸异常"
            if (w <= h) return false to "非横屏"
            val step = PILLAR_SAMPLE_STEP
            val gw = (w + step - 1) / step
            val gl = (w * PILLAR_LEFT_X0).toInt() / step
            val gr = (w * PILLAR_LEFT_X1).toInt() / step
            val gc0 = (w * PILLAR_CENTER_X0).toInt() / step
            val gc1 = (w * PILLAR_CENTER_X1).toInt() / step
            val gr0 = (w * PILLAR_RIGHT_X0).toInt() / step
            val gr1 = (w * PILLAR_RIGHT_X1).toInt() / step
            if (gr <= gl + 1 || gc1 <= gc0 + 1 || gr1 <= gr0 + 1) return false to "窗口尺寸异常"
            val y0 = (h * PILLAR_BAND_TOP_FRAC).toInt()
            val y1 = (h * PILLAR_BAND_BOTTOM_FRAC).toInt()
            if (y1 - y0 < step * 4) return false to "条带太窄"
            val row = IntArray(w)
            val lum = IntArray(gw)
            val colE = DoubleArray(gw)
            var sumL = 0.0
            var nL = 0L
            var sumR = 0.0
            var nR = 0L
            var sumLumL = 0.0
            var nLumL = 0L
            var sumLumR = 0.0
            var nLumR = 0L
            val centers = ArrayList<Float>(64)
            var y = y0
            while (y < y1) {
                sw.getPixels(row, 0, w, 0, y, w, 1)
                for (gx in 0 until gw) {
                    val c = row[gx * step]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val lv = (299 * r + 587 * g + 114 * b) / 1000
                    lum[gx] = lv
                    // 两侧窗口统计**亮度**（不是梯度）：用来把“纯黑边”和“有内容的模糊填充”分开
                    if (gx > gl && gx < gr) {
                        sumLumL += lv
                        nLumL++
                    } else if (gx > gr0 && gx < gr1) {
                        sumLumR += lv
                        nLumR++
                    }
                }
                var lSum = 0
                var lN = 0
                var cSum = 0
                var cN = 0
                var rSum = 0
                var rN = 0
                for (gx in 1 until gw) {
                    val prev = lum[gx - 1]
                    val cur = lum[gx]
                    val d = if (cur >= prev) cur - prev else prev - cur
                    colE[gx] += d.toDouble()
                    if (gx > gl && gx < gr) {
                        lSum += d
                        lN++
                    } else if (gx > gc0 && gx < gc1) {
                        cSum += d
                        cN++
                    } else if (gx > gr0 && gx < gr1) {
                        rSum += d
                        rN++
                    }
                }
                if (lN > 0) {
                    sumL += lSum.toDouble() / lN
                    nL++
                }
                if (rN > 0) {
                    sumR += rSum.toDouble() / rN
                    nR++
                }
                if (cN > 0) centers.add(cSum.toFloat() / cN)
                y += step
            }
            if (centers.isEmpty()) return false to "条带内无采样"
            centers.sort()
            val ec = centers[centers.size / 2]
            val el = if (nL > 0) (sumL / nL).toFloat() else 0f
            val er = if (nR > 0) (sumR / nR).toFloat() else 0f
            val side = (el + er) / 2f
            val spread = when {
                side > 0.01f -> ec / side
                ec > 0f -> 99f
                else -> 0f
            }
            val mL = if (nLumL > 0) (sumLumL / nLumL).toFloat() else 0f
            val mR = if (nLumR > 0) (sumLumR / nLumR).toFloat() else 0f
            // 内容带宽度：列能量按行数归一后，与“中央行能量中位数（ec）”同一量纲比较。
            // ⚠️ 不能用 maxCol 作基准：内容带的**硬边缘**只有一两列，会把 maxCol 拉爆，
            // 实测 band 恒为 0.00（等于护栏完全失效）。改用 ec（与 fill 用的是同一个量）。
            // 边界判定要求连续 PILLAR_BAND_RUN 列都达标，避免画面内部平坦区/噪声让边界抖动。
            // 找不到边界时保守地按“满宽”处理（bandFrac=全宽 → 直接不命中），宁漏勿误判。
            var bandFrac = 1f
            val rows = centers.size
            if (rows > 0 && ec > 0f) {
                val th = (ec * PILLAR_BAND_COL_RATIO).coerceAtLeast(0.5f)
                val lo = gw / 20
                val hi = gw - 1 - lo
                var left = lo
                var i = lo
                while (i + PILLAR_BAND_RUN <= gw / 2) {
                    var ok = true
                    for (k in 0..PILLAR_BAND_RUN) {
                        if (colE[i + k] / rows < th) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        left = i
                        break
                    }
                    i++
                }
                var right = hi
                var j = hi
                while (j - PILLAR_BAND_RUN >= gw / 2) {
                    var ok = true
                    for (k in 0..PILLAR_BAND_RUN) {
                        if (colE[j - k] / rows < th) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        right = j
                        break
                    }
                    j--
                }
                bandFrac = (right - left + 1).toFloat() * step / w
            }
            var filled = 0
            val rowMin = ec * PILLAR_ROW_FILL_RATIO
            for (e in centers) {
                if (e >= rowMin) filled++
            }
            val fill = filled.toFloat() / centers.size
            val desc = "C ${fmt2(ec)} L ${fmt2(el)} R ${fmt2(er)} " +
                "spread ${fmt2(spread)} fill ${fmt2(fill)} " +
                "band ${fmt2(bandFrac)} mL ${fmt2(mL)} mR ${fmt2(mR)}"
            val hit = ec >= PILLAR_ENERGY_MIN &&
                spread >= PILLAR_SPREAD_MIN &&
                fill >= PILLAR_FILL_MIN &&
                bandFrac <= PILLAR_BAND_MAX_FRAC &&
                mL >= PILLAR_SIDE_LUMA_MIN &&
                mR >= PILLAR_SIDE_LUMA_MIN
            return hit to desc
        } catch (t: Throwable) {
            Log.w(TAG, "信箱判据：解析截图失败 $t")
            return false to "信箱解析失败"
        } finally {
            sw.recycle()
        }
    }

    private fun fmt2(v: Float): String = String.format("%.2f", v)
    private fun fmt4(v: Float): String = String.format("%.4f", v)
    private fun fmtPct(v: Float): String = String.format("%.1f%%", v * 100f)

    /**
     * 判断画面里是否有一个“实心橙色宽条”（= 广告 CTA 按钮）。
     *
     * 判据不是橙色占比（实测正剧画面占比反而更高），而是最大橙色连通块的几何形状：
     * 填充率高 + 够宽 + 够矮 + 宽高比大 + 位置在中下部。
     * 实测：“查看详情”按钮 2082x108、填充率 0.97、长宽比 19 → 命中；
     * 横屏正剧最大块 336x360、填充率 0.40 → 不命中。
     *
     * @return first = 是否判定为按钮，second = 排查用描述
     */
    private fun computeOrangeBar(src: Bitmap): Pair<Boolean, String> {
        val sw = toSoftwareSrgb(src) ?: return false to "copy bitmap 失败"
        try {
            val w = sw.width
            val h = sw.height
            if (w <= 0 || h <= 0) return false to "截图尺寸异常"
            val step = ORANGE_SAMPLE_STEP
            val gw = (w + step - 1) / step
            val gh = (h + step - 1) / step
            val totalCells = gw * gh
            // 每格存“采样到的颜色”（0 = 非橙色）。存颜色而不是布尔值，是为了能算颜色纯度：
            // 视频里的“橙色”是几百种色值混在一起，UI 按钮是同一色值。
            val grid = IntArray(totalCells)
            val row = IntArray(w)
            // step×step **盒平均**后再判定：无障碍截图路径（HardwareBuffer → copy）会引入
            // 逐像素抖动/色域转换差异（实测同一根橙色按钮：screencap 均值 #EF8E3E、App 内均值
            // #FE8720，通道极差几十），单点采样会让“颜色纯度”判据直接失效。盒平均保留
            // 视频的渐变（大范围色差），只抹掉逐像素噪声。
            val accR = IntArray(gw)
            val accG = IntArray(gw)
            val accB = IntArray(gw)
            val accN = IntArray(gw)
            var orangeCells = 0
            var gy = 0
            for (y in 0 until h) {
                sw.getPixels(row, 0, w, 0, y, w, 1)
                for (gx in 0 until gw) {
                    val xs = gx * step
                    val xe = if (xs + step < w) xs + step else w
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    for (x in xs until xe) {
                        val c = row[x]
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                    }
                    accR[gx] += sr
                    accG[gx] += sg
                    accB[gx] += sb
                    accN[gx] += xe - xs
                }
                if ((y + 1) % step == 0 || y == h - 1) {
                    val base = gy * gw
                    for (gx in 0 until gw) {
                        val n = accN[gx]
                        if (n > 0) {
                            val r = accR[gx] / n
                            val g = accG[gx] / n
                            val b = accB[gx] / n
                            // 橙色：R 高、B 低、且 R>=G>=B（纯红、纯黄都会被排除）
                            if (r >= 140 && g in 40..200 && b <= 110 && r - b >= 70 && r >= g && g >= b) {
                                grid[base + gx] = (r shl 16) or (g shl 8) or b
                                orangeCells++
                            }
                        }
                        accR[gx] = 0
                        accG[gx] = 0
                        accB[gx] = 0
                        accN[gx] = 0
                    }
                    gy++
                }
            }
            val ratioPct = if (totalCells == 0) 0f else orangeCells * 100f / totalCells
            // 逐块判定（不能只看最大的那块：实测右下角 CTA 会被画面里更大的散碎橙色块盖掉）
            var bestSize = 0
            var bestDesc = ""
            var barHit = false
            var barDesc = ""
            var ctaHit = false
            var ctaDesc = ""
            if (orangeCells > 0) {
                val visited = BooleanArray(totalCells)
                val stack = IntArray(orangeCells)
                // 当前块的格索引：用于计算块内颜色均值与“纯度”
                val comp = IntArray(orangeCells)
                // 行/列跨度统计（用 stamp 避免每块重置整个数组）
                val rowLo = IntArray(gh)
                val rowHi = IntArray(gh)
                val colLo = IntArray(gw)
                val colHi = IntArray(gw)
                val rowStamp = IntArray(gh) { -1 }
                val colStamp = IntArray(gw) { -1 }
                var compId = 0
                for (start in 0 until totalCells) {
                    if (grid[start] == 0 || visited[start]) continue
                    var sp = 0
                    stack[sp++] = start
                    visited[start] = true
                    var size = 0
                    var x0 = Int.MAX_VALUE
                    var y0 = Int.MAX_VALUE
                    var x1 = -1
                    var y1 = -1
                    while (sp > 0) {
                        val idx = stack[--sp]
                        comp[size++] = idx
                        val cx = idx % gw
                        val cy = idx / gw
                        if (cx < x0) x0 = cx
                        if (cx > x1) x1 = cx
                        if (cy < y0) y0 = cy
                        if (cy > y1) y1 = cy
                        if (cx > 0) {
                            val n = idx - 1
                            if (grid[n] != 0 && !visited[n]) {
                                visited[n] = true
                                stack[sp++] = n
                            }
                        }
                        if (cx < gw - 1) {
                            val n = idx + 1
                            if (grid[n] != 0 && !visited[n]) {
                                visited[n] = true
                                stack[sp++] = n
                            }
                        }
                        if (cy > 0) {
                            val n = idx - gw
                            if (grid[n] != 0 && !visited[n]) {
                                visited[n] = true
                                stack[sp++] = n
                            }
                        }
                        if (cy < gh - 1) {
                            val n = idx + gw
                            if (grid[n] != 0 && !visited[n]) {
                                visited[n] = true
                                stack[sp++] = n
                            }
                        }
                    }
                    if (size < ORANGE_MIN_CELLS) continue
                    val bw = (x1 - x0 + 1) * step
                    val bh = (y1 - y0 + 1) * step
                    val fill = size.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
                    val aspect = bw.toFloat() / bh.toFloat().coerceAtLeast(1f)
                    val centerYFrac = ((y0 + y1) / 2f * step) / h
                    val centerXFrac = ((x0 + x1) / 2f * step) / w
                    // 颜色纯度：块内像素相对块内均值的 ±ORANGE_COLOR_TOL 带内占比。
                    // 视频里的橙色是一大片混色，UI 按钮是同一色值（标定见常量注释）。
                    var sumR = 0L
                    var sumG = 0L
                    var sumB = 0L
                    // 诊断用：每通道极值（判断 App 截图是“抖动”还是“渐变”）
                    var minR = 255
                    var maxR = 0
                    var minG = 255
                    var maxG = 0
                    var minB = 255
                    var maxB = 0
                    for (i in 0 until size) {
                        val c = grid[comp[i]]
                        val cr = (c shr 16) and 0xFF
                        val cg = (c shr 8) and 0xFF
                        val cb = c and 0xFF
                        sumR += cr
                        sumG += cg
                        sumB += cb
                        if (cr < minR) minR = cr
                        if (cr > maxR) maxR = cr
                        if (cg < minG) minG = cg
                        if (cg > maxG) maxG = cg
                        if (cb < minB) minB = cb
                        if (cb > maxB) maxB = cb
                    }
                    val meanR = (sumR / size).toInt()
                    val meanG = (sumG / size).toInt()
                    val meanB = (sumB / size).toInt()
                    var near = 0
                    for (i in 0 until size) {
                        val c = grid[comp[i]]
                        val dr = ((c shr 16) and 0xFF) - meanR
                        val dg = ((c shr 8) and 0xFF) - meanG
                        val db = (c and 0xFF) - meanB
                        if (dr >= -ORANGE_COLOR_TOL && dr <= ORANGE_COLOR_TOL &&
                            dg >= -ORANGE_COLOR_TOL && dg <= ORANGE_COLOR_TOL &&
                            db >= -ORANGE_COLOR_TOL && db <= ORANGE_COLOR_TOL
                        ) {
                            near++
                        }
                    }
                    val uniformity = near.toFloat() / size
                    // 备选纯度度量：4bit/通道量化直方图 + ±1 邻域占比。
                    // 起因（2026-10-27 实测）：同一根橙色按钮，`adb screencap` 测得 ±12 纯度为 0.998，
                    // 而 App 内 `takeScreenshot` 测得只有 0.69（均值 #FE8720 vs #EF8E3E，通道极差几十），
                    // 说明两种截图路径的色彩管线不同 → 绝对色值不可比，需要容忍量化误差的度量。
                    var purityBin = 0f
                    var binR = 0
                    var binG = 0
                    var binB = 0
                    run {
                        val hist = IntArray(4096)
                        for (i in 0 until size) {
                            val c = grid[comp[i]]
                            val qr = ((c shr 16) and 0xFF) shr 4
                            val qg = ((c shr 8) and 0xFF) shr 4
                            val qb = (c and 0xFF) shr 4
                            hist[(qr shl 8) or (qg shl 4) or qb]++
                        }
                        var bestBin = 0
                        var bestCnt = 0
                        for (k in 0 until 4096) {
                            if (hist[k] > bestCnt) {
                                bestCnt = hist[k]
                                bestBin = k
                            }
                        }
                        val br = (bestBin shr 8) and 0xF
                        val bg = (bestBin shr 4) and 0xF
                        val bb = bestBin and 0xF
                        var nearBin = 0
                        for (dr in -1..1) {
                            for (dg in -1..1) {
                                for (db in -1..1) {
                                    val r2 = br + dr
                                    val g2 = bg + dg
                                    val b2 = bb + db
                                    if (r2 in 0..15 && g2 in 0..15 && b2 in 0..15) {
                                        nearBin += hist[(r2 shl 8) or (g2 shl 4) or b2]
                                    }
                                }
                            }
                        }
                        purityBin = nearBin.toFloat() / size
                        binR = (br shl 4) or 0x0F
                        binG = (bg shl 4) or 0x0F
                        binB = (bb shl 4) or 0x0F
                    }
                    // 近似矩形：逐行看横向跨度、逐列看纵向跨度是否接近外接框
                    compId++
                    for (i in 0 until size) {
                        val idx = comp[i]
                        val cx = idx % gw
                        val cy = idx / gw
                        if (rowStamp[cy] != compId) {
                            rowStamp[cy] = compId
                            rowLo[cy] = cx
                            rowHi[cy] = cx
                        } else {
                            if (cx < rowLo[cy]) rowLo[cy] = cx
                            if (cx > rowHi[cy]) rowHi[cy] = cx
                        }
                        if (colStamp[cx] != compId) {
                            colStamp[cx] = compId
                            colLo[cx] = cy
                            colHi[cx] = cy
                        } else {
                            if (cy < colLo[cx]) colLo[cx] = cy
                            if (cy > colHi[cx]) colHi[cx] = cy
                        }
                    }
                    val rowSpanNeed = ((x1 - x0 + 1) * ORANGE_RECT_SPAN_FRAC).toInt()
                    val colSpanNeed = ((y1 - y0 + 1) * ORANGE_RECT_SPAN_FRAC).toInt()
                    var rowsTotal = 0
                    var rowsOk = 0
                    for (cy in y0..y1) {
                        if (rowStamp[cy] != compId) continue
                        rowsTotal++
                        if (rowHi[cy] - rowLo[cy] + 1 >= rowSpanNeed) rowsOk++
                    }
                    var colsTotal = 0
                    var colsOk = 0
                    for (cx in x0..x1) {
                        if (colStamp[cx] != compId) continue
                        colsTotal++
                        if (colHi[cx] - colLo[cx] + 1 >= colSpanNeed) colsOk++
                    }
                    val rowCover = if (rowsTotal == 0) 0f else rowsOk.toFloat() / rowsTotal
                    val colCover = if (colsTotal == 0) 0f else colsOk.toFloat() / colsTotal
                    val isBarShape = fill >= ORANGE_FILL_MIN &&
                        bw >= w * ORANGE_MIN_WIDTH_FRAC &&
                        bh >= ORANGE_MIN_HEIGHT_PX &&
                        bh <= h * ORANGE_MAX_HEIGHT_FRAC &&
                        aspect >= ORANGE_MIN_ASPECT &&
                        centerYFrac >= ORANGE_MIN_CENTER_Y_FRAC
                    val isCtaShape = fill >= ORANGE_CTA_FILL_MIN &&
                        bw >= w * ORANGE_CTA_MIN_WIDTH_FRAC &&
                        bw <= w * ORANGE_CTA_MAX_WIDTH_FRAC &&
                        bh >= h * ORANGE_CTA_MIN_HEIGHT_FRAC &&
                        bh <= h * ORANGE_CTA_MAX_HEIGHT_FRAC &&
                        centerXFrac >= ORANGE_CTA_MIN_CENTER_X_FRAC &&
                        centerYFrac >= ORANGE_CTA_MIN_CENTER_Y_FRAC
                    // 形态通过时采样块中线色值：判断变化是“渐变”（单调）还是“抖动”（随机）
                    var lineDesc = ""
                    if (isBarShape || isCtaShape) {
                        val midY = (y0 + y1) / 2
                        val sb = StringBuilder()
                        var shown = 0
                        var seenOnRow = 0
                        for (i in 0 until size) {
                            val idx = comp[i]
                            if (idx / gw != midY) continue
                            if (seenOnRow % 12 == 0) {
                                sb.append(String.format(" #%06X", grid[idx]))
                                shown++
                            }
                            seenOnRow++
                            if (shown >= 8) break
                        }
                        lineDesc = sb.toString()
                    }
                    val desc = "块 ${bw}x$bh " +
                        "填充 ${String.format("%.2f", fill)} " +
                        "长宽比 ${String.format("%.1f", aspect)} " +
                        "中心X ${String.format("%.2f", centerXFrac)} " +
                        "中心Y ${String.format("%.2f", centerYFrac)} " +
                        "纯度 ${String.format("%.2f", uniformity)} " +
                        "纯度4bit ${String.format("%.2f", purityBin)} " +
                        "行跨 ${String.format("%.2f", rowCover)} " +
                        "列跨 ${String.format("%.2f", colCover)} " +
                        "范围 R$minR-$maxR G$minG-$maxG B$minB-$maxB " +
                        "色 #${String.format("%02X%02X%02X", meanR, meanG, meanB)} " +
                        "桶 #${String.format("%02X%02X%02X", binR, binG, binB)}" +
                        (if (lineDesc.isEmpty()) "" else " 中线:$lineDesc")
                    if (size > bestSize) {
                        bestSize = size
                        bestDesc = desc
                    }
                    // 判定：**几何为主**（填充 + 行列跨度）。
                    // 颜色纯度不用作判据 —— 实测同一根橙色按钮，`adb screencap` 测得 ±12 纯度 0.998、
                    // App 内只有 0.68（正剧块反而有 0.38~0.47），因为无障碍截图的色彩管线不同。
                    // 纯度/颜色/极差/4bit桶 仅作排查日志。
                    val uniform = rowCover >= ORANGE_RECT_ROW_COVER_MIN &&
                        colCover >= ORANGE_RECT_COL_COVER_MIN
                    // 形态 A：横贯屏幕的实心橙色宽条（旧版“查看详情”按钮）
                    if (!barHit && uniform && isBarShape) {
                        barHit = true
                        barDesc = desc
                    }
                    // 形态 B：右下角的小实心橙色按钮（直播/电商广告的角标 CTA）
                    if (!ctaHit && uniform && isCtaShape) {
                        ctaHit = true
                        ctaDesc = desc
                    }
                }
            }
            if (bestSize == 0) {
                return false to "橙色 ${String.format("%.2f", ratioPct)}% 无连通块(≥$ORANGE_MIN_CELLS 格)"
            }
            val info = "橙色 ${String.format("%.2f", ratioPct)}% " + when {
                barHit -> "宽条 $barDesc"
                ctaHit -> "右下角按钮 $ctaDesc"
                else -> "最大 $bestDesc"
            }
            return (barHit || ctaHit) to info
        } finally {
            sw.recycle()
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

    /**
     * 一次节点遍历的统计信息。
     * - [visibleNodeCount]：**过滤前**的可见节点数（排查用，也用于日志对比口径）；
     * - [hasVideoSurface]：页面是否挂了视频表面（SurfaceView/TextureView）；
     * - [hasScrollableContent]：页面是否有可滚动容器（列表 / 网格 / ViewPager）；
     * - [hasWebView]：页面是否有 WebView（试玩/落地页广告常用它渲染创意，正剧不用）；
     * - [classCounts]：**过滤前**各类名的计数（仅调试浮层开启时统计）——
     *   “画面有内容却读不到一个字”时，只有类名能说明创意到底是用什么渲染的。
     *   这些信息在过滤后的节点列表里拿不到，所以只能在遍历时记录。
     */
    private class NodeStats {
        var visibleNodeCount = 0
        var hasVideoSurface = false
        var hasScrollableContent = false
        var hasWebView = false
        val classCounts = HashMap<String, Int>()

        /** 类名直方图（降序、去掉包名前缀），供调试浮层/日志用 */
        fun classHistogram(limit: Int = 6): String {
            if (classCounts.isEmpty()) return ""
            return classCounts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .joinToString(",") { "${it.key}×${it.value}" }
        }
    }

    private fun collectNodes(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        stats: NodeStats,
        collectClasses: Boolean,
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            // 拖动悬浮球已开始：立即中止本次检测，把主线程让给拖动
            if (isDragging) return
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            stats.visibleNodeCount++
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("SurfaceView") || cls.contains("TextureView") ||
                cls.contains("VideoView")
            ) {
                stats.hasVideoSurface = true
            }
            if (cls.contains("WebView")) {
                stats.hasWebView = true
            }
            if (collectClasses && cls.isNotEmpty()) {
                // 只留简单类名（android.widget.FrameLayout → FrameLayout），避免日志过长
                val simple = cls.substringAfterLast('.')
                stats.classCounts[simple] = (stats.classCounts[simple] ?: 0) + 1
            }
            if (node.isScrollable) {
                stats.hasScrollableContent = true
            }
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
    private fun collectFromAllWindows(
        out: MutableList<AccessibilityNodeInfo>,
        fgPkg: String,
        stats: NodeStats,
        collectClasses: Boolean = false,
    ) {
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
            collectNodes(root, out, stats, collectClasses)
        }
        // 兜底：某些设备上 windows 列表为空时退回活动窗口
        if (out.isEmpty()) {
            service.rootInActiveWindow?.let { collectNodes(it, out, stats, collectClasses) }
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
