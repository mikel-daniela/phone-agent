package com.pocketagent.voicelogic

/**
 * 语音链路的延迟分段 —— 架构文档 §4.5.2 的四段预算。
 *
 * ```
 * 用户说完 ──300ms──> 判停 ──200ms──> ASR 首字 ──200ms──> LLM 首 token ──100ms──> TTS 首包
 *                合计目标 ≤ 800ms
 * ```
 *
 * ## 为什么是「四段串行相加」而不是一条总计时
 *
 * 这是一条**串行**链路：VAD 判完才送 ASR，ASR 出字才请求 LLM，LLM 出 token 才拿 TTS。
 * 所以用户感知的「说完到听见」就是四段之和，中间没有任何并行可以相互掩盖。
 * （将来若改成边听边识别、或 Realtime API 一体化，这个模型要跟着改 —— 见 §4.5.4。）
 *
 * ## 为什么每段要有 target 与 limit 两个数
 *
 * 只有 target 的话，一次网络抖动就会把「不合格」判成常态，没人再看这个指标；
 * 只有 limit 的话，四段各差一点点、总账 790ms 合格，但每个环节都在磨蹭，
 * 排查时无从下手。
 *
 * 所以：**target 是记账线**（超了就记进报告，供诊断），**limit 是判定线**（超了算失败）。
 * 两个数都显式写在这里，不引入「2 倍」之类的魔法倍率 —— 魔法数无法解释，
 * 也不知道该不该调。
 */
enum class LatencySegment(
    /** 给人看的名字，进日志与诊断面板 */
    val displayName: String,
    /** 目标值：超出即记入报告的「亚健康」清单，但不判失败 */
    val targetMs: Int,
    /** 上限：超出即判失败 */
    val limitMs: Int,
) {
    /** 用户说完 → VAD 判停。纯本地计算，慢就意味着 VAD 窗口开太大 */
    VAD_ENDPOINT(displayName = "VAD 判停", targetMs = 300, limitMs = 600),

    /** 判停 → ASR 首个中间结果。受网络与音频上传策略影响 */
    ASR_FIRST_TEXT(displayName = "ASR 首字", targetMs = 200, limitMs = 450),

    /** 请求发出 → LLM 首个 token。受模型与首 token 优化影响 */
    LLM_FIRST_TOKEN(displayName = "LLM 首 token", targetMs = 200, limitMs = 450),

    /** 文本交给 TTS → 第一块音频可播。受云端合成与本地拼接影响 */
    TTS_FIRST_CHUNK(displayName = "TTS 首包", targetMs = 100, limitMs = 300),
    ;

    companion object {
        /**
         * 四段 target 之和 = 800ms。
         *
         * ⚠️ 这个常量与各段的 [targetMs] 是**两处独立**的数字，很容易改一处忘一处。
         * [LatencyLedgerTest] 里有一条断言 `values().sumOf { it.targetMs } == TARGET_TOTAL_MS`
         * 把它们钉在一起 —— 改任何一段的目标值而忘了改总账，测试立刻红。
         */
        const val TARGET_TOTAL_MS: Int = 800

        /**
         * 总账容差：目标 800ms 之外还允许 400ms 的合计超支（平均每段多 100ms）。
         *
         * 400ms 是**工程约定**，不是测量结论：低于它，用户仍会觉得「回答了」；
         * 再长就开始怀疑「是不是断了」。要调就调这里，别去动各段的 target。
         */
        const val TOTAL_TOLERANCE_MS: Int = 400

        /**
         * 总账上限 = 800 + 400 = 1200ms。
         *
         * ★ 它**必须小于**四段 [LatencySegment.limitMs] 之和（1800）：
         * 否则「总账超限」这个判定永远被「某一段超限」先触发，等于失效。
         * 有意义的场景恰恰是反过来的那种 —— 四段都勉强没爆表（500+440+440+290），
         * 每一段单独看都「还行」，合起来用户已经等了一秒七。这种整体性劣化
         * 只有总账能抓到。这条关系由 [LatencyLedgerTest] 断言钉住。
         */
        const val LIMIT_TOTAL_MS: Int = TARGET_TOTAL_MS + TOTAL_TOLERANCE_MS
    }
}

/**
 * 一次语音回合的延迟记账（可变，随打点推进）。
 *
 * ## 为什么缺段必须判「未完成」而不是当 0
 *
 * 如果把没测到的段按 0 计入总和，那么「只测了 VAD 一段」的回合会得到
 * 乐观得离谱的总账，并且**判定为合格**。这种假通过比没有指标更糟：
 * 它让人以为链路达标了，实际上有三段从来没被观测过。
 *
 * 所以 [report] 在缺段时直接返回 [LatencyVerdict.Incomplete]，不参与任何比较。
 *
 * ## 打点语义
 *
 * · 重复打同一段：以最后一次为准（重试/重连会重打，取最新才有意义）；
 * · 负数：直接拒绝（`require`）—— 那一定是打点逻辑错了，静默接受会污染总账；
 * · 顺序：不校验。乱序打点在真实链路里会出现（比如 ASR 首字迟到），
 *   强行校验只会制造假失败。
 */
class LatencyLedger {

    private val marks = LinkedHashMap<LatencySegment, Long>()

    /** 记录一段耗时。重复打点覆盖旧值 */
    fun mark(segment: LatencySegment, elapsedMs: Long): LatencyLedger {
        require(elapsedMs >= 0) {
            "耗时不能为负（" + segment.displayName + " 收到 " + elapsedMs + "ms）—— 打点逻辑错了"
        }
        marks[segment] = elapsedMs
        return this
    }

    /** 某段的实测值；**未测返回 null**，不要用 0 代替 —— 见类注释 */
    fun markedMs(segment: LatencySegment): Long? = marks[segment]

    /** 是否四段都测过了 */
    fun isComplete(): Boolean = marks.keys.containsAll(LatencySegment.values().toList())

    fun reset() {
        marks.clear()
    }

    /**
     * 生成报告与判定。
     *
     * 判定顺序是固定的：**先看完整性，再看超限**。
     * 反过来的话，一条只测了半段的回合可能在某一小段上超限而报 OverBudget，
     * 掩盖了「另有三段没测」这个更根本的问题。
     */
    fun report(): LatencyReport {
        val missing = LatencySegment.values().filterNot { marks.containsKey(it) }.toSet()
        val measuredTotal = marks.values.sum()
        val overSegments = LatencySegment.values()
            .filter { (marks[it] ?: 0L) > it.limitMs }
        val slowSegments = LatencySegment.values()
            .filter { seg ->
                val v = marks[seg] ?: return@filter false
                v > seg.targetMs && v <= seg.limitMs
            }
        val totalOver = measuredTotal > LatencySegment.LIMIT_TOTAL_MS

        val verdict: LatencyVerdict = when {
            missing.isNotEmpty() -> LatencyVerdict.Incomplete(missing)
            overSegments.isNotEmpty() || totalOver -> LatencyVerdict.OverBudget(
                measuredTotalMs = measuredTotal,
                totalOver = totalOver,
                overSegments = overSegments,
            )
            else -> LatencyVerdict.WithinBudget
        }

        return LatencyReport(
            measuredTotalMs = measuredTotal,
            isComplete = missing.isEmpty(),
            marks = marks.toMap(),
            missing = missing,
            slowSegments = slowSegments,
            verdict = verdict,
        )
    }
}

/**
 * 一次回合的延迟结论。
 *
 * ⚠️ [measuredTotalMs] 是**已测段之和**。只有 [isComplete] 为 true 时它才等于
 * 用户感知延迟；缺段时的部分和只能用来看「已测的部分有多慢」。
 * 判定请一律看 [verdict]，不要自己拿总和去比 800。
 */
data class LatencyReport(
    val measuredTotalMs: Long,
    val isComplete: Boolean,
    val marks: Map<LatencySegment, Long>,
    val missing: Set<LatencySegment>,
    /** 超 target 但未超 limit 的段：不判失败，但在诊断里必须看得见 */
    val slowSegments: List<LatencySegment>,
    val verdict: LatencyVerdict,
) {
    /** 一行摘要，直接进日志 */
    fun summary(): String = when (val v = verdict) {
        LatencyVerdict.WithinBudget -> {
            val head = "延迟合格：" + measuredTotalMs + "ms（上限 " +
                LatencySegment.LIMIT_TOTAL_MS + "ms）"
            // ★ 合格也要把慢段念出来。只写「合格」的日志让"四段全程磨蹭但没爆表"
            // 看起来和"四段飞快"一模一样，而这正是最该被发现的那类劣化
            if (slowSegments.isEmpty()) {
                head
            } else {
                head + "；未达目标：" + slowSegments.joinToString("、") { seg ->
                    seg.displayName + " " + (marks[seg] ?: 0L) + "ms/" + seg.targetMs + "ms"
                }
            }
        }
        is LatencyVerdict.Incomplete ->
            "延迟未测全：缺 " + v.missing.joinToString("、") { it.displayName }
        is LatencyVerdict.OverBudget -> {
            val parts = mutableListOf<String>()
            if (v.totalOver) {
                parts.add("合计 " + v.measuredTotalMs + "ms 超过上限 " + LatencySegment.LIMIT_TOTAL_MS + "ms")
            }
            for (seg in v.overSegments) {
                parts.add(seg.displayName + " " + (marks[seg] ?: 0L) + "ms 超过上限 " + seg.limitMs + "ms")
            }
            "延迟超限：" + parts.joinToString("；")
        }
    }
}

/** 延迟判定。三态把「不合格」与「没测全」分开 —— 它们的处置方式完全不同 */
sealed interface LatencyVerdict {
    /** 四段齐全且都在上限内 */
    data object WithinBudget : LatencyVerdict

    /**
     * 至少一项超限。
     *
     * [totalOver] 与 [overSegments] 可能同时为真：总账超了，同时某段也爆了。
     * 分开记录是为了让人一眼看出「是某一段拖垮了总账」还是「四段都慢、总量超标」。
     */
    data class OverBudget(
        val measuredTotalMs: Long,
        val totalOver: Boolean,
        val overSegments: List<LatencySegment>,
    ) : LatencyVerdict

    /**
     * 有段没测到。
     *
     * ★ 这个分支存在本身就是一种防护：它让「缺数据」无法被当成「数据很好」。
     */
    data class Incomplete(val missing: Set<LatencySegment>) : LatencyVerdict
}
