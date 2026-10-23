package com.wisight.adauto.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class AdActionType {
    CLICK,       // 点击某个可点击节点
    SWIPE_UP,    // 上滑（如“上滑继续观看短剧”）
    SWIPE_DOWN,  // 下滑
    SWIPE_LEFT,  // 左滑
    SWIPE_RIGHT, // 右滑
    BACK,        // 返回键
}

data class AdAction(
    val type: AdActionType,
    val node: AccessibilityNodeInfo? = null,
    val reason: String = "",
    /**
     * true = 不走“倒计时等待 / 点中心暂停”流程，直接执行动作。
     * 直播样式广告整屏都是视频层、页面里没有任何文字，既读不到倒计时、也没有可用的
     * 播放按钮，而点屏幕正中有“误入直播间”的风险，所以必须绕过暂停流程直接划走。
     */
    val immediate: Boolean = false,
)

/**
 * 广告识别规则。
 *
 * 匹配策略（按优先级）：
 * 1. 页面中出现“上滑继续观看短剧”等关键字 -> 执行上滑手势
 * 2. 广告上下文中出现“跳过/关闭/知道了”等可点击文字 -> 点击
 * 3. 用户自定义关键词（同样要求广告上下文，避免误触）
 */
object AdRules {

    /** “上滑继续观看短剧”类广告关键字（全部带明确上滑方向，避免把首页“继续观看短剧”卡片误判成广告） */
    val SWIPE_UP_KEYWORDS = listOf(
        "上滑继续观看", "上滑继续", "上滑继续看短剧", "上滑继续看", "上滑看短剧",
        "向上滑动继续观看", "上滑解锁", "上滑看下一集",
    )
    /**
     * 正剧播放界面的特征控件文字：出现则判定为“正常短剧播放”，绝不执行滑动/点击。
     * （红果短剧等播放器在正常播放时会显示这些原生控件，而穿山甲 SurfaceView
     * 广告期间这些控件不出现 —— 用于保护正剧不被误伤）
     */
    val PLAYBACK_CONTROL_KEYWORDS = listOf(
        "倍速", "选集", "热评", "分享", "评论", "展开", "暂停", "下一集",
        "全集", "已完结", "作者声明", "跟播", "点赞", "收藏", "弹幕",
    )

    /** 正剧播放特征：剧集标题，如“第4集” */
    val EPISODE_REGEX = Regex("第\\d+集")

    /** 广告上下文关键字，用于降低误触概率 */
    val AD_CONTEXT_KEYWORDS = listOf("广告", "advertisement")

    /**
     * 直播样式广告的渲染视图资源 id（`com.phoenix.read:id/ttlive_player_render_view`）。
     *
     * 红果新版广告（抖音直播样式创意）整屏渲染在这个 TextureView 上，**广告文案全部画在
     * 视频层里**，无障碍树读不到任何文字 —— 上滑关键字、立即领取、广告角标、倒计时、
     * 点击规则全部失效，这就是“直播广告跳不过”的根因。
     *
     * 该渲染视图是**具名 id**（非混淆），且只有这类广告才会挂上：
     * 实测 5 份正剧样本（播放中 / 暂停 / 控件可见）全部是 `SurfaceView`、都不含此 id；
     * 4+ 份直播广告样本全部含此 id（渲染视图为 `TextureView`）。
     */
    const val AD_RENDER_VIEW_ID = "ttlive_player_render_view"

    /** “直播样式广告”的判定原因，供 AdDetector 识别该分支（直接划走 + 更长冷却） */
    const val REASON_LIVE_AD = "直播广告(渲染视图)"

    /**
     * 红果（`com.phoenix.read`）——**唯一**做了专属适配的 App。
     *
     * 下面这些**红果专属判据**（渲染视图 / 广告角标 / 商城卡片 banner / 广告 item 容器 / 稀疏树）
     * 全部只在这个包上生效。原因：它们都是针对红果的广告形态逐个取证出来的启发式，
     * 而“稀疏树”本质是**缺失型**判据（靠“节点少 + 文字少”反推广告），跨 App 通用会误伤 ——
     * 实测反馈：某读书 App 用 Canvas/SurfaceView 画正文，无障碍树同样“稀疏”，
     * 被判据兜住后一直自动划页。
     *
     * 其余**通用规则**（上滑关键字 / 跳过·关闭点击 / 倒计时）不受此限制，
     * 仍可在“通用模式”或用户自定义的支持包名里生效。
     */
    const val HONGUO_PACKAGE = "com.phoenix.read"

    /** 节点集合里是否存在直播广告的渲染视图（页面无文字可读，只能靠这个具名 id 判定）。 */
    fun hasAdRenderView(nodes: List<AccessibilityNodeInfo>): Boolean =
        nodes.any { it.viewIdResourceName?.endsWith("/$AD_RENDER_VIEW_ID") == true }

    /**
     * 广告创意渲染视图的**类名**。
     *
     * 2026-10-23 实测（台球试玩/游戏落地广告）：创意改用**没有 resource-id 的 `TextureView`**
     * 挂载，整屏游戏 UI / 按钮 / “上滑继续看短剧”提示全画在 surface 上 → 无障碍树里
     * **0 个文字节点、只有 1 个可点击节点（左上返回）**，`countdown=false`，
     * 上面所有按文字/具名 id 的规则全部 miss（这就是“又一次跳不过”的根因）。
     * 同一时段的正剧对照：用的是 `android.view.SurfaceView`（[0,0][1200,2360]），
     * **全程没有 TextureView**（与 2026-09 的结论一致：正剧走 SurfaceView）。
     * 因此判据从“具名 id”泛化为“类名”：只要出现 TextureView 就当作广告渲染视图。
     */
    const val AD_RENDER_VIEW_CLASS = "TextureView"

    /** “广告创意渲染视图（无 id 的 TextureView）”的判定原因 */
    const val REASON_AD_SURFACE = "广告(渲染视图TextureView)"

    /** 节点集合里是否存在 TextureView 类型的渲染视图（广告创意画的 surface）。 */
    fun hasAdRenderSurface(nodes: List<AccessibilityNodeInfo>): Boolean =
        nodes.any { it.className?.toString()?.contains(AD_RENDER_VIEW_CLASS) == true }

    /**
     * 红果「广告 feed item」的布局容器 id（`ay0` + `i0t`）。
     *
     * 实测（同一时期同一版本）三种广告形态的无障碍树里**都同时包含**这两个 id：
     * - 2026-10-14 Pangle 图文/商城卡片（肯德基疯狂星期四，图集 0/4）
     * - 2026-10-23 台球王者试玩广告（创意画在无 id 的 TextureView 上）
     * - 2026-10-23 iQOO 电商卡片广告（创意是图片，文字只剩“叮～送你/7/折优惠券/7折”碎碎片）
     * 而正剧 dump（84 个 id）里**两个都没有** → 说明它们是广告 item 的容器（正剧时为 GONE）。
     * 这条能盖住“创意全画在图片里、无障碍树几乎无字”的广告——这类前面的所有规则都认不出。
     *
     * ⚠️ 这两个是混淆后的短名，红果升级可能改名；失效时只会“漏跳”（安全方向），
     * 届时按新样本重新取证即可。
     */
    private val AD_ITEM_CONTAINER_IDS = listOf("ay0", "i0t")

    /** “广告 feed item 容器”的判定原因 */
    const val REASON_AD_ITEM = "广告(广告布局容器)"

    /** 节点集合里是否同时存在广告 item 的两个容器 id（单个出现不算）。 */
    fun hasAdItemContainer(nodes: List<AccessibilityNodeInfo>): Boolean =
        AD_ITEM_CONTAINER_IDS.all { id ->
            nodes.any { it.viewIdResourceName?.endsWith("/$id") == true }
        }

    /** “稀疏树广告”兜底判据的上限（实测：正剧 nodes 88~90 / textLen 108~124；广告 nodes 20~24 / textLen 0~15） */
    private const val SPARSE_AD_MAX_NODES = 40
    private const val SPARSE_AD_MAX_TEXT_LEN = 30

    /** “稀疏树广告”的判定原因 */
    const val REASON_SPARSE_AD = "广告(稀疏树)"

    /**
     * **名称无关**的“广告 item”兜底判据（2026-10-23 用户提出：节点/id 数差异明显）。
     *
     * 条件（全部满足）：
     * - **仅竖屏**：[screenW] < [screenH]。横屏正剧实测 `nodes=22 textLen=0`，与广告特征完全撞车；
     * - 收集节点数 ≤ [SPARSE_AD_MAX_NODES]（正剧 88~90；广告实测 20~24）；
     * - 文字总量 ≤ [SPARSE_AD_MAX_TEXT_LEN] 字符（正剧 108~124；广告 0~15）；
     * - 没有正剧播放控件（[hasPlaybackControls]）。
     *
     * ⚠️ 这是“缺失型”判据：**广告 → 正剧的过渡帧**（实测 `nodes=26~28 textLen=0`，持续约 400ms）
     * 同样满足，所以调用方必须做**持久性确认**：AdDetector 里连续观测满
     * `AD_ITEM_CONFIRM_MS`（800ms）才动手，且动作后清零重新确认。
     */
    fun looksLikeSparseAdItem(
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        screenW: Int,
        screenH: Int,
    ): Boolean {
        if (screenW <= 0 || screenH <= 0 || screenW >= screenH) return false
        if (nodes.size > SPARSE_AD_MAX_NODES) return false
        if (pageText.length > SPARSE_AD_MAX_TEXT_LEN) return false
        return !hasPlaybackControls(pageText)
    }

    /** “穿山甲图文/商城卡片广告”的判定原因（命名带“穿山甲广告” → 与其它穿山甲规则共享 6000ms 长冷却） */
    const val REASON_SHOP_CARD_AD = "穿山甲广告(商城卡片)"

    /** 商城卡片的固定 banner 文案（实测样本：`desc="商城好物省心选"`，位于卡片底部）。 */
    private const val SHOP_CARD_BANNER = "好物省心选"

    /** “商城卡片广告”允许的最大文字节点数：正剧/内容页/商城页的文字远多于此。 */
    private const val MAX_SHOP_CARD_TEXT_NODES = 6

    /**
     * 广告倒计时关键字（“3秒后可继续”“5s后继续观看”等）。
     * 这类文案本身即是广告的强信号，且倒计时结束后常出现可点击的“继续观看”按钮。
     */
    /**
     * 显式“滑动锁”文案（红果横屏全屏广告：“5s后可继续上滑观看”）。
     *
     * 锁期内滑动一律无效，必须当倒计时处理（等锁解除再划）。只收“后可…上滑”这种带“后”
     * 的形式：锁解除后的“可继续上滑观看”不能再当倒计时，否则会永远划不走。
     */
    private val SWIPE_LOCK_KEYWORDS = listOf("后可继续上滑", "后可上滑", "秒后可上滑", "s后可上滑")

    /** 页面是否处于“滑动锁”状态（如“5s后可继续上滑观看”）。 */
    fun hasSwipeLock(pageText: String): Boolean {
        val compactText = pageText.replace(Regex("\\s+"), "")
        return SWIPE_LOCK_KEYWORDS.any { pageText.contains(it) || compactText.contains(it) }
    }

    val COUNTDOWN_KEYWORDS = listOf(
        "秒后可继续", "s后可继续", "S后可继续",
        "秒后继续", "s后继续",
        "后可继续", "后继续观看", "后继续播放", "后可观看",
        "倒计时", "countdown", "CountDown",
    ) + SWIPE_LOCK_KEYWORDS

    /** 倒计时正则：匹配 “3秒后”“5s后可继续”“广告倒计时 3” 等 */
    val COUNTDOWN_REGEX = Regex("\\d+\\s*(?:秒|s|S)\\s*(?:后|后可继续|后继续|后可观看|后观看|后播放)?")

    /**
     * 倒计时数字提取正则（用于精确安排“倒计时结束”后的重扫时机）：
     * - “3秒后可继续”“5s后” → (\\d+)\\s*(?:秒|s|S)
     * - “广告倒计时 3”“广告 3s” → (?:广告|倒计时)\\s*(\\d+)(?:\\s*(?:秒|s|S))?
     */
    private val COUNTDOWN_NUMBER_REGEX = Regex(
        "(?:广告|倒计时)\\s*(\\d+)(?:\\s*(?:秒|s|S))?|(\\d+)\\s*(?:秒|s|S)"
    )

    /** 拼接页面文本（所有节点的 text + contentDescription），供匹配与倒计时解析共用 */
    fun pageTextOf(nodes: List<AccessibilityNodeInfo>): String = buildString {
        for (n in nodes) {
            n.text?.toString()?.let { append(it).append(' ') }
            n.contentDescription?.toString()?.let { append(it).append(' ') }
        }
    }

    /**
     * 解析页面倒计时剩余秒数（取所有匹配中的最小值，即最接近结束的那个）。
     * 解析不到（或没有正在进行的倒计时）返回 null。
     * 传入已拼接好的页面文本，避免重复遍历节点树（主线程开销）。
     */
    fun remainingCountdownSeconds(pageText: String): Int? {
        val compactText = pageText.replace(Regex("\\s+"), "")
        var min: Int? = null
        for (text in arrayOf(pageText, compactText)) {
            for (m in COUNTDOWN_NUMBER_REGEX.findAll(text)) {
                val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull() ?: continue
                if (v > 0 && (min == null || v < min)) min = v
            }
        }
        return min
    }

    /** 页面是否出现广告倒计时文案（如 “5秒后可继续”、“3s”、“倒计时”）。 */
    fun hasCountdown(pageText: String): Boolean {
        val compactText = pageText.replace(Regex("\\s+"), "")
        return COUNTDOWN_KEYWORDS.any {
            pageText.contains(it, ignoreCase = true) || compactText.contains(it, ignoreCase = true)
        } || pageText.contains(COUNTDOWN_REGEX) || compactText.contains(COUNTDOWN_REGEX)
    }

    /** 页面是否出现广告上下文（“广告”字样，或广告倒计时）。 */
    fun hasAdContext(pageText: String): Boolean {
        val compactText = pageText.replace(Regex("\\s+"), "")
        return AD_CONTEXT_KEYWORDS.any {
            pageText.contains(it, ignoreCase = true) || compactText.contains(it, ignoreCase = true)
        } || hasCountdown(pageText)
    }

    /**
     * “即将进入广告”的前置提示关键词：广告还没开始，正剧仍在播放。
     * 例：“5秒后进入广告”、“即将播放广告”、“3秒后播放广告”。
     * 这类文案会同时带倒计时数字（会被 hasCountdown 命中），但此时**不能**点中心暂停——
     * 暂停会把正在播放的正剧停掉。
     */
    private val UPCOMING_AD_KEYWORDS = listOf(
        "进入广告", "播放广告", "广告即将", "即将播放广告", "即将进入广告",
        "后进入广告", "后播放广告", "开始播放广告", "即将开始播放", "即将开始广告",
    )

    /** 页面是否为“即将进入广告”的前置提示（广告尚未开始，正剧仍在播放）。 */
    fun hasUpcomingAd(pageText: String): Boolean {
        val compactText = pageText.replace(Regex("\\s+"), "")
        return UPCOMING_AD_KEYWORDS.any {
            pageText.contains(it) || compactText.contains(it)
        }
    }

    /**
     * 正剧播放保护：页面是否出现正常短剧播放器特征（倍速/选集/第X集/弹幕等）。
     * 出现这些控件说明当前是正剧而非广告，任何滑动/点击（含点中心暂停）都不应执行。
     */
    fun hasPlaybackControls(pageText: String): Boolean {
        val compactText = pageText.replace(Regex("\\s+"), "")
        return PLAYBACK_CONTROL_KEYWORDS.any {
            pageText.contains(it) || compactText.contains(it)
        } || compactText.contains(EPISODE_REGEX)
    }

    /** 点击类规则，按顺序匹配 */
    val CLICK_RULES = listOf(
        ClickRule(
            name = "跳过广告",
            // 明确的“跳过广告/跳过此广告”是强广告信号，无需广告上下文。
            // 注意：该规则排在“跳过”之前，命中时优先匹配（“跳过广告”文字也包含“跳过”）。
            texts = listOf("跳过广告", "跳过此广告", "跳過廣告", "Skip Ad", "SkipAd"),
            requireAdContext = false,
        ),
        ClickRule(
            name = "跳过",
            // 单独的“跳过/跳過/Skip”太通用（很多界面的引导/弹窗都有），
            // 必须同时存在广告上下文（“广告”字样或倒计时）才点击，避免误触。
            texts = listOf("跳过", "跳過", "skip", "Skip"),
            requireAdContext = true,
        ),
        ClickRule(
            name = "关闭",
            texts = listOf("关闭广告", "关闭", "×", "✕"),
            requireAdContext = true,
        ),
        ClickRule(
            name = "知道了",
            texts = listOf("知道了", "确定"),
            requireAdContext = true,
        ),
        ClickRule(
            name = "继续(倒计时结束)",
            // 仅匹配明确的“继续观看/继续播放/立即观看/立即播放”按钮文字。
            // 裸词“继续”太通用（推文/设置/弹窗里到处都是），配合误判的倒计时上下文
            // （如“9 Share”“1s”时间戳）会把普通内容当广告点开。
            texts = listOf("继续观看", "继续播放", "立即观看", "立即播放"),
            requireCountdown = true,
        ),
    )

    data class ClickRule(
        val name: String,
        val texts: List<String>,
        val requireAdContext: Boolean = true,
        /** 仅当页面出现广告倒计时（如“3秒后可继续”）时才匹配 */
        val requireCountdown: Boolean = false,
    )

    /** 读取用户自定义关键词（支持中英文逗号/顿号/分号分隔） */
    fun customClickTexts(): List<String> {
        val raw = SettingsManager.customKeywords
        if (raw.isBlank()) return emptyList()
        return raw.split(',', '，', '、', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 在页面节点集合中查找广告并决定执行的动作。
     * 返回 null 表示当前界面未检测到广告。
     *
     * @param screenW/screenH 当前屏幕尺寸（像素）。用于对“广告”角标这类位置敏感的
     *        特征做位置过滤；为 0 时跳过位置过滤（向后兼容直接调用）。
     * @param pkg 当前前台包名。红果专属判据只对 [HONGUO_PACKAGE] 生效；
     *        传空（或不匹配）时这些判据全部跳过，只保留通用规则。
     */
    fun match(
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        screenW: Int = 0,
        screenH: Int = 0,
        pkg: String = "",
    ): AdAction? {
        // 去掉空白后做匹配，兼容“上滑 继续看短剧”这类带空格写法
        val compactText = pageText.replace(Regex("\\s+"), "")
        // 红果专属判据闸门（见 HONGUO_PACKAGE 注释）
        val isHonguo = pkg == HONGUO_PACKAGE

        // 1) “上滑继续观看短剧”类广告 -> 上滑。
        //    这类提示词带明确方向（上滑），是硬性广告信号，正常播放时绝不会出现，
        //    因此放在“正剧保护”之前判定——避免广告文案里偶然出现的“展开”等正剧词
        //    把显式的上滑提示拦截掉（2026-08-17 红果房产视频广告误拦截“上滑继续观看短剧”）。
        if (SWIPE_UP_KEYWORDS.any { pageText.contains(it) || compactText.contains(it) }) {
            // 附带匹配到的节点，便于日志输出提示词精确坐标
            val matched = nodes.firstOrNull { n ->
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                SWIPE_UP_KEYWORDS.any { kw -> t.contains(kw) || d.contains(kw) }
            }
            return AdAction(AdActionType.SWIPE_UP, matched, reason = "上滑继续观看")
        }

        // 0) 正剧播放保护：出现播放器控件特征（第X集/倍速/选集/热评等）
        //    说明当前是正常短剧播放，绝不执行滑动/点击，避免误伤正剧内容。
        //    （显式上滑提示已在上面优先处理；此保护仅守护后面的“立即领取”启发式
        //    与点击规则，防止正常播放时误伤）
        if (hasPlaybackControls(pageText)) return null

        // 1.7) 直播样式广告（红果新版）：
        //     整屏广告创意画在视频层（TextureView `ttlive_player_render_view`）上，
        //     无障碍树里**一个文字节点都没有** → 上面的上滑关键字 / 立即领取 / 广告角标 /
        //     点击规则全部失效（这就是“直播广告跳不过”的根因）。
        //     该渲染视图是具名 id 且广告独有（正剧始终走 SurfaceView），据此判定为广告 → 上滑。
        //     位置放在正剧保护之后：万一将来正剧也复用它，正剧保护仍然优先。
        if (isHonguo && hasAdRenderView(nodes)) {
            val renderView = nodes.firstOrNull {
                it.viewIdResourceName?.endsWith("/$AD_RENDER_VIEW_ID") == true
            }
            return AdAction(
                AdActionType.SWIPE_UP,
                renderView,
                reason = REASON_LIVE_AD,
                immediate = true,
            )
        }

        // 1.71) 无 id 的 TextureView 渲染视图（2026-10-23 新形态：台球试玩/游戏落地广告）：
        //    创意（游戏 UI、按钮、“上滑继续看短剧”提示）全画在 TextureView 上 → 树里 0 文字、
        //    无可点节点、无角标/倒计时/具名 id，且上一条（具名 id）认不出来。
        //    实测：正剧始终走 android.view.SurfaceView、不会出现 TextureView；
        //    且本分支位于正剧保护（hasPlaybackControls）之后，双重保险。
        if (isHonguo && hasAdRenderSurface(nodes)) {
            val surface = nodes.firstOrNull {
                it.className?.toString()?.contains(AD_RENDER_VIEW_CLASS) == true
            }
            return AdAction(
                AdActionType.SWIPE_UP,
                surface,
                reason = REASON_AD_SURFACE,
                immediate = true,
            )
        }

        // 1.72) 红果「广告 feed item」容器（2026-10-23）：
        //   有些广告创意整张画在图片/位图里，无障碍树几乎无字 —— 实测 iQOO 电商卡片广告：
        //   `nodes=24 textLen=15`，文字只剩优惠券动画的碎片（“叮～送你 / 7 / 折优惠券 / 7折”），
        //   无“广告”角标/倒计时/渲染视图/banner → 上面所有规则全部漏掉。
        //   而这类广告的无障碍树里稳定含有 `id/ay0` + `id/i0t` 两个广告 item 容器
        //   （实测 3 种广告形态都有、正剧 84 个 id 里都没有）。
        //   护栏：位于正剧保护之后；且两个 id 必须同时出现。
        if (isHonguo && hasAdItemContainer(nodes)) {
            return AdAction(
                AdActionType.SWIPE_UP,
                null,
                reason = REASON_AD_ITEM,
                immediate = true,
            )
        }

        // 1.5) 穿山甲 SurfaceView 视频广告（红果短剧等）：
        // 广告提示词画在视频 Surface 上，无障碍树读不到任何文字（关键字匹配失效）。
        // 但广告上屏时会出现原生“立即领取”按钮，且此时没有任何正剧播放控件
        // （上面的 hasPlaybackControls 已提前拦截正剧）。据此判定为广告 -> 直接上滑。
        val claimCta = nodes.firstOrNull { n ->
            n.packageName?.toString() == "com.phoenix.read" &&
                (n.text?.toString().orEmpty().contains("立即领取") ||
                    n.contentDescription?.toString().orEmpty().contains("立即领取")) &&
                (n.isClickable || findClickable(n) != null)
        }
        if (claimCta != null) {
            return AdAction(AdActionType.SWIPE_UP, claimCta, reason = "穿山甲广告(立即领取)")
        }

        // 1.55) 穿山甲「图文 / 商城卡片」广告（红果新版，2026-10-14）：
        //   整屏广告创意（图集/商品卡）+ 卡片，无障碍树里**只有两个文字节点**：
        //     · `desc="商城好物省心选"`（商城卡片固定 banner）
        //     · `text="领取"`（右边缘常驻 CTA，**正剧也有，不能当判据**）
        //   底部的“上滑继续看短剧”提示画在视频层上、**读不到**（与“家博会广告”那条原生
        //   `rid=apt` 的提示不同），所以上滑关键字规则永不命中；也没有“广告”角标/倒计时/
        //   渲染视图 → 上面所有规则全部漏掉（这就是“又跳不过了”的根因）。
        //   实测（2026-10-14）该广告确实响应上滑 → 判定为广告并直接上滑划走。
        if (isHonguo && isShopCardAd(nodes, pageText)) {
            val banner = nodes.firstOrNull {
                !it.contentDescription.isNullOrEmpty() &&
                    it.contentDescription.toString().contains(SHOP_CARD_BANNER)
            }
            return AdAction(
                AdActionType.SWIPE_UP,
                banner,
                reason = REASON_SHOP_CARD_AD,
                immediate = true,
            )
        }

        // 1.6) Pangle 全屏广告的“广告”角标（竖屏/横屏统一）：
        // 全屏视频广告的提示文字（“上滑继续观看”等）画在视频 Surface 上，无障碍树读不到，
        // 但右上角的“广告”角标是原生节点、能稳定读到，横竖屏都会出现。
        // 只要页面上没有倒计时（倒计时走“点中心暂停”流程）、不是“X秒后进入广告”前置提示
        // （正剧仍在播），且没有正剧播放控件（上方 hasPlaybackControls 已拦截），
        // 就判定为广告并直接上滑划走。
        if (isHonguo && !hasCountdown(pageText) && !hasUpcomingAd(pageText)) {
            val badge = findAdBadgeNode(nodes, screenW, screenH)
            if (badge != null) {
                return AdAction(AdActionType.SWIPE_UP, badge, reason = "穿山甲广告(角标)")
            }
        }

        // 广告上下文：出现“广告”字样，或倒计时（如 “5秒后可继续”、“3s”)
        val countdownFound = hasCountdown(pageText)
        val adContextFound = hasAdContext(pageText)

        // 2) 内置点击规则
        for (rule in CLICK_RULES) {
            if (rule.requireAdContext && !adContextFound) continue
            if (rule.requireCountdown && !countdownFound) continue
            for (node in nodes) {
                if (!node.matchesAny(rule.texts)) continue
                val clickable = findClickable(node) ?: continue
                return AdAction(AdActionType.CLICK, clickable, reason = rule.name)
            }
        }

        // 3) 用户自定义关键词
        val custom = customClickTexts()
        if (custom.isNotEmpty() && adContextFound) {
            for (node in nodes) {
                if (!node.matchesAny(custom)) continue
                val clickable = findClickable(node) ?: continue
                val hit = custom.firstOrNull { node.matches(it) } ?: continue
                return AdAction(AdActionType.CLICK, clickable, reason = "自定义:$hit")
            }
        }

        // 3.5) 名称无关兜底：稀疏树（放在所有正向规则之后，让精确判据优先）
        //     覆盖“创意整张画在位图/表面里、无障碍树几乎没有内容”的新广告，
        //     不依赖任何具体的 id 名或创意文案。安全性见 looksLikeSparseAdItem 注释。
        //     ⚠️ 仅对红果生效：这是缺失型判据，用在别的 App 上会把“Canvas/Surface 画正文”
        //     的页面（如读书软件）当成广告一直划（实测反馈）。
        if (isHonguo && looksLikeSparseAdItem(nodes, pageText, screenW, screenH)) {
            return AdAction(
                AdActionType.SWIPE_UP,
                null,
                reason = REASON_SPARSE_AD,
                immediate = true,
            )
        }

        return null
    }

    /**
     * 页面是否为穿山甲「图文 / 商城卡片」广告（2026-10-14 实测形态）：
     * - 页面文字出现商城卡片固定 banner（“好物省心选”）；
     * - 且页面文字节点极少（≤ [MAX_SHOP_CARD_TEXT_NODES]）—— 正剧/内容页/商城页的文字远多于此；
     * - 正剧播放由调用方（match 里的 hasPlaybackControls）在此之前拦掉。
     *
     * 实测依据（2026-10-14）：广告卡住态 `nodes=23 textLen=11`，整棵树只有两个文字节点——
     * `desc="商城好物省心选"` 与 `text="领取"`；命中本条件后可立即上滑（实测上滑能划走）。
     *
     * ⚠️ **为什么不用“右边缘的『领取』CTA”作条件（实测被推翻）**：
     * 正剧（第25集播放中）状态下同样存在一个 `text="领取"`、bounds `[1137,901][1179,989]`、
     * 祖先为 `ViewGroup [CLICKABLE] [1068,819][1200,1071]` 的节点，**与广告里的完全一致**
     * （且服务树 textLen=126 证明它确实被收集）—— 说明“右边缘领取”是常驻元素（红果自己的
     * 福利入口 / SDK 常驻 widget），不是广告专属特征：单独用它会在正剧上误判。只用 banner。
     */
    private fun isShopCardAd(
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
    ): Boolean {
        val textNodes = nodes.count {
            !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty()
        }
        if (textNodes == 0 || textNodes > MAX_SHOP_CARD_TEXT_NODES) return false
        val compactText = pageText.replace(Regex("\\s+"), "")
        return compactText.contains(SHOP_CARD_BANNER)
    }

    /**
     * 查找 Pangle 全屏广告的“广告”角标节点：文字或内容描述为“广告”，且位于屏幕**右上角**。
     * - Pangle 全屏广告的 disclosure 角标统一放在屏幕右上角，竖屏/横屏一致；
     * - 只按相对坐标判定（右上角区域），不依赖具体方向，横竖屏通用；
     * - 首页/信息流没有这种右上角“广告”角标，不会误判。
     */
    private fun findAdBadgeNode(
        nodes: List<AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
    ): AccessibilityNodeInfo? {
        if (screenW <= 0 || screenH <= 0) return null
        // 右上角区域：顶部 ~28% 高度、右部 ~40% 宽度（Pangle 角标固定右上角）
        val topLimit = screenH * 0.28f
        val rightLimit = screenW * 0.60f
        for (n in nodes) {
            val t = n.text?.toString().orEmpty().trim()
            val d = n.contentDescription?.toString().orEmpty().trim()
            if (t != "广告" && d != "广告") continue
            // 角标本身不可点（可点的是广告容器/按钮），进一步降低误判
            if (n.isClickable) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.isEmpty) continue
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            if (cy > topLimit || cx < rightLimit) continue
            return n
        }
        return null
    }

    /** 找到自身或其祖先中第一个可点击的节点 */
    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.matchesAny(texts: List<String>): Boolean =
        texts.any { matches(it) }

    private fun AccessibilityNodeInfo.matches(keyword: String): Boolean {
        val nodeText = text?.toString().orEmpty()
        val nodeDesc = contentDescription?.toString().orEmpty()
        return nodeText.contains(keyword) || nodeDesc.contains(keyword)
    }
}
