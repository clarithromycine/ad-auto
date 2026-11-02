package com.wisight.adauto.core

import android.os.SystemClock

/**
 * 调试快照：`AdDetector` 写入“本次判定详情”，`FloatingBallService` 的调试浮层读取显示。
 *
 * 用单例 + `@Volatile` 字符串而不是回调，是为了**彻底解耦**：
 * - 两个 Service 的生命周期互不依赖（无障碍服务可能先于悬浮球服务起来，反之亦然）；
 * - 调试关闭时写端可以完全不更新（零开销），读端也不必订阅。
 *
 * ⚠️ 这个对象只承载“排查用文本”，**绝不参与任何判定逻辑**。
 */
object DebugTrace {

    /** 最近一次判定的详细描述（多行文本） */
    @Volatile
    var detail: String = ""
        private set

    /** 最近一次更新时刻（uptimeMillis，0 = 从未更新） */
    @Volatile
    var updatedAt: Long = 0L
        private set

    /** 判定序号：用来在浮层上区分“是不是新的一次判定” */
    @Volatile
    var seq: Long = 0L
        private set

    fun update(text: String) {
        detail = text
        updatedAt = SystemClock.uptimeMillis()
        seq++
    }

    fun clear() {
        detail = ""
        updatedAt = 0L
        seq++
    }
}
