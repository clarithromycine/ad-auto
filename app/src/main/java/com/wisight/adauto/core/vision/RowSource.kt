package com.wisight.adauto.core.vision

/**
 * 只读像素源：按行取 ARGB 像素。
 *
 * 为什么不直接传整帧 `IntArray`：一张 3200x2136 的截图铺成 `IntArray` 要多占约 27MB，
 * 而现有实现本来就是逐行 `getPixels` 读取的。这里保留那个内存特征，同时把像素判据
 * 从 `android.graphics.Bitmap` 里解耦出来 —— 单测/离线分析直接用普通数组即可。
 */
fun interface RowSource {
    /** 把第 [y] 行写入 [out]（长度必须 ≥ 图像宽度）。 */
    fun readRow(y: Int, out: IntArray)
}

/** 把整帧 ARGB 数组包成 [RowSource]（单测 / 离线分析用）。 */
fun rowSourceOf(pixels: IntArray, width: Int): RowSource = RowSource { y, out ->
    System.arraycopy(pixels, y * width, out, 0, width)
}
