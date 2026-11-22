package com.pocketagent.memorylogic

/**
 * **把几个原子库当成一个来读** —— 记忆互通用的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-21：用户要求"三个场景的关键记忆应该互通"
 * ═══════════════════════════════════════════════════════════════
 *
 * 在这之前是两个文件、两条读路径：
 *
 * ```
 *   角色扮演 / 工作台 → atomStore        （一个文件）
 *   在场              → lifeformAtomStore（另一个文件）
 * ```
 *
 * 后果很具体：在「在场」里聊了半天"他最近在为面试焦虑"，切回角色聊天，
 * 她**完全不知道这件事**。用户的原话是"这些应该互通"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 只合并**读**，不合并写
 * ═══════════════════════════════════════════════════════════════
 *
 * 写入仍然各归各的库（[primary]）：这样"这一段对话是在哪个场景里说的"
 * 这个事实不会丢，将来要按场景区分权重 / 做清理也还有依据。
 *
 * ⚠️ 合并的是**全部**记忆，不是挑"关键的"几条：
 *    挑哪几条算"关键"需要一条判断规则，而任何这样的规则都会在
 *    "他随口提了一句"和"他很在意这件事"之间判错。
 *    读的时候按相关度排序（`MemoryRecall.rank`）已经把噪音压下去了。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 两个库的 id 命名空间是**同一个**
 * ═══════════════════════════════════════════════════════════════
 *
 * `Atom.idOf(kind, text)` 只由类别与文本决定，所以同一句话在两个场景里
 * 被各抽一次会得到**同一个 id**。合并时走 `Atom.merge`（置信取高、
 * 溯源并集、时间取更早）—— 这正是我们要的效果：两条变一条，且证据更全。
 */
class CompositeAtomStore(
    /** 写入落在这个库上。读的时候它排在最前（同 id 时它的字段优先）。 */
    private val primary: AtomStore,
    /** 只读的其它库。 */
    private val others: List<AtomStore> = emptyList(),
) : AtomStore {

    /** 全部库（primary 在最前）。 */
    private fun all(): List<AtomStore> = listOf(primary) + others

    override fun loadAll(): List<Atom> {
        val byId = LinkedHashMap<String, Atom>()
        for (store in all()) {
            val atoms = runCatching { store.loadAll() }.getOrElse { emptyList() }
            for (a in atoms) {
                val old = byId[a.id]
                // ⚠️ `merge` 只能合并同一条 —— id 相同才进来，安全。
                byId[a.id] = if (old == null) a else Atom.merge(old, a)
            }
        }
        return byId.values.toList()
    }

    /**
     * 整表覆盖**只写 primary**。
     *
     * ⚠️ 绝不能把 `loadAll()` 的合并结果写回 primary ——
     *    那会把别的库里的记忆复制过来，同一条记忆于是有两个文件各存一份，
     *    之后删掉原库里的那条，副本还在（"我明明删了它还记得"）。
     */
    override fun replaceAll(atoms: List<Atom>) {
        primary.replaceAll(atoms)
    }

    /**
     * 并入新原子：写 primary，返回**合并后**的全表（含其它库）。
     *
     * ⚠️ 返回值必须是全表而不是 primary 的内容 —— 调用方通常拿它去刷缓存，
     *    只给一半会让刚读到的旧记忆当场消失。
     */
    override fun mergeIn(incoming: List<Atom>): List<Atom> {
        primary.mergeIn(incoming)
        return loadAll()
    }
}
