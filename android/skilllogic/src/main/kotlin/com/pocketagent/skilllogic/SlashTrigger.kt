package com.pocketagent.skilllogic

/**
 * 输入框里的 `/` 触发。
 *
 * ## 与 `@` 面板的关系
 *
 * `@` 挑的是"素材"（能力、记忆、文件），`/` 挑的是"怎么做"（技能）。
 * 两者共用同一套判定形状，但**互不干扰**：一个输入框里同时只能开一个面板，
 * 而 `@` 与 `/` 的判据各自独立 —— 谁在文本末尾谁说了算。
 *
 * ## 判定（三条，和 `MentionTrigger` 同构）
 *
 * 1. `/` 必须是**最后一个**（前面的 `/` 当普通字符）
 * 2. `/` 前面是开头或空白 —— 挡掉 `http://…`（前一个字符是 `:`）与 `a/b`（路径）
 * 3. `/` 之后到末尾**没有空白** —— 打了空格就当挑完/放弃
 *
 * ★ 已知限制与 `MentionTrigger` 相同：输入框是 `BasicTextField(value: String)`，
 *   拿不到光标位置，所以把"文本末尾"当光标。改成 `TextFieldValue` 会牵动
 *   `TasksViewModel` 的草稿类型与全部持久化路径，不在这一轮做。
 *   [replace] 的签名已按"某一段区间"设计，将来换过去只动 [active] 的入参。
 */
object SlashTrigger {

    data class Active(val start: Int, val query: String)

    fun active(text: String): Active? {
        if (text.isEmpty()) return null
        val at = text.lastIndexOf('/')
        if (at < 0) return null
        // 开头就是 `//` 的当命令行/注释，不弹面板。
        if (at == 0 && text.length > 1 && text[1] == '/') return null
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val tail = text.substring(at + 1)
        if (tail.any { it.isWhitespace() }) return null
        return Active(start = at, query = tail)
    }

    /**
     * 把 `/检索词` 换成 `/技能名 `。
     *
     * ★ 末尾那个空格**是功能性的**：`active()` 的第三条判据要求"后面没有空白"，
     *   所以补上空格后面板自然收起。这与 `MentionTrigger.replace` 同一条纪律 ——
     *   不是排版偏好，是用来关面板的。
     */
    fun replace(text: String, active: Active, name: String): String {
        if (name.isBlank()) return text
        val head = text.substring(0, active.start)
        val tailStart = active.start + 1 + active.query.length
        val tail = if (tailStart <= text.length) text.substring(tailStart) else ""
        return head + "/" + name + " " + tail
    }

    /**
     * 已经挑中的技能：文本以 `/技能名` 开头。
     *
     * 返回技能与**去掉这个前缀之后**的正文（给模型看的用户原话）。
     * 前缀后面可以没有空格（用户直接又打了字），也可以有多个空格。
     */
    fun resolve(text: String, skills: List<Skill> = BuiltinSkills.all): Pick? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('/')) return null
        val rest = trimmed.substring(1)
        if (rest.isEmpty()) return null
        // ★ 不能只按空白切词：`/deck讲一下` 里"讲一下"不是空白，整段会被当成一个 token
        //   于是永远匹配不上。改成"看有没有哪条技能的名字/id 正好是这个前缀"，
        //   并取**最长**的那条（`做演示` 与 `做演示文稿` 同时存在时选后者）。
        //   还要求前缀后面不是 ASCII 字母数字，否则 `/decoder` 会被认成 `/deck`。
        val matched = skills
            .mapNotNull { skill ->
                val byName = skill.name.takeIf { rest.startsWith(it) }?.let { it.length to skill }
                val byId = skill.id.takeIf { rest.startsWith(it) }?.let { it.length to skill }
                listOfNotNull(byName, byId).maxByOrNull { it.first }
            }
            .filter { (len, _) ->
                val next = rest.getOrNull(len)
                next == null || next.isWhitespace() || !next.isLetterOrDigit() || next.code > 127
            }
            .maxByOrNull { it.first }
            ?: return null
        val body = rest.substring(matched.first).trim()
        return Pick(skill = matched.second, text = body)
    }

    data class Pick(val skill: Skill, val text: String)
}
