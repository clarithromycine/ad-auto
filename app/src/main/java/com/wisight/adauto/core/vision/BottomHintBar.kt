package com.wisight.adauto.core.vision

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

/** 提示栏判据的一轮结果。 */
data class HintBarEvidence(
    override val hit: Boolean,
    /** 底部暗带高度占屏高的比例 */
    val bandHeightFrac: Float,
    /** 暗带内暗像素占比 */
    val darkFrac: Float,
    /** 暗带内亮像素（白字）占比 */
    val brightFrac: Float,
    /** 白字横向跨度占屏宽的比例 */
    val textSpanFrac: Float,
    /** 白字水平中心占屏宽的比例 */
    val textCenterFrac: Float,
    /** 暗像素主色桶占比 */
    val dominantFrac: Float,
    /** 暗像素落在多少个色桶里 */
    val distinctBuckets: Int,
    private val failure: String? = null,
) : PixelEvidence {

    override fun describe(): String = failure ?: (
        "栏高 ${fmtPct(bandHeightFrac)} 黑 ${fmt2(darkFrac)} 白 ${fmt4(brightFrac)} " +
            "跨度 ${fmt2(textSpanFrac)} 中心 ${fmt2(textCenterFrac)} " +
            "主色 ${fmt2(dominantFrac)}($distinctBuckets 桶)"
        )

    companion object {
        /** 截图不可用 / 尺寸异常 / 解析失败时的空证据。 */
        fun failure(reason: String): HintBarEvidence =
            HintBarEvidence(false, 0f, 0f, 0f, 0f, 0f, 0f, 0, reason)
    }
}

/**
 * 判断画面底部是否存在「∧ 上滑继续看短剧」那种**纯黑底 + 居中白字**的提示栏。
 *
 * 算法（自底向上，单遍）：
 * 1. 逐行统计暗像素（亮度<70）与亮像素（亮度>200）占比；
 * 2. 只要该行暗占比 ≥ [HINT_BAR_ROW_DARK_MIN] 就并入提示栏，否则说明栏顶到了；
 * 3. 校验栏高/黑底/白字占比/白字横向跨度与中心。
 *
 * 纯函数：不持有状态、不碰 `android.graphics`，输入是尺寸 + 逐行像素。
 */
object BottomHintBar {

    fun detect(width: Int, height: Int, rows: RowSource): HintBarEvidence {
        if (width <= 0 || height <= 0) return HintBarEvidence.failure("截图尺寸异常")
        val w = width
        val h = height
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
            rows.readRow(y, row)
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
        if (cells == 0) return HintBarEvidence.failure("底部无黑色栏")
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
            rows.readRow(yy, row)
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
        return HintBarEvidence(
            hit = hit,
            bandHeightFrac = hFrac,
            darkFrac = darkFrac,
            brightFrac = brightFrac,
            textSpanFrac = span,
            textCenterFrac = center,
            dominantFrac = dominant,
            distinctBuckets = distinctBuckets,
        )
    }
}
