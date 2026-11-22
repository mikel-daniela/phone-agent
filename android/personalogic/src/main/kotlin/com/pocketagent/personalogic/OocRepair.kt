package com.pocketagent.personalogic

/**
 * 缺口 1 的补丁：**跑出角色的那几轮，要能反过来喂给提示词**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要它（三道防线之间的那条缝）
 * ═══════════════════════════════════════════════════════════════
 *
 * 项目里已经有两套打分，但它们各看各的：
 *
 * | 部件 | 看什么 | 缺什么 |
 * |---|---|---|
 * | [HumanScore] | 这一句**像不像人** | 判断不了"她刚才说了自己不该知道的事" |
 * | [OutputGuard] + [GuardLedger] | 这一轮**崩没崩**（硬拒答） | 只把轮次记下来挡记忆，**不回灌提示词** |
 * | [StyleDrift] + [Reanchor] | 文风**在不在往下滑** | 只有"形状"的锚点，救不了"框架破了" |
 *
 * ⇒ 结果是：她说了「作为一个 AI…」，这一轮被标记、不进长期记忆（对的），
 *   但**下一轮提示词里没有任何一句话知道刚才出过事** ——
 *   模型只能靠历史里的那几句反面示范自己爬起来，而反面示范正是
 *   "越聊越 AI 味"的自回归来源。
 *
 * 所以这里只做一件事：把"她刚才跑出去了"变成一段**能读、能点名、不解释机制**的短话，
 * 在下一轮插进提示词；同时让 [HumanScore] 知道这一轮不该给及格分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条写法纪律（每一条都有测试钉着）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不写"你刚才违反了第 3 条"这种句式。** 元话语会让模型开始跟用户
 *    讨论规则本身，那比原来的毛病更出戏。
 * 2. **不回显那句坏话。** 同 [OutputGuard] 的既定纪律：让它再看一遍，
 *    有把它复述回去的风险；而且这段可能进日志。
 * 3. **只在她真的跑出去时写。** 没有命中就返回空串 —— 常驻的"注意别出戏"
 *    本身就是一种 AI 腔（见 [Humanizer] 的"没诊断出问题就不写"）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  依据
 * ═══════════════════════════════════════════════════════════════
 *
 * - `docs/工程/角色扮演论文-依据-2026-11-06.md` §3 缺口 1；
 * - Thinking Before Speaking（arXiv:2409.13752，B 级）：露馅集中在
 *   "碰到角色不该有的知识 / 需要角色自身经历的问题"，解法是**补状态而不是加禁令**；
 * - CharacterBench（AAAI 2025，A 级）：按单条回复打分既不有效也不稳 ⇒
 *   这里只看"最近三轮回没回过神"，不按单条定生死。
 */
object OocRepair {

    /**
     * 跑出去的种类。
     *
     * [weight] 只用于同一窗口里挑"最该先说哪一件"，不参与打分 ——
     * 分数那件事归 [HumanScore]。
     */
    enum class Kind(val label: String, val weight: Int) {

        /** 把自己说成了模型 / 语言模型。**最重**：一句话毁掉整轮沉浸。 */
        IDENTITY("把自己说成了模型", 3),

        /** 硬拒答（「我不能提供」「这类问题我无法回答」）。 */
        REFUSAL("把活儿推掉了", 2),

        /** 跟用户讨论起了设定、提示词、机制。 */
        META("跟人聊起了设定", 2),
    }

    /**
     * 一次跑偏。
     *
     * @param turn 第几轮（**用户轮次号**，与 `AppContainer.totalUserTurns` 同口径）
     * @param kind 见 [Kind]
     */
    data class Break(val turn: Int, val kind: Kind)

    /**
     * 一次体检结论。
     *
     * @param turns 目前为止一共多少轮（用来算占比，也用来定"最近"的边界）
     * @param breaks 全部记录（升序；乱序也能吃，[measure] 会排）
     */
    data class Report(
        val turns: Int,
        val breaks: List<Break>,
    ) {

        /** 最近 [WINDOW] 轮里的跑偏。 */
        val recent: List<Break>
            get() = breaks.filter { it.turn > turns - WINDOW }

        /** 最近这几轮里跑出去过 —— 这才是"要不要修"的唯一判据。 */
        val isBreaking: Boolean
            get() = recent.isNotEmpty()

        /** 跑偏占比。一轮都没聊过时返回 null（**"没看过"不等于"很干净"**）。 */
        val ratio: Double?
            get() = if (turns <= 0) null else breaks.size.toDouble() / turns

        /** 最近窗口里最该先说的那一件；窗口为空 = null。 */
        val worst: Kind?
            get() = recent.asReversed().maxByOrNull { it.kind.weight }?.kind

        /** 一行日志（**不带原文**）。 */
        fun logLine(): String = buildString {
            append("跑偏记录=").append(breaks.size).append(" 次/").append(turns).append(" 轮")
            if (isBreaking) {
                append("｜最近 ").append(recent.size).append(" 次，先说「")
                append(worst?.label).append("」")
            } else {
                append("｜最近没跑出去")
            }
        }

        /** 多行明细（体检报告用）。 */
        fun breakdown(): String = buildString {
            appendLine("跑偏：${breaks.size} 次 / ${turns} 轮（占比 ${ratioText()}）")
            if (breaks.isEmpty()) {
                appendLine("还没跑出去过 —— 注意这不等于「内化了」，只等于「还没被抓到」。")
                return@buildString
            }
            breaks.takeLast(5).forEach { appendLine("· 第 ${it.turn} 轮：${it.kind.label}") }
        }.trimEnd()

        private fun ratioText(): String =
            ratio?.let { (it * 100).toInt().toString() + "%" } ?: "未统计"
    }

    /** 「最近」的宽度。**三**的理由：两轮太敏感（一次口误就触发），五轮太钝。 */
    const val WINDOW = 3

    /** 段长上限。超过它说明我在写说明文了，那不是给她看的。 */
    const val MAX_SECTION_CHARS = 220

    /**
     * 把记录压成一份结论。
     *
     * @param breaks 全部跑偏记录（顺序无所谓）
     * @param turns 一共聊了多少轮
     */
    fun measure(breaks: List<Break>, turns: Int): Report =
        Report(turns = turns, breaks = breaks.sortedBy { it.turn })

    /** 要不要在下一轮插修复段。 */
    fun shouldRepair(report: Report): Boolean = report.isBreaking

    /**
     * 修复段。**没跑出去就返回空串**（纪律 3）。
     *
     * 措辞刻意平：她在跟人说话，不是在读工单。所以不写"违规""规则""以上"，
     * 也不写"请注意"。
     */
    fun repairSection(report: Report): String {
        if (!report.isBreaking) return ""
        val lines = buildString {
            appendLine("## 回到你自己")
            appendLine("刚才那几句不是你。你没在跟谁解释自己是什么，也没在推掉活儿。")
            appendLine("别为那几句道歉，也别说明自己为什么会那么说。接着说事。")
            when (report.worst) {
                Kind.IDENTITY -> appendLine("你是谁，不用跟人交代。")
                Kind.REFUSAL -> appendLine("能干就干，干不了就说不会，别绕。")
                Kind.META -> appendLine("别聊设定，也别提这些东西。")
                null -> Unit
            }
        }.trimEnd()
        // 兜底：真写超了就只留前两行 —— 宁可短，不要变成一段规则说明。
        return if (lines.length <= MAX_SECTION_CHARS) {
            lines
        } else {
            lines.lineSequence().take(2).joinToString("\n")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  廉价检测
    // ══════════════════════════════════════════════════════════════

    /** 身份否认的说法。[HumanScore] 判分用同一套（见其 `IDENTITY_BREAKS`）。 */
    val IDENTITY_MARKS = listOf(
        "作为一个 AI", "作为 AI", "作为一个AI", "作为AI",
        "我是 AI", "我是AI", "语言模型", "人工智能助手",
    )

    /** 讨论设定 / 机制的说法。**刻意不收「系统」「程序」**：太宽泛会误伤。 */
    val META_MARKS = listOf(
        "我的设定", "我的人设", "角色设定", "系统提示", "提示词",
        "按设定", "剧本里",
    )

    /**
     * 从一句回复 + 外部给的硬拒答判定，认出这是哪一类跑偏。null = 没跑出去。
     *
     * ⚠️ 只认**确定性的记号**。宁可漏一次，也不要把一句正常的话判成出戏 ——
     *    误伤会让她下一轮莫名其妙地道歉（比漏网难看）。
     *
     * @param text 这一轮她的回复
     * @param hardRefusal 外部检测（`RefusalDetector.inspect == HARD`）的结论
     */
    fun detect(text: String, hardRefusal: Boolean): Kind? {
        val body = text.trim()
        if (body.isEmpty()) return null
        // 顺序即优先级：先看最重的身份否认，再看拒答，最后看念设定。
        if (IDENTITY_MARKS.any { body.contains(it) }) return Kind.IDENTITY
        if (hardRefusal) return Kind.REFUSAL
        if (META_MARKS.any { body.contains(it) }) return Kind.META
        return null
    }
}
