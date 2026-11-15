package com.wisight.adauto.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照 schema 与两个“分不出来”闸门的回归测试。
 *
 * 浮层字段表以前只是 README 里的一段文字、格式靠手写拼接；现在它是
 * [DetectionSnapshot.overlayText] 的输出契约，改坏了会被这里挡住。
 */
class DetectionSnapshotTest {

    @Test
    fun `横屏模糊态：红果 + 横屏 + 无正剧控件`() {
        assertTrue(snapshot().landscapeAmbiguous(hasAction = false))
        assertFalse("竖屏不成立", snapshot(frameWidth = 720, frameHeight = 1280).landscapeAmbiguous(false))
        assertFalse("别的 App 不成立", snapshot(fgPkg = "com.other").landscapeAmbiguous(false))
        assertFalse(
            "有正剧控件不成立",
            snapshot(hasPlaybackControls = true).landscapeAmbiguous(false),
        )
        assertFalse("已经拿到动作就不需要截图", snapshot().landscapeAmbiguous(hasAction = true))
    }

    @Test
    fun `整页读不到字：必须没有视频层`() {
        assertTrue(snapshot(pageText = "").contentlessAmbiguous(hasAction = false))
        assertFalse(
            "有视频层时提示栏判据会和正剧字幕撞车",
            snapshot(pageText = "", hasVideoSurface = true).contentlessAmbiguous(false),
        )
        assertFalse(
            "正剧控件在场不算待定",
            snapshot(pageText = "", hasPlaybackControls = true).contentlessAmbiguous(false),
        )
        assertFalse(
            "页面能读到较多文字不算待定",
            snapshot(pageText = "这是一段明显超过三十个字的正常剧集简介文案，用来测试「整页读不到字」的文字长度阈值边界").contentlessAmbiguous(false),
        )
        assertFalse(snapshot(pageText = "").contentlessAmbiguous(hasAction = true))
    }

    @Test
    fun `浮层正文格式固定`() {
        val text = snapshot(frameWidth = 1280, frameHeight = 720, pageText = "abc")
            .overlayText(lastPillarInfo = "", actionEnabled = true)
        assertEquals(
            "fg=com.phoenix.read  1280x720\n" +
                "nodes=10/20 textLen=3\n" +
                "rv=false tex=true adItem=false\n" +
                "sparse=true cd=true landNoSurf=false\n" +
                "surface=false scroll=false webView=false",
            text,
        )
    }

    @Test
    fun `仅调试模式与信箱上次结果都体现在浮层里`() {
        val text = snapshot(pageText = "abc").overlayText(
            lastPillarInfo = "C 12.00 L 1.00 R 0.50",
            actionEnabled = false,
        )
        assertTrue(text.startsWith("【仅调试·不执行】\n"))
        assertTrue(text.endsWith("\n柱(上次) C 12.00 L 1.00 R 0.50"))
    }

    @Test
    fun `scan 日志保持一行可 grep`() {
        val line = snapshot(pageText = "abc").scanLogLine(adSwipeStreak = 3)
        assertTrue(line.startsWith("scan: fg=com.phoenix.read nodes=10 rawNodes=20 "))
        assertTrue(line.contains("frame=1280x720"))
        assertTrue(line.endsWith("countdown=true streak=3"))
    }
}
