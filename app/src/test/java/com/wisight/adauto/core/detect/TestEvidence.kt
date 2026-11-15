package com.wisight.adauto.core.detect

import com.wisight.adauto.core.vision.HintBarEvidence
import com.wisight.adauto.core.vision.OrangeEvidence
import com.wisight.adauto.core.vision.PillarboxEvidence

/**
 * 判定链路的测试替身：只关心 `hit`，不关心像素数值。
 * （真实像素数值由 `PixelSignalCorpusTest` 用录制语料覆盖。）
 */
internal fun emptyOrange(hit: Boolean = false): OrangeEvidence =
    OrangeEvidence(hit = hit, ratioPct = 0f, barBlock = null, ctaBlock = null, bestBlock = null)

internal fun emptyHint(hit: Boolean = false): HintBarEvidence =
    HintBarEvidence(
        hit = hit,
        bandHeightFrac = 0f,
        darkFrac = 0f,
        brightFrac = 0f,
        textSpanFrac = 0f,
        textCenterFrac = 0f,
        dominantFrac = 0f,
        distinctBuckets = 0,
    )

internal fun emptyPillar(hit: Boolean = false): PillarboxEvidence =
    PillarboxEvidence(
        hit = hit,
        centerEnergy = 0f,
        leftEnergy = 0f,
        rightEnergy = 0f,
        spread = 0f,
        fill = 0f,
        bandFrac = 0f,
        meanLumaLeft = 0f,
        meanLumaRight = 0f,
    )

internal fun probeFrame(
    seq: Long,
    orange: Boolean = false,
    hint: Boolean = false,
    pillar: Boolean = false,
): ProbeFrame = ProbeFrame(seq, emptyOrange(orange), emptyHint(hint), emptyPillar(pillar))

internal fun snapshot(
    fgPkg: String = "com.phoenix.read",
    frameWidth: Int = 1280,
    frameHeight: Int = 720,
    pageText: String = "",
    hasVideoSurface: Boolean = false,
    hasPlaybackControls: Boolean = false,
): DetectionSnapshot = DetectionSnapshot(
    fgPkg = fgPkg,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
    nodeCount = 10,
    rawNodeCount = 20,
    // hasPlaybackControls 是构造时从 pageText 推导的，用一句“正剧控件”文案模拟
    pageText = if (hasPlaybackControls) "$pageText 第12集 倍速 选集" else pageText,
    hasRenderView = false,
    hasTextureView = true,
    hasAdItem = false,
    looksSparse = true,
    looksLandscapeNoSurface = false,
    hasVideoSurface = hasVideoSurface,
    hasScrollableContent = false,
    hasWebView = false,
    hasCountdown = true,
    classHistogram = "",
)
