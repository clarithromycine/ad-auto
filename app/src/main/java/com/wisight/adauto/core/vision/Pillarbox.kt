package com.wisight.adauto.core.vision

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

/** 信箱判据的一轮结果。 */
data class PillarboxEvidence(
    override val hit: Boolean,
    /** 中央窗口行能量中位数 */
    val centerEnergy: Float,
    /** 左侧窗口梯度能量均值 */
    val leftEnergy: Float,
    /** 右侧窗口梯度能量均值 */
    val rightEnergy: Float,
    /** 中央 / 两侧能量比 */
    val spread: Float,
    /** 中央能量达到中位数 35% 的行占比 */
    val fill: Float,
    /** 内容带宽度占屏宽比例 */
    val bandFrac: Float,
    /** 左侧窗口平均亮度 */
    val meanLumaLeft: Float,
    /** 右侧窗口平均亮度 */
    val meanLumaRight: Float,
    private val failure: String? = null,
) : PixelEvidence {

    override fun describe(): String = failure ?: (
        "C ${fmt2(centerEnergy)} L ${fmt2(leftEnergy)} R ${fmt2(rightEnergy)} " +
            "spread ${fmt2(spread)} fill ${fmt2(fill)} " +
            "band ${fmt2(bandFrac)} mL ${fmt2(meanLumaLeft)} mR ${fmt2(meanLumaRight)}"
        )

    companion object {
        /** 截图不可用 / 尺寸异常 / 解析失败时的空证据。 */
        fun failure(reason: String): PillarboxEvidence =
            PillarboxEvidence(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, reason)
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
 * 纯函数：不持有状态、不碰 `android.graphics`，输入是尺寸 + 逐行像素。
 */
object Pillarbox {

    fun detect(width: Int, height: Int, rows: RowSource): PillarboxEvidence {
        if (width <= 0 || height <= 0) return PillarboxEvidence.failure("截图尺寸异常")
        val w = width
        val h = height
        if (w <= h) return PillarboxEvidence.failure("非横屏")
        val step = PILLAR_SAMPLE_STEP
        val gw = (w + step - 1) / step
        val gl = (w * PILLAR_LEFT_X0).toInt() / step
        val gr = (w * PILLAR_LEFT_X1).toInt() / step
        val gc0 = (w * PILLAR_CENTER_X0).toInt() / step
        val gc1 = (w * PILLAR_CENTER_X1).toInt() / step
        val gr0 = (w * PILLAR_RIGHT_X0).toInt() / step
        val gr1 = (w * PILLAR_RIGHT_X1).toInt() / step
        if (gr <= gl + 1 || gc1 <= gc0 + 1 || gr1 <= gr0 + 1) return PillarboxEvidence.failure("窗口尺寸异常")
        val y0 = (h * PILLAR_BAND_TOP_FRAC).toInt()
        val y1 = (h * PILLAR_BAND_BOTTOM_FRAC).toInt()
        if (y1 - y0 < step * 4) return PillarboxEvidence.failure("条带太窄")
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
            rows.readRow(y, row)
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
        if (centers.isEmpty()) return PillarboxEvidence.failure("条带内无采样")
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
        val rowsCount = centers.size
        if (rowsCount > 0 && ec > 0f) {
            val th = (ec * PILLAR_BAND_COL_RATIO).coerceAtLeast(0.5f)
            val lo = gw / 20
            val hi = gw - 1 - lo
            var left = lo
            var i = lo
            while (i + PILLAR_BAND_RUN <= gw / 2) {
                var ok = true
                for (k in 0..PILLAR_BAND_RUN) {
                    if (colE[i + k] / rowsCount < th) {
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
                    if (colE[j - k] / rowsCount < th) {
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
        val hit = ec >= PILLAR_ENERGY_MIN &&
            spread >= PILLAR_SPREAD_MIN &&
            fill >= PILLAR_FILL_MIN &&
            bandFrac <= PILLAR_BAND_MAX_FRAC &&
            mL >= PILLAR_SIDE_LUMA_MIN &&
            mR >= PILLAR_SIDE_LUMA_MIN
        return PillarboxEvidence(
            hit = hit,
            centerEnergy = ec,
            leftEnergy = el,
            rightEnergy = er,
            spread = spread,
            fill = fill,
            bandFrac = bandFrac,
            meanLumaLeft = mL,
            meanLumaRight = mR,
        )
    }
}
