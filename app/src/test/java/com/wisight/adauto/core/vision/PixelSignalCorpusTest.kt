package com.wisight.adauto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 像素判据回归语料。
 *
 * 语料由 `tools/ui-diagnose/make_fixtures.py` 生成，文件名自带期望结论：
 * `<criterion>_<slug>_<hit|miss>.png`。真机截图转成 PNG 后直接丢进
 * `app/src/test/resources/fixtures/` 即可纳入回归，不需要改测试代码。
 *
 * 这些用例覆盖的是**判定行为**（命中/不命中 + 关键几何量），也就是重构前
 * 只存在于真机日志里的那部分知识。
 */
class PixelSignalCorpusTest {

    private class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        val rows: RowSource get() = rowSourceOf(pixels, width)
    }

    private fun fixtureDir(): File = listOf(
        File("src/test/resources/fixtures"),
        File("app/src/test/resources/fixtures"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到语料目录，cwd=${File("").absolutePath}")

    private fun load(name: String): Frame {
        val img = PngFrameLoader.read(File(fixtureDir(), name))
        return Frame(img.width, img.height, img.argb)
    }

    /** 按文件名前缀跑完整个子语料，逐个比对 `_hit` / `_miss`。 */
    private fun checkCorpus(prefix: String, detect: (Frame) -> PixelEvidence) {
        val files = fixtureDir()
            .listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".png") }!!
            .sortedBy { it.name }
        assertTrue("$prefix 语料为空", files.isNotEmpty())
        for (f in files) {
            val expected = when {
                f.nameWithoutExtension.endsWith("_hit") -> true
                f.nameWithoutExtension.endsWith("_miss") -> false
                else -> error("语料文件名必须以 _hit / _miss 结尾：${f.name}")
            }
            val ev = detect(load(f.name))
            assertEquals("${f.name}: ${ev.describe()}", expected, ev.hit)
        }
    }

    @Test
    fun orange() = checkCorpus("orange_") { OrangeButton.detect(it.width, it.height, it.rows) }

    @Test
    fun hintBar() = checkCorpus("hint_") { BottomHintBar.detect(it.width, it.height, it.rows) }

    @Test
    fun pillarbox() = checkCorpus("pillar_") { Pillarbox.detect(it.width, it.height, it.rows) }

    /** 实测「查看详情」宽条：2082x108、填充率 0.97 → 形态 A，且不应同时命中形态 B。 */
    @Test
    fun orangeBarGeometryMatchesCalibrationSample() {
        val f = load("orange_bar_hit.png")
        val ev = OrangeButton.detect(f.width, f.height, f.rows)
        val bar = requireNotNull(ev.barBlock) { ev.describe() }
        assertEquals(2080, bar.widthPx)
        assertEquals(108, bar.heightPx)
        assertEquals(1.0f, bar.fill, 1e-3f)
        assertEquals(0.71f, bar.centerYFrac, 5e-3f)
        assertNull(ev.ctaBlock)
    }

    /** 右下角 CTA 必须逐块判定：画面里更大的散碎橙块不能把它盖掉。 */
    @Test
    fun orangeCtaFoundEvenWithLargerDecoyBlock() {
        val f = load("orange_cta_hit.png")
        val ev = OrangeButton.detect(f.width, f.height, f.rows)
        val cta = requireNotNull(ev.ctaBlock) { ev.describe() }
        assertEquals(216, cta.widthPx)
        assertEquals(84, cta.heightPx)
        assertNull(ev.barBlock)
        assertNotNull("语料里应存在更大的干扰块", ev.bestBlock)
        assertTrue(
            "干扰块应比 CTA 大，才能证明是逐块判定: ${ev.bestBlock!!.sizeCells} vs ${cta.sizeCells}",
            ev.bestBlock!!.sizeCells > cta.sizeCells,
        )
    }

    /** 提示栏：纯黑底 + 居中白字。 */
    @Test
    fun hintBarGeometryMatchesCalibrationSample() {
        val f = load("hint_bar_hit.png")
        val ev = BottomHintBar.detect(f.width, f.height, f.rows)
        assertTrue(ev.hit)
        assertTrue("栏高 ${ev.bandHeightFrac}", ev.bandHeightFrac in 0.04f..0.14f)
        assertTrue("中心 ${ev.textCenterFrac}", ev.textCenterFrac in 0.30f..0.70f)
        assertEquals(1.0f, ev.dominantFrac, 1e-3f)
        assertEquals(1, ev.distinctBuckets)
    }

    /** 信箱：竖屏素材带很窄、两侧是有内容的模糊填充。 */
    @Test
    fun pillarboxGeometryMatchesCalibrationSample() {
        val f = load("pillar_squeeze_hit.png")
        val ev = Pillarbox.detect(f.width, f.height, f.rows)
        assertTrue(ev.hit)
        assertEquals(0.38f, ev.bandFrac, 5e-3f)
        assertTrue("两侧亮度应达到模糊填充的水平: ${ev.meanLumaLeft}/${ev.meanLumaRight}",
            ev.meanLumaLeft >= 24f && ev.meanLumaRight >= 24f)
    }

    /** 16:9 正剧在 2.2:1 屏上保比例播放 → 内容带 0.80 且两侧纯黑，绝不能判命中。 */
    @Test
    fun letterboxedDramaIsNotPillarbox() {
        val f = load("pillar_letterbox_miss.png")
        val ev = Pillarbox.detect(f.width, f.height, f.rows)
        assertTrue(!ev.hit)
        assertEquals(0f, ev.meanLumaLeft, 1e-3f)
        assertEquals(0f, ev.meanLumaRight, 1e-3f)
        assertTrue("内容带宽度 ${ev.bandFrac} 应超过 0.50 上限", ev.bandFrac > 0.50f)
    }
}
