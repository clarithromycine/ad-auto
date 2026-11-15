package com.wisight.adauto.core.detect

import com.wisight.adauto.core.AdRules
import com.wisight.adauto.core.vision.HintBarEvidence
import com.wisight.adauto.core.vision.OrangeEvidence
import com.wisight.adauto.core.vision.PillarboxEvidence

/** 最近一次截图探测的原始结论（一张截图 = 一个 frame）。 */
class ProbeFrame(
    /** 测量序号：同一张图不重复计命中、也不重复刷日志 */
    val seq: Long,
    val orange: OrangeEvidence,
    val hint: HintBarEvidence,
    val pillar: PillarboxEvidence,
)

/** 截图探测的两条“分不出来”的入口条件。 */
class Ambiguity(val landscape: Boolean, val contentless: Boolean) {
    val any: Boolean get() = landscape || contentless
}

/**
 * 一条截图判据 = 一行数据：**什么时候参与**（gate）+ **这张图看到了没**（observed）
 * + 命中阈值 + 结论。三条判据结构完全同构，差异只在 gate / observed / reason。
 *
 * 阈值出处见各自的 `core/vision` 下各自的文件（它们是同一批真机样本标定出来的）。
 */
class ScreenshotCriterion(
    /** 命中后写进 [com.wisight.adauto.core.AdAction.reason] */
    val reason: String,
    /** 日志前缀，如 "信箱判据" / "横屏主策略" / "底部提示栏" */
    val logLabel: String,
    /** 调试浮层里的一行前缀，如 "柱" / "橙" / "栏" */
    val tracePrefix: String,
    /** 需要几张**不同**截图都命中（同一张图重复命中不算） */
    val hitsRequired: Int,
    private val gateOf: (Ambiguity) -> Boolean,
    private val observedOf: (ProbeFrame) -> Boolean,
    private val infoOf: (ProbeFrame) -> String,
    /**
     * 前面的判据已经把结论定下来时，本判据是否跳过。
     * 例：信箱（结构判据）命中后，不允许颜色判据改判。
     */
    private val skipWhenResolved: Boolean = true,
    /**
     * 跳过时是否清零计数。
     * 提示栏是**不清零**的（历史行为：`else if (!contentlessAmbiguous)` 才清零），
     * 改动会改变确认节奏，所以显式保留。
     */
    private val resetWhenSkipped: Boolean = true,
) {
    fun gate(ambiguity: Ambiguity): Boolean = gateOf(ambiguity)
    fun observed(probe: ProbeFrame): Boolean = observedOf(probe)
    fun info(probe: ProbeFrame): String = infoOf(probe)
    fun skipsWhenResolved(): Boolean = skipWhenResolved
    fun resetsWhenSkipped(): Boolean = resetWhenSkipped
}

/** 一条判据的运行时计数（“两张不同截图”确认）。 */
class CriterionTally {
    var count = 0
    var lastCountedSeq = 0L
    var lastLoggedSeq = 0L
}

/** 需要打的一行日志（调用方决定落到 logcat 还是调试浮层）。 */
class CriterionLog(
    val logLabel: String,
    val tracePrefix: String,
    val info: String,
    val count: Int,
    val required: Int,
)

class CriterionEvaluation(
    val logs: List<CriterionLog>,
    /** 命中并应生成动作的判据（null = 没有判据达到阈值） */
    val fired: ScreenshotCriterion?,
    /** 已有一张图命中、需要再取一张确认 */
    val needConfirm: Boolean,
)

/**
 * 截图判据表 + 裁决器。
 *
 * 顺序即优先级：**信箱（结构）→ 橙色（颜色）→ 提示栏**。命中一条后不再改判。
 */
class ScreenshotArbiter(
    private val criteria: List<ScreenshotCriterion> = defaultCriteria(),
) {
    private val tallies = Array(criteria.size) { CriterionTally() }

    /** 外层条件不满足（不在模糊态 / 动作冷却中）时清零所有计数。 */
    fun resetCounts() {
        for (t in tallies) t.count = 0
    }

    /**
     * 只清零某一条判据的计数。
     *
     * 换窗口时历史实现只作废**橙色**计数（`orangeHitCount = 0`），提示栏/信箱计数是跨窗口
     * 保留的 —— 这个不对称会影响“新页面第一张截图能否直接触发”，所以显式保留，不顺手统一。
     */
    fun resetCountFor(reason: String) {
        val i = criteria.indexOfFirst { it.reason == reason }
        if (i >= 0) tallies[i].count = 0
    }

    fun evaluate(probe: ProbeFrame, ambiguity: Ambiguity): CriterionEvaluation {
        var resolved = false
        var fired: ScreenshotCriterion? = null
        val logs = ArrayList<CriterionLog>(criteria.size)
        for (i in criteria.indices) {
            val c = criteria[i]
            val t = tallies[i]
            if (!c.gate(ambiguity)) {
                t.count = 0
                continue
            }
            if (resolved && c.skipsWhenResolved()) {
                if (c.resetsWhenSkipped()) t.count = 0
                continue
            }
            val observed = c.observed(probe)
            if (observed && probe.seq != t.lastCountedSeq) {
                t.lastCountedSeq = probe.seq
                t.count++
            } else if (!observed) {
                t.count = 0
            }
            // 同一张截图只记一行日志（content change 每秒可来十几次）
            if (probe.seq != t.lastLoggedSeq) {
                t.lastLoggedSeq = probe.seq
                logs.add(CriterionLog(c.logLabel, c.tracePrefix, c.info(probe), t.count, c.hitsRequired))
            }
            if (t.count >= c.hitsRequired) {
                t.count = 0
                fired = c
                resolved = true
            }
        }
        val needConfirm = !resolved &&
            criteria.indices.any { criteria[it].gate(ambiguity) && criteria[it].observed(probe) }
        return CriterionEvaluation(logs, fired, needConfirm)
    }

    companion object {
        /** 与橙色/提示栏一致：需要两张不同截图都命中 */
        private const val HITS_REQUIRED = 2

        fun defaultCriteria(): List<ScreenshotCriterion> = listOf(
            // 结构判据，比颜色可靠，所以排在橙色之前；命中后不再允许颜色判据改判。
            // 现场样本（2026-11-09 小米 M656BA / 3200x2136 / 红果 7.4.3.32）：
            // 横屏里放竖屏直播电商创意，左右是同一帧的模糊放大填充，
            // 调试浮层 `textLen=0 tex=true surface=true scroll=true` → 四条结构判据全灭。
            ScreenshotCriterion(
                reason = AdRules.REASON_PILLARBOX_AD,
                logLabel = "信箱判据",
                tracePrefix = "柱",
                hitsRequired = HITS_REQUIRED,
                gateOf = { it.landscape },
                observedOf = { it.pillar.hit },
                infoOf = { it.pillar.describe() },
            ),
            // 只用于横屏策略，竖屏不用它，避免误判。
            ScreenshotCriterion(
                reason = AdRules.REASON_ORANGE_AD,
                logLabel = "横屏主策略",
                tracePrefix = "橙",
                hitsRequired = HITS_REQUIRED,
                gateOf = { it.landscape },
                observedOf = { it.orange.hit },
                infoOf = { it.orange.describe() },
            ),
            // 横竖屏通用：整页读不到字时看底部有没有「∧ 上滑继续看短剧」提示栏。
            // 注意 resetWhenSkipped = false：历史上“已被前面判据定下结论”时不清零提示栏计数。
            ScreenshotCriterion(
                reason = AdRules.REASON_HINT_BAR_AD,
                logLabel = "底部提示栏",
                tracePrefix = "栏",
                hitsRequired = HITS_REQUIRED,
                gateOf = { it.contentless },
                observedOf = { it.hint.hit },
                infoOf = { it.hint.describe() },
                resetWhenSkipped = false,
            ),
        )
    }
}
