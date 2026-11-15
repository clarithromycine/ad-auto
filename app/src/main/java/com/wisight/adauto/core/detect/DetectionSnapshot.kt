package com.wisight.adauto.core.detect

import com.wisight.adauto.core.AdRules

/**
 * 一轮检测的**特征快照** —— “这次屏幕上看到了什么”的不可变记录。
 *
 * schema 直接对应调试浮层的字段表（README「各字段含义」）：
 * `rv / tex / adItem / sparse / cd / surface / scroll / webView / landNoSurf`。
 * 判定与浮层不再各取各的中间变量，而是统一从快照读 —— **浮层看到什么，判据就吃什么**。
 *
 * 纯数据 + 纯派生：不持有状态、不碰 Android API（节点树已在外面收集好）。
 */
class DetectionSnapshot(
    val fgPkg: String,
    val frameWidth: Int,
    val frameHeight: Int,
    /** 过滤后留下的节点数（`nodes.size`） */
    val nodeCount: Int,
    /** 过滤前的可见节点总数 */
    val rawNodeCount: Int,
    /** 页面文本只拼接一次：关键字匹配与倒计时解析共用（见 AdRules.pageTextOf） */
    val pageText: String,
    val hasRenderView: Boolean,
    val hasTextureView: Boolean,
    val hasAdItem: Boolean,
    val looksSparse: Boolean,
    val looksLandscapeNoSurface: Boolean,
    val hasVideoSurface: Boolean,
    val hasScrollableContent: Boolean,
    val hasWebView: Boolean,
    val hasCountdown: Boolean,
    /** 类名直方图：只在调试浮层开启时统计，默认空串（零开销） */
    val classHistogram: String,
) {
    val textLen: Int get() = pageText.length

    val isLandscape: Boolean
        get() = frameWidth > 0 && frameHeight > 0 && frameWidth > frameHeight

    /** 正剧播放控件（第X集/倍速/选集/热评…）：出现即视为正剧，绝不动作 */
    val hasPlaybackControls: Boolean = AdRules.hasPlaybackControls(pageText)

    /** “X秒后进入广告 / 即将播放广告”前置提示：广告还没开始，正剧仍在播 */
    val hasUpcomingAd: Boolean = AdRules.hasUpcomingAd(pageText)

    /** 显式滑动锁（“5s后可继续上滑观看”）：按时间解锁，暂停视频并不能解锁 */
    val hasSwipeLock: Boolean = AdRules.hasSwipeLock(pageText)

    /** 整页读不到字（创意自绘） */
    val isContentless: Boolean get() = AdRules.isContentlessPage(pageText)

    /**
     * 横屏“模糊态”：拿不到正剧控件、也没有任何正向广告特征 —— 只有这种状态才值得截图看颜色。
     * 横屏全屏时红果会把正剧控件全部隐藏（实测正剧：43 节点 / 0 文字 / 0 可点击），
     * 节点树完全分不出广告和正剧。注意：横屏时橙色按钮是**主策略**而不是兜底。
     *
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
     * 所以最终策略：**保留角标快路径 + 橙色作主策略**。
     *
     * 注：曾有一个 `LANDSCAPE_IGNORE_RENDER_VIEW` 开关（横屏时丢弃“渲染视图”判据），
     * 2026-11-11 已移除 —— 它把 5 份正剧样本都不具备的 `ttlive_player_render_view`
     * 也一起挡掉了，改为用 `liveGate`（[PersistenceGate]）做 800ms 持久化确认。
     *
     * 另一个已停用的判据是「横屏无视频层」（[AdRules.REASON_LANDSCAPE_NO_SURFACE]）：
     * 它的四个条件全是「读不到 / 没有」型，三星 SM-S9280 / Android 15 / 红果 7.1.1.32
     * 实测**正在播正剧**时也全部满足 → 正剧被直接上滑划走（2026-11-11 停用）。
     * 教训：缺失型条件只允许用于「进入截图探测」，不允许用于「直接执行动作」；
     * 恢复时必须补**正向**锚点，而不是继续加「没有…」的闸门。
     * 信号本身仍由 [AdRules.looksLikeLandscapeAdWithoutSurface] 计算，只用于调试浮层展示。
     */
    fun landscapeAmbiguous(hasAction: Boolean): Boolean =
        !hasAction && isLandscape && fgPkg == AdRules.HONGUO_PACKAGE && !hasPlaybackControls

    /**
     * 竖屏也有一模一样的“读不到字”全屏广告（2026-11-07 用户样本：竖屏游戏试玩）。
     * 只要整页读不到字、没有正剧控件、也没命中任何规则，就值得截一张图看看底部有没有
     * 「∧ 上滑继续看短剧」提示栏。
     *
     * ⚠️ **必须要求“没有视频层”**（2026-11-08 修正）：横屏正剧实测 `surface=true textLen=0`，
     * 而底部暗场景画面 + 烧在视频帧里的居中白色字幕，像素统计与提示栏完全撞车
     * （栏高 19.8% / 黑 0.91 / 白 0.0096 / 中心 0.50），导致正剧被反复划走。
     * 正剧播放**必然**挂 SurfaceView/TextureView，提示栏只出现在“创意自绘、没有任何视频层”
     * 的页面上 —— 用这个结构差异把它彻底隔开。
     */
    fun contentlessAmbiguous(hasAction: Boolean): Boolean =
        !hasAction && fgPkg == AdRules.HONGUO_PACKAGE && isContentless &&
            !hasVideoSurface && !hasPlaybackControls

    /** `logcat` 一行“本次看到了什么”，排查用。 */
    fun scanLogLine(adSwipeStreak: Int): String =
        "scan: fg=$fgPkg nodes=$nodeCount rawNodes=$rawNodeCount " +
            "textLen=$textLen frame=${frameWidth}x$frameHeight " +
            "surface=$hasVideoSurface scroll=$hasScrollableContent " +
            "webView=$hasWebView " +
            "renderView=$hasRenderView texView=$hasTextureView adItem=$hasAdItem " +
            "sparse=$looksSparse landNoSurface=$looksLandscapeNoSurface " +
            "countdown=$hasCountdown streak=$adSwipeStreak"

    /**
     * 调试浮层正文（字段含义见 README）。
     *
     * @param lastPillarInfo 上一次信箱判据的排查描述；为空串时不显示该行
     * @param actionEnabled 自动跳过是否开启（关闭时标注“仅调试·不执行”）
     */
    fun overlayText(lastPillarInfo: String, actionEnabled: Boolean): String {
        // 顶部标注当前模式：自动跳过关闭时是“仅调试·不执行”（只看不动）
        val mode = if (actionEnabled) "" else "【仅调试·不执行】\n"
        return mode +
            "fg=$fgPkg  ${frameWidth}x$frameHeight\n" +
            "nodes=$nodeCount/$rawNodeCount textLen=$textLen\n" +
            "rv=$hasRenderView tex=$hasTextureView adItem=$hasAdItem\n" +
            "sparse=$looksSparse cd=$hasCountdown landNoSurf=$looksLandscapeNoSurface\n" +
            "surface=$hasVideoSurface scroll=$hasScrollableContent " +
            "webView=$hasWebView" +
            (if (lastPillarInfo.isEmpty()) "" else "\n柱(上次) $lastPillarInfo") +
            (if (classHistogram.isEmpty()) "" else "\ncls=$classHistogram")
    }
}
