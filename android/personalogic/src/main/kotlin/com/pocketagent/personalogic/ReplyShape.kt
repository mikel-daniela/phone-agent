package com.pocketagent.personalogic

/**
 * **回复的形状** —— 把"像文档"的排版从聊天回复里拿掉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的哪一句话
 * ═══════════════════════════════════════════════════════════════
 *
 * "长时间对话，还是会出现 ai 味超级浓的，就是会变成一种模板化的回答，
 *  感觉会一直在套公式。"
 *
 * 前几轮她还在演人，轮次一多就回落到模型的默认习惯。**默认习惯里最刺眼的
 * 一条不是用词，是排版**：小标题、加粗、分点。人发微信不会先写一个
 * `**首先**`，再列三条。所以哪怕内容没错，看到那种形状就知道不是人。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么在代码里做，而不是再写一条禁令
 * ═══════════════════════════════════════════════════════════════
 *
 * [Humanizer] 已经有一条"别分点"的提示词，但提示词只能降概率：
 * 模型在长上下文里会漂回去，而漂回去之后那条禁令**也在上下文里**，
 * 于是它一边被要求别分点、一边看着自己前几轮的加粗体学样。
 *
 * 这是**确定性的、能在返回前做掉**的事 —— 那就别指望提示词。
 * 同一句话，去掉 `**` 与 `- ` 之后读起来就是人话。
 *
 * ⚠️ 只用于**聊天**（角色扮演 / 在场）。工作模式的产出是给人看的文档，
 *    那里的小标题是功能，不是 AI 味 —— 调用点自己判模式，本类不判。
 */
object ReplyShape {

    /** 有没有需要收拾的排版痕迹。 */
    fun hasFormatting(text: String): Boolean {
        if (text.isBlank()) return false
        if (BOLD.containsMatchIn(text)) return true
        var inCode = false
        for (line in text.lineSequence()) {
            if (FENCE.matches(line.trim())) {
                inCode = !inCode
                continue
            }
            if (inCode) continue
            if (HEADING.containsMatchIn(line)) return true
            if (BULLET.containsMatchIn(line)) return true
            if (RULE.matches(line)) return true
        }
        return false
    }

    /**
     * 去掉排版痕迹，保留文字本身。
     *
     * 规则（全部只作用于**代码块之外**的行）：
     * · `### 标题` → `标题`
     * · `- 一条` / `1. 一条` / `• 一条` → `一条`
     * · `---` 分隔线 → 整行删掉
     * · `**加粗**` / `__加粗__` / 行内 `反引号` → 只删标记
     *
     * ⚠️ 三条不做的事：
     *   1. 代码块原样保留（里面 `#`、`-`、`*` 都是有意义的）；
     *   2. 不合并相邻的行 —— 分行是她自己的节奏，不是排版；
     *   3. 洗完若为空（整条都是排版），**返回原文**。宁可留着让人看见，
     *      也不能把一句回复洗成空串 —— 那会被上层当成"模型没吐字"。
     */
    fun strip(text: String): String {
        if (text.isBlank()) return text
        val out = StringBuilder(text.length)
        var inCode = false
        for (line in text.lineSequence()) {
            if (FENCE.matches(line.trim())) {
                inCode = !inCode
                out.append(line).append('\n')
                continue
            }
            if (inCode) {
                out.append(line).append('\n')
                continue
            }
            if (RULE.matches(line)) continue
            var fixed = line
            fixed = HEADING.replace(fixed, "")
            fixed = BULLET.replace(fixed, "")
            fixed = BOLD.replace(fixed, "")
            fixed = INLINE_CODE.replace(fixed, "")
            out.append(fixed).append('\n')
        }
        val cleaned = out.toString().trimEnd()
        if (cleaned.isBlank()) return text
        return collapseBlankRuns(cleaned)
    }

    // ── 判据 ──────────────────────────────────────────────────

    /** 连着的空行压成一个 —— 去掉列表记号之后常会留下孤立的空行。 */
    private fun collapseBlankRuns(text: String): String {
        val out = StringBuilder(text.length)
        var blank = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                blank++
                if (blank > 1) continue
            } else {
                blank = 0
            }
            out.append(line).append('\n')
        }
        return out.toString().trimEnd()
    }

    /** ``` 开头/结尾的围栏（允许带语言名）。 */
    private val FENCE = Regex("""^\s*```.*$""")

    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")

    /** `- ` `* ` `• ` `· ` 与 `1. ` `1) ` `1、`。 */
    private val BULLET = Regex("""^\s{0,3}(?:[-*•·]|\d{1,2}[.)、])\s+""")

    private val RULE = Regex("""^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$""")

    private val BOLD = Regex("""\*\*|__""")

    private val INLINE_CODE = Regex("`")
}
