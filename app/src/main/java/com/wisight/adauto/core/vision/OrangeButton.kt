package com.wisight.adauto.core.vision

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
 * **颜色纯度**标定值（2026-10-27 用户提出）：“短剧画面里的橙色块不会是一个色值，
 * 但按钮色块一定是同一个色值”。把橙块内像素相对块内均值的偏离限定在 ±该值内，
 * 再统计带内占比。实测（步长 4px、屏幕 2670x1200）:
 * - 广告右下角 CTA：均值 #EF8E3E，最大标准差 6.9，±12 带内占比 **0.98**
 * - 广告里的视频橙块：均值 #BD8953，标准差 17.8，带内占比 **0.38**
 * - 正剧里的橙块：均值 #A1744F，标准差 12.8，带内占比最高 **0.76**
 * 所以纯度曾是理论上分离度最大的判据。
 *
 * ⚠️ **但纯度不作判定条件**：同一条按钮在 `adb screencap` 与 App 内 `takeScreenshot`
 * 两条路径下色值不可比（实测 ±12 纯度 0.998 vs 0.69）。该常量和 [purityBin] 只用于
 * 排查色彩管线，判定改用**几何**（见 [ORANGE_RECT_ROW_COVER_MIN]）。
 */
private const val ORANGE_COLOR_TOL = 12
/**
 * **近似矩形**判据（2026-10-27 用户提出：“按钮一定是一个近似矩形”）。
 * 只看填充率不够 —— 斜向色带/L 形/圆弧也能填满外接框一半。矩形要做的是
 * “每一行的横向跨度都接近外接框宽度，每一列的纵向跨度都接近外接框高度”。
 * 实测: 广告 CTA 行跨 1.00 / 列跨 0.94；视频橙块行跨 0.00~0.59、列跨 0.26~0.70。
 */
/** 单行/单列的跨度达到外接框该方向尺寸的这个比例，才算“这一行是满的” */
private const val ORANGE_RECT_SPAN_FRAC = 0.80f
/**
 * 行/列“满”的比例下限。
 * 实测（**App 内截图像素**，盒平均后）：真广告按钮 行跨 1.00 / 列跨 0.98、
 * 填充 0.97；正剧块 填充≤0.50、行跨≤0.30、列跨≤0.35 → 分离度极大。
 * 注意：这两个几何量在 `adb screencap` 与 App `takeScreenshot` 两条路径下
 * **完全一致**（同一根按钮两边都是填充 0.97 / 行跨 1.00 / 列跨 0.98），
 * 所以几何是可靠判据，颜色不是（见 [ORANGE_COLOR_TOL]）。
 */
private const val ORANGE_RECT_ROW_COVER_MIN = 0.90f
private const val ORANGE_RECT_COL_COVER_MIN = 0.88f

/** 形态 A：横贯屏幕的实心橙色宽条 */
private fun isBarShape(
    fill: Float,
    bw: Int,
    bh: Int,
    aspect: Float,
    centerYFrac: Float,
    w: Int,
    h: Int,
): Boolean = fill >= ORANGE_FILL_MIN &&
    bw >= w * ORANGE_MIN_WIDTH_FRAC &&
    bh >= ORANGE_MIN_HEIGHT_PX &&
    bh <= h * ORANGE_MAX_HEIGHT_FRAC &&
    aspect >= ORANGE_MIN_ASPECT &&
    centerYFrac >= ORANGE_MIN_CENTER_Y_FRAC

/** 形态 B：右下角的小实心橙色按钮 */
private fun isCtaShape(
    fill: Float,
    bw: Int,
    bh: Int,
    centerXFrac: Float,
    centerYFrac: Float,
    w: Int,
    h: Int,
): Boolean = fill >= ORANGE_CTA_FILL_MIN &&
    bw >= w * ORANGE_CTA_MIN_WIDTH_FRAC &&
    bw <= w * ORANGE_CTA_MAX_WIDTH_FRAC &&
    bh >= h * ORANGE_CTA_MIN_HEIGHT_FRAC &&
    bh <= h * ORANGE_CTA_MAX_HEIGHT_FRAC &&
    centerXFrac >= ORANGE_CTA_MIN_CENTER_X_FRAC &&
    centerYFrac >= ORANGE_CTA_MIN_CENTER_Y_FRAC

/** 一个候选「实心橙色块」的证据：几何量参与判定，颜色量仅供排查。 */
class OrangeBlock internal constructor(
    val sizeCells: Int,
    val widthPx: Int,
    val heightPx: Int,
    val fill: Float,
    val aspect: Float,
    val centerXFrac: Float,
    val centerYFrac: Float,
    val rowCover: Float,
    val colCover: Float,
    /** ±[ORANGE_COLOR_TOL] 带内占比。**不作判据**，仅排查色彩管线。 */
    val colorUniformity: Float,
    /** 4bit 量化 + ±1 邻域纯度。**不作判据**，同上。 */
    val binUniformity: Float,
    /** 通道极值 / 均值色 / 中线采样。仅供排查。 */
    val diagnostics: String,
) {
    /**
     * 判定：**几何为主**（填充 + 行列跨度）。
     * 颜色纯度不用作判据 —— 实测同一根橙色按钮，`adb screencap` 测得 ±12 纯度 0.998、
     * App 内只有 0.68（正剧块反而有 0.38~0.47），因为无障碍截图的色彩管线不同。
     */
    internal val isRectangular: Boolean
        get() = rowCover >= ORANGE_RECT_ROW_COVER_MIN && colCover >= ORANGE_RECT_COL_COVER_MIN

    fun describe(): String =
        "块 ${widthPx}x$heightPx 填充 ${fmt2(fill)} 长宽比 ${fmt1(aspect)} " +
            "中心X ${fmt2(centerXFrac)} 中心Y ${fmt2(centerYFrac)} " +
            "纯度 ${fmt2(colorUniformity)} 纯度4bit ${fmt2(binUniformity)} " +
            "行跨 ${fmt2(rowCover)} 列跨 ${fmt2(colCover)} $diagnostics"
}

/** 橙色判据的一轮结果。 */
data class OrangeEvidence(
    override val hit: Boolean,
    /** 橙色格占全部采样格的比例（%，不是逐像素比例） */
    val ratioPct: Float,
    /** 第一个命中形态 A 的块 */
    val barBlock: OrangeBlock?,
    /** 第一个命中形态 B 的块 */
    val ctaBlock: OrangeBlock?,
    /** 面积最大的橙色块（无块达到 [ORANGE_MIN_CELLS] 时为 null） */
    val bestBlock: OrangeBlock?,
    private val failure: String? = null,
) : PixelEvidence {

    override fun describe(): String = when {
        failure != null -> failure
        bestBlock == null -> "橙色 ${fmt2(ratioPct)}% 无连通块(≥$ORANGE_MIN_CELLS 格)"
        barBlock != null -> "橙色 ${fmt2(ratioPct)}% 宽条 ${barBlock.describe()}"
        ctaBlock != null -> "橙色 ${fmt2(ratioPct)}% 右下角按钮 ${ctaBlock.describe()}"
        else -> "橙色 ${fmt2(ratioPct)}% 最大 ${bestBlock.describe()}"
    }

    companion object {
        /** 截图不可用 / 尺寸异常 / 解析失败时的空证据。 */
        fun failure(reason: String): OrangeEvidence =
            OrangeEvidence(false, 0f, null, null, null, reason)
    }
}

/**
 * 判断画面里是否存在「实心橙色按钮」（形态 A 宽条 / 形态 B 右下角 CTA）。
 *
 * 纯函数：不持有状态、不碰 `android.graphics`，输入是尺寸 + 逐行像素。
 */
object OrangeButton {

    fun detect(width: Int, height: Int, rows: RowSource): OrangeEvidence {
        if (width <= 0 || height <= 0) return OrangeEvidence.failure("截图尺寸异常")
        val w = width
        val h = height
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
            rows.readRow(y, row)
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
                        if (isOrange(r, g, b)) {
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
        var bestBlock: OrangeBlock? = null
        var barBlock: OrangeBlock? = null
        var ctaBlock: OrangeBlock? = null
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
                var nearBin = 0
                val bnR = (bestBin shr 8) and 0xF
                val bnG = (bestBin shr 4) and 0xF
                val bnB = bestBin and 0xF
                for (dr in -1..1) {
                    for (dg in -1..1) {
                        for (db in -1..1) {
                            val r2 = bnR + dr
                            val g2 = bnG + dg
                            val b2 = bnB + db
                            if (r2 in 0..15 && g2 in 0..15 && b2 in 0..15) {
                                nearBin += hist[(r2 shl 8) or (g2 shl 4) or b2]
                            }
                        }
                    }
                }
                val purityBin = nearBin.toFloat() / size
                val binR = (bnR shl 4) or 0x0F
                val binG = (bnG shl 4) or 0x0F
                val binB = (bnB shl 4) or 0x0F
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
                // 形态判定（几何为主，见常量注释）
                val isBar = isBarShape(fill, bw, bh, aspect, centerYFrac, w, h)
                val isCta = isCtaShape(fill, bw, bh, centerXFrac, centerYFrac, w, h)
                // 形态通过时采样块中线色值：判断变化是“渐变”（单调）还是“抖动”（随机）
                var lineDesc = ""
                if (isBar || isCta) {
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
                val block = OrangeBlock(
                    sizeCells = size,
                    widthPx = bw,
                    heightPx = bh,
                    fill = fill,
                    aspect = aspect,
                    centerXFrac = centerXFrac,
                    centerYFrac = centerYFrac,
                    rowCover = rowCover,
                    colCover = colCover,
                    colorUniformity = uniformity,
                    binUniformity = purityBin,
                    diagnostics = buildDiagnostics(minR, maxR, minG, maxG, minB, maxB, meanR, meanG, meanB, binR, binG, binB, lineDesc),
                )
                if (size > bestSize) {
                    bestSize = size
                    bestBlock = block
                }
                if (block.isRectangular && isBar) {
                    if (barBlock == null) barBlock = block
                }
                if (block.isRectangular && isCta) {
                    if (ctaBlock == null) ctaBlock = block
                }
            }
        }
        return OrangeEvidence(
            hit = barBlock != null || ctaBlock != null,
            ratioPct = ratioPct,
            barBlock = barBlock,
            ctaBlock = ctaBlock,
            bestBlock = bestBlock,
        )
    }

    /** 橙色：R 高、B 低、且 R>=G>=B（纯红、纯黄都会被排除） */
    private fun isOrange(r: Int, g: Int, b: Int): Boolean =
        r >= 140 && g in 40..200 && b <= 110 && r - b >= 70 && r >= g && g >= b

    private fun buildDiagnostics(
        minR: Int, maxR: Int,
        minG: Int, maxG: Int,
        minB: Int, maxB: Int,
        meanR: Int, meanG: Int, meanB: Int,
        binR: Int, binG: Int, binB: Int,
        lineDesc: String,
    ): String = "范围 R$minR-$maxR G$minG-$maxG B$minB-$maxB " +
        "色 #${hex6(meanR, meanG, meanB)} 桶 #${hex6(binR, binG, binB)}" +
        (if (lineDesc.isEmpty()) "" else " 中线:$lineDesc")
}
