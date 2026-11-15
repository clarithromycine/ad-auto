package com.wisight.adauto.core.vision

/**
 * 单条视觉判据的结构化证据。
 *
 * [hit] 是裁决结果，其余字段是参与判定的原始数值（可被单测断言）；
 * [describe] 输出与重构前逐字节一致的一行排查描述，供 logcat / 调试浮层使用。
 */
interface PixelEvidence {
    val hit: Boolean

    /** 一行人读描述。格式化只服务于排查，**不参与判定**。 */
    fun describe(): String
}

internal fun fmt1(v: Float): String = String.format("%.1f", v)
internal fun fmt2(v: Float): String = String.format("%.2f", v)
internal fun fmt4(v: Float): String = String.format("%.4f", v)
internal fun fmtPct(v: Float): String = String.format("%.1f%%", v * 100f)
internal fun hex6(r: Int, g: Int, b: Int): String = String.format("%02X%02X%02X", r, g, b)
