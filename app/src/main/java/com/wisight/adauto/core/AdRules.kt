package com.wisight.adauto.core

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

    /** “上滑继续观看短剧”类广告关键字 */
    val SWIPE_UP_KEYWORDS = listOf(
        "上滑继续观看", "上滑继续", "上滑继续看短剧", "上滑继续看", "上滑看短剧",
        "向上滑动继续观看", "继续观看短剧", "上滑解锁", "上滑看下一集",
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
     * 广告倒计时关键字（“3秒后可继续”“5s后继续观看”等）。
     * 这类文案本身即是广告的强信号，且倒计时结束后常出现可点击的“继续观看”按钮。
     */
    val COUNTDOWN_KEYWORDS = listOf(
        "秒后可继续", "s后可继续", "S后可继续",
        "秒后继续", "s后继续",
        "后可继续", "后继续观看", "后继续播放", "后可观看",
        "倒计时", "countdown", "CountDown",
    )

    /** 倒计时正则：匹配 “3秒后”“5s后可继续”“广告倒计时 3” 等 */
    val COUNTDOWN_REGEX = Regex("\\d+\\s*(?:秒|s|S)\\s*(?:后|后可继续|后继续|后可观看|后观看|后播放)?")

    /** 点击类规则，按顺序匹配 */
    val CLICK_RULES = listOf(
        ClickRule(
            name = "跳过",
            texts = listOf("跳过广告", "跳过此广告", "跳过", "跳過", "skip", "Skip"),
            requireAdContext = false,
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
            texts = listOf("继续观看", "继续播放", "立即观看", "立即播放", "继续"),
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
     */
    fun match(nodes: List<AccessibilityNodeInfo>): AdAction? {
        val pageText = buildString {
            for (n in nodes) {
                n.text?.toString()?.let { append(it).append(' ') }
                n.contentDescription?.toString()?.let { append(it).append(' ') }
            }
        }
        // 去掉空白后做匹配，兼容“上滑 继续看短剧”这类带空格写法
        val compactText = pageText.replace(Regex("\\s+"), "")

        // 0) 正剧播放保护：出现播放器控件特征（第X集/倍速/选集/热评等）
        //    说明当前是正常短剧播放，绝不执行滑动/点击，避免误伤正剧内容。
        val hasPlaybackControls = PLAYBACK_CONTROL_KEYWORDS.any {
            pageText.contains(it) || compactText.contains(it)
        } || compactText.contains(EPISODE_REGEX)
        if (hasPlaybackControls) return null

        // 1) “上滑继续观看短剧”类广告 -> 上滑
        if (SWIPE_UP_KEYWORDS.any { pageText.contains(it) || compactText.contains(it) }) {
            // 附带匹配到的节点，便于日志输出提示词精确坐标
            val matched = nodes.firstOrNull { n ->
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                SWIPE_UP_KEYWORDS.any { kw -> t.contains(kw) || d.contains(kw) }
            }
            return AdAction(AdActionType.SWIPE_UP, matched, reason = "上滑继续观看")
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

        // 广告上下文：出现“广告”字样，或倒计时（如 “5秒后可继续”、“3s”)
        val hasCountdown = COUNTDOWN_KEYWORDS.any {
            pageText.contains(it, ignoreCase = true) || compactText.contains(it, ignoreCase = true)
        } || pageText.contains(COUNTDOWN_REGEX) || compactText.contains(COUNTDOWN_REGEX)
        val hasAdContext = AD_CONTEXT_KEYWORDS.any {
            pageText.contains(it, ignoreCase = true) || compactText.contains(it, ignoreCase = true)
        } || hasCountdown

        // 2) 内置点击规则
        for (rule in CLICK_RULES) {
            if (rule.requireAdContext && !hasAdContext) continue
            if (rule.requireCountdown && !hasCountdown) continue
            for (node in nodes) {
                if (!node.matchesAny(rule.texts)) continue
                val clickable = findClickable(node) ?: continue
                return AdAction(AdActionType.CLICK, clickable, reason = rule.name)
            }
        }

        // 3) 用户自定义关键词
        val custom = customClickTexts()
        if (custom.isNotEmpty() && hasAdContext) {
            for (node in nodes) {
                if (!node.matchesAny(custom)) continue
                val clickable = findClickable(node) ?: continue
                val hit = custom.firstOrNull { node.matches(it) } ?: continue
                return AdAction(AdActionType.CLICK, clickable, reason = "自定义:$hit")
            }
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
