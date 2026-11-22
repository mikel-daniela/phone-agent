package com.pocketagent.agentui

/**
 * 把**容器里有什么**翻译成 `@` 面板的候选池。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么翻译单独放一层，而不是在 Composable 里 map
 * ═══════════════════════════════════════════════════════════════
 *
 * 三个理由，都不是"好看"：
 *
 * 1. **`MentionIndex.Item` 有约束**（key/label 非空）—— 空名字的工具或空文本的
 *    记忆一旦进池，会在用户敲 `@` 的那一下抛 [IllegalArgumentException]，
 *    而那是输入框，崩在打字上最难看。过滤必须发生在**构造之前**。
 * 2. **长文本要截**：记忆原文可能是一整句 80 字的话，直接当 label 会把面板
 *    撑成一面墙。截断规则（[MEMORY_LABEL_MAX]）需要被断言。
 * 3. **分组与排序是产品决定**：能力走名字、记忆走时间 —— 这个决定应该
 *    留在一个能写注释的地方，而不是散在 UI 的 `map { }` 里。
 */
object MentionPool {

    /** 记忆在面板上的最长显示字数。超出补省略号。 */
    const val MEMORY_LABEL_MAX = 18

    /** 文件路径在面板上保留的末尾段数（`a/b/c.txt` → `b/c.txt`）。 */
    const val FILE_TAIL_SEGMENTS = 2

    /**
     * 能力（工具）。
     *
     * key = 工具原名（模型认得出），label = 短名（人读）。
     * [titles] 缺某个名字时退回原名 —— 面板宁可朴素，不能空一格。
     */
    fun capabilities(names: List<String>, titles: Map<String, String> = emptyMap()): List<MentionIndex.Item> =
        names.mapNotNull { raw ->
            val name = raw.trim()
            if (name.isEmpty()) return@mapNotNull null
            MentionIndex.Item(
                key = name,
                label = titles[name]?.trim()?.takeIf { it.isNotEmpty() } ?: name,
                group = MentionIndex.Group.CAPABILITY,
            )
        }.distinctBy { it.key }

    /**
     * 记忆。
     *
     * key 用**原文**（它是稳定标识，也是检索词），label 截到 [MEMORY_LABEL_MAX]。
     * [entries] 的顺序即"最近优先"—— 排序由调用方给，这里不猜。
     */
    fun memories(entries: List<Pair<String, String>>): List<MentionIndex.Item> =
        entries.mapNotNull { (id, text) ->
            val body = text.trim()
            if (id.isBlank() || body.isEmpty()) return@mapNotNull null
            MentionIndex.Item(
                key = id.trim(),
                label = clip(body),
                group = MentionIndex.Group.MEMORY,
            )
        }.distinctBy { it.key }

    /** 文件。label 只留末尾两段，长路径在面板上读不出来。 */
    fun files(paths: List<String>): List<MentionIndex.Item> =
        paths.mapNotNull { raw ->
            val path = raw.trim()
            if (path.isEmpty()) return@mapNotNull null
            MentionIndex.Item(
                key = path,
                label = tail(path),
                group = MentionIndex.Group.FILE,
            )
        }.distinctBy { it.key }

    /**
     * 应用。
     *
     * @param entries (包名 to 显示名)。缺显示名时退回包名的最后一段 ——
     *   整串 `com.example.foo` 在面板上比 `foo` 难认得多。
     */
    fun apps(entries: List<Pair<String, String>>): List<MentionIndex.Item> =
        entries.mapNotNull { (pkg, label) ->
            val name = pkg.trim()
            if (name.isEmpty()) return@mapNotNull null
            MentionIndex.Item(
                key = name,
                label = label.trim().takeIf { it.isNotEmpty() } ?: name.substringAfterLast('.'),
                group = MentionIndex.Group.APP,
            )
        }.distinctBy { it.key }

    /** 截到 [MEMORY_LABEL_MAX]，超出补省略号。 */
    fun clip(text: String): String {
        val t = text.trim()
        if (t.length <= MEMORY_LABEL_MAX) return t
        return t.take(MEMORY_LABEL_MAX - 1) + "\u2026"
    }

    /** 只留末尾 [FILE_TAIL_SEGMENTS] 段。 */
    fun tail(path: String): String {
        val parts = path.trim().trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return path.trim()
        return parts.takeLast(FILE_TAIL_SEGMENTS).joinToString("/")
    }
}
