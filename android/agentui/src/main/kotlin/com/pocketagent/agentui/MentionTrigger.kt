package com.pocketagent.agentui

/**
 * `@` 面板的**触发判定**与**插入**，纯函数。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么单独一个对象，而不是写在输入框的 onValueChange 里
 * ═══════════════════════════════════════════════════════════════
 *
 * 判定规则一旦写进界面回调，就只能靠手指试出来对错：多打一个空格、
 * 中途退格、把 `@` 打在句子中间 —— 每种都该有明确结论，而它们全部
 * 是"看起来还行、用起来别扭"的那类问题。
 *
 * 抽成纯函数之后，这些边界在离线测试里逐条钉住。
 *
 * ⚠️ **已知限制**（刻意，不是遗漏）：这里把"光标位置"当成**文本末尾**。
 *    输入框现在用 `BasicTextField(value: String)`，拿不到光标位置；
 *    为它改成 `TextFieldValue` 会牵动 `TasksViewModel` 的草稿类型与
 *    全部持久化路径。而实际用法里 `@` 几乎总是打在末尾 ——
 *    先去支持末尾，比为了句中的 `@` 把草稿结构翻一遍划算。
 *    等确认用户真需要句中提及时，再改成带光标的版本（[replace] 的
 *    签名已经按"某一段区间"设计，换过去只动 [active] 的入参）。
 */
object MentionTrigger {

    /** 一次**正在输入**的提及：`@` 在 [start]，后面已打的内容是 [query]（可能是空串）。 */
    data class Active(
        /** `@` 的下标。 */
        val start: Int,
        /** `@` 之后已输入的内容（已 trim，可能为空）。 */
        val query: String,
    )

    /**
     * 找出末尾那个还开着头的 `@`。没有就返回 null。
     *
     * 判定条件（全部满足才算"正在提及"）：
     *
     * 1. `@` 是**最后一个** `@`；
     * 2. `@` 前面是文本开头或空白 —— 让 `a@b.com` 这种邮箱不当成提及；
     * 3. `@` 后面到末尾**没有空白** —— 一旦打了空格，说明这个提及已经结束
     *    （或者用户压根是在写 `@ somebody` 这种句子），面板该收起来。
     */
    fun active(text: String): Active? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val tail = text.substring(at + 1)
        if (tail.any { it.isWhitespace() }) return null
        return Active(start = at, query = tail)
    }

    /**
     * 把 [active] 这一段换成 [label]。
     *
     * ⚠️ 换上去的是**显示名**而不是 key：用户看到的那句话要能读通，
     *    而模型那边本来就把工具名当普通词读。
     *    后面补一个空格 —— 否则接着打下去会和提及粘成一个词。
     *
     * @return 新的文本（`@` 与已输入的 query 都被替换掉）。空 label 视为"不换"，
     *   原样返回 —— 让调用方少一层判空。
     */
    fun replace(text: String, active: Active, label: String): String {
        val shown = label.trim()
        if (shown.isEmpty()) return text
        if (active.start !in 0..text.length) return text
        return text.substring(0, active.start) + shown + " "
    }
}
