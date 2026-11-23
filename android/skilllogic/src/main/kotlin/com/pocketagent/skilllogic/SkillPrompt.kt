package com.pocketagent.skilllogic

/**
 * 把技能拼成提示词里的那两段。
 *
 * ## 两段分工
 *
 * | 段 | 什么时候出现 | 说什么 |
 * |---|---|---|
 * | 清单（[listSection]） | 工作模式每轮 | 有哪些技能、各自管什么。**只是目录**，不含正文 |
 * | 命中（[activeSection]） | 这一轮用得上某个技能时 | 那一条的正文规则 |
 *
 * 分开的理由只有一个：**正文很贵**。八条技能全塞进去要两三万字，
 * 而 95% 的轮次一条都用不上。清单几百字，命中时才补一段。
 *
 * ## 预算
 *
 * 清单封顶 [MAX_LIST_CHARS]。超了就**从末尾删整行**并补一句说明 ——
 * 不截半句（模型会把半句当完整规则），也不静默丢（用户会以为技能没导进来）。
 *
 * ## 语气边界
 *
 * 技能管的是"怎么做这件事"，**不管"用什么口气"**。所以 [activeSection]
 * 里明确写了一句「不要因为这段改变说话方式」—— 否则工作模式的回复会
 * 从"同事在帮你"变成"操作手册"。
 *
 * ## 为什么没有登记进 `PromptVoiceTest.productionSections()`
 *
 * 那张表的用途是抓「提示词自己带了会被照抄的形状」——行首列表符、编号、
 * 星号加粗、排比。技能段落**刻意**全是这些东西：清单就是一列 `- 名字：说明`，
 * 正文就是一串编号步骤，这是"做法"的正确写法，不是腔调。
 *
 * 真正需要盯的是**框架那几句**（清单的 `## 你能用的工作技能` 抬头与
 * [activeSection] 的引导语），
 * 它们由 [SkillPromptTest] 直接断言形状；正文与清单不参与去 AI 味体检，
 * 因为模型从中学的是流程，不是句式。反过来把它们塞进那张表只会得到
 * 一条无法修的常红。
 */
object SkillPrompt {

    /** 清单上限。约等于 40 行短句，塞得进系统提示词又不至于挤掉记忆。 */
    const val MAX_LIST_CHARS = 900

    enum class How {
        /** 用户打了 `/技能名` —— 点名要用。 */
        PICKED,

        /** 从用户的话里判断出来的 —— 可能判断错，允许模型忽略。 */
        MATCHED,
    }

    /**
     * 目录段。没有技能时返回空串（调用方直接 `+ section`，空串无害）。
     */
    fun listSection(skills: List<Skill>): String {
        if (skills.isEmpty()) return ""
        val header = buildString {
            appendLine("## 你能用的工作技能")
            appendLine()
            appendLine("遇到下面这些事，按对应技能里的规则做；没列到的按通用习惯做。")
            appendLine("用户如果打了 `/`，就是点名要用某一条。")
            appendLine()
        }
        val kept = ArrayList<String>(skills.size)
        var used = header.length
        var dropped = 0
        for (skill in skills) {
            val line = skill.listLine()
            if (used + line.length + 1 > MAX_LIST_CHARS) {
                dropped = skills.size - kept.size
                break
            }
            kept += line
            used += line.length + 1
        }
        if (kept.isEmpty()) return ""
        return buildString {
            append(header)
            appendLine(kept.joinToString("\n"))
            if (dropped > 0) {
                appendLine()
                append("（还有 $dropped 条没列出来 —— 用户在输入框打 / 能看到全部）")
            }
        }.trimEnd()
    }

    /**
     * 命中段。
     *
     * @param how 见 [How]。
     */
    fun activeSection(skill: Skill, how: How): String {
        val head = when (how) {
            How.PICKED ->
                "## 这一轮按「${skill.name}」做\n\n" +
                    "用户点了这条技能，下面就是它的做法。"
            How.MATCHED ->
                "## 这一轮看起来要用「${skill.name}」\n\n" +
                    "这是从用户的要求里判断出来的。**如果明显不相关，忽略这一段**，按通用习惯做。"
        }
        return buildString {
            appendLine(head)
            appendLine()
            appendLine("技能只管做法，不改变你说话的方式和称呼 —— 别因为这段变成操作手册。")
            appendLine()
            appendLine(skill.body.trim())
        }.trimEnd()
    }

    /**
     * 一次性给出这一轮该带哪一段。
     *
     * @param picked 用户点名的技能（优先）。
     * @param matched 从原话里判出来的（[picked] 为空时才用）。
     */
    fun sectionFor(skills: List<Skill>, picked: Skill?, matched: Skill?): String {
        val list = listSection(skills)
        val active = when {
            picked != null -> activeSection(picked, How.PICKED)
            matched != null -> activeSection(matched, How.MATCHED)
            else -> ""
        }
        return listOf(list, active).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
