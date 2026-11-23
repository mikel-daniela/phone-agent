package com.pocketagent.agentui

/**
 * 把 agent 循环吐出来的**一行文字**重新拆回结构。
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ★ 为什么要在界面上再拆一次（而不是让循环直接给结构体）
 * ═══════════════════════════════════════════════════════════════════
 *
 * `PhoneAgentLoop` 给界面的 `onStep` / `trace` 都是**一行字符串**：
 *
 * ```
 * linux_exec → 退出码 0
 * linux_exec 失败：容器没起来
 * android_tap 被拦下：这一步要花钱
 * ```
 *
 * 这几种形状在循环里是**三个不同的分支**、携带三种不同的语义
 * （做成 / 没做成可重试 / 被安全策略拦住不许重试）。而界面把它们
 * 一视同仁地画成 `· 一行灰字` —— 用户看不出哪一步出了问题，
 * 更看不出"被拦下"和"失败"不是一回事（前者不该重试，后者该）。
 *
 * 改成让循环直接产出结构体要动 `PhoneRunResult` 的三个分支、
 * `ChatResult.Ok`、以及它们的全部构造点与测试。而这三个字符串
 * 形状**本来就是循环的产物**，把它们拆回来是纯函数、离线可测、
 * 不牵动执行路径 —— 所以拆在这一侧。
 *
 * ⚠️ 拆不出来的行**不丢**：归成 [Outcome.UNKNOWN]，原样放进
 *    [Step.detail]。宁可显示一行没有工具名的字，也不要让
 *    "界面上少了一步"这种事发生。
 */
object ToolTrace {

    /** 一步的结局。语义差别见类注释。 */
    enum class Outcome {
        /** 做成了。 */
        DONE,

        /** 没做成，但**可以重试**（技术故障）。 */
        FAILED,

        /** 被安全策略拦住，**不许重试**。 */
        BLOCKED,

        /** 认不出形状的行（旧格式、或者循环将来改了文案）。 */
        UNKNOWN,
    }

    /**
     * 一步。
     *
     * @param tool 工具名；认不出来时是空串（不是 `null` —— 界面上少一层分支）。
     * @param detail 这一步的说明，可能是空串。
     * @param raw 原始那一行，永远保留。
     */
    data class Step(
        val tool: String,
        val detail: String,
        val outcome: Outcome,
        val raw: String,
    )

    /** 成功那行的分隔符（`"linux_exec → 退出码 0"`）。 */
    private const val ARROW = " → "

    /** 失败那行的分隔符。 */
    private const val FAILED = " 失败："

    /** 被拦那行的分隔符。 */
    private const val BLOCKED = " 被拦下："

    /**
     * 工具名最长多少字。
     *
     * `ToolSpec.id` 的上限是 48，这里留到 48；超出说明这一行不是
     * "工具名 + 分隔符"的形状（比如详情里恰好出现了 ` → `），
     * 归到 [Outcome.UNKNOWN]。
     */
    private const val MAX_TOOL_CHARS = 48

    /** 拆一行。 */
    fun parse(line: String): Step {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return Step("", "", Outcome.UNKNOWN, line)

        // ⚠️ 三个分隔符的**顺序**有讲究：先找 `→`。
        //   `.oneLine()` 把换行压成空格，所以详情里完全可能再出现
        //   "失败：" 这类词；而工具名是第一个 token，它前面不会先
        //   出现别的分隔符。按"最先出现的那个"判，与循环里
        //   `if/else` 的分支顺序一致。
        val arrow = trimmed.indexOf(ARROW)
        val failed = trimmed.indexOf(FAILED)
        val blocked = trimmed.indexOf(BLOCKED)

        val candidates = listOf(
            Triple(arrow, ARROW, Outcome.DONE),
            Triple(failed, FAILED, Outcome.FAILED),
            Triple(blocked, BLOCKED, Outcome.BLOCKED),
        ).filter { it.first > 0 }

        val hit = candidates.minByOrNull { it.first }
            ?: return Step("", trimmed, Outcome.UNKNOWN, line)

        val tool = trimmed.substring(0, hit.first)
        val detail = trimmed.substring(hit.first + hit.second.length).trim()
        if (!looksLikeTool(tool)) return Step("", trimmed, Outcome.UNKNOWN, line)
        return Step(tool, detail, hit.third, line)
    }

    /** 拆一串。空行与纯空白行会被跳过（不是"未知步骤"）。 */
    fun parse(lines: List<String>): List<Step> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.map { parse(it) }

    /**
     * 折叠时那一行的摘要。
     *
     * 只在**真的跑过动作**时才有意义；空列表返回空串，让界面
     * 不要画一块空白的摘要。
     */
    fun summary(steps: List<Step>): String {
        if (steps.isEmpty()) return ""
        val done = steps.count { it.outcome == Outcome.DONE }
        val failed = steps.count { it.outcome == Outcome.FAILED }
        val blocked = steps.count { it.outcome == Outcome.BLOCKED }
        return buildString {
            append("跑了 ").append(steps.size).append(" 个动作")
            if (done > 0) append(" · ").append(done).append(" 成功")
            if (failed > 0) append(" / ").append(failed).append(" 失败")
            // "被拦下"与"失败"分开数：它意味着**不该重试**，是另一种处置。
            if (blocked > 0) append(" / ").append(blocked).append(" 被拦")
        }
    }

    /**
     * 工具名是不是真的像个工具名。
     *
     * 判据只有两条：非空、不含空白、不太长。**不校验字符集** ——
     * 工具名将来可能带点或短横，卡死字符集会让新工具在界面上
     * 全部退化成"未知步骤"。
     */
    private fun looksLikeTool(raw: String): Boolean =
        raw.isNotBlank() && raw.length <= MAX_TOOL_CHARS && raw.none { it.isWhitespace() }
}
