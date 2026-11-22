package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompositeAtomStore] —— 三个场景的记忆互通。
 *
 * 背景（2026-11-21 用户要求）：在「在场」里聊过的事，切回角色聊天她完全不知道。
 * 原因是两个库、两条读路径。这里钉四件事：
 *
 * 1. **读**是合并的（另一库里的事看得见）
 * 2. **写**只落 primary（另一库不被污染）
 * 3. 同一条记忆在两个库各有一份时**合成一条**，置信取高、溯源并集、时间取更早
 * 4. `mergeIn` 返回的必须是**合并后的全表**（否则调用方刷新缓存时会把别的库的记忆刷没）
 */
class CompositeAtomStoreTest {

    private class FakeStore(initial: List<Atom> = emptyList()) : AtomStore {
        val atoms = LinkedHashMap<String, Atom>()

        init {
            for (a in initial) atoms[a.id] = a
        }

        var loads = 0
            private set

        override fun loadAll(): List<Atom> {
            loads += 1
            return atoms.values.toList()
        }

        override fun replaceAll(atoms: List<Atom>) {
            this.atoms.clear()
            for (a in atoms) this.atoms[a.id] = a
        }
    }

    private fun atom(
        text: String,
        kind: AtomKind = AtomKind.FACT,
        confidence: Double = 0.6,
        at: Long = 1_000L,
        turns: List<String> = listOf("t1"),
    ) = Atom(
        id = Atom.idOf(kind, text),
        kind = kind,
        text = text,
        sourceTurnIds = turns,
        confidence = confidence,
        at = at,
    )

    @Test
    fun `另一个库里的事也读得到`() {
        val roleplay = FakeStore(listOf(atom("他在准备面试")))
        val lifeform = FakeStore(listOf(atom("她喜欢下雨天")))
        val store = CompositeAtomStore(primary = roleplay, others = listOf(lifeform))

        val texts = store.loadAll().map { it.text }.toSet()
        assertEquals(setOf("他在准备面试", "她喜欢下雨天"), texts)
    }

    @Test
    fun `写入只落在 primary，不污染别的库`() {
        val roleplay = FakeStore()
        val lifeform = FakeStore()
        val store = CompositeAtomStore(primary = roleplay, others = listOf(lifeform))

        store.mergeIn(listOf(atom("他讨厌开会")))

        assertEquals(listOf("他讨厌开会"), roleplay.loadAll().map { it.text })
        assertTrue("别的库不该被写进任何东西", lifeform.loadAll().isEmpty())
    }

    @Test
    fun `同一个 id 的两条记忆合成一条，置信取高且溯源并集`() {
        val shared = atom("他在准备面试", confidence = 0.5, at = 2_000L, turns = listOf("t1"))
        val same = atom("他在准备面试", confidence = 0.9, at = 1_000L, turns = listOf("t2"))
        val store = CompositeAtomStore(
            primary = FakeStore(listOf(shared)),
            others = listOf(FakeStore(listOf(same))),
        )

        val merged = store.loadAll()
        assertEquals(1, merged.size)
        val one = merged.single()
        assertEquals(0.9, one.confidence, 1e-9)
        assertEquals(listOf("t1", "t2"), one.sourceTurnIds)
        assertEquals(1_000L, one.at)
    }

    @Test
    fun `mergeIn 返回的是合并后的全表，不是 primary 的内容`() {
        val primary = FakeStore()
        val other = FakeStore(listOf(atom("她怕打雷")))
        val store = CompositeAtomStore(primary = primary, others = listOf(other))

        val after = store.mergeIn(listOf(atom("他今天很累")))
        val texts = after.map { it.text }.toSet()
        // 只给一半会让调用方刷缓存时把"她怕打雷"刷没
        assertEquals(setOf("他今天很累", "她怕打雷"), texts)
    }

    @Test
    fun `replaceAll 不会把别的库复制进 primary`() {
        val primary = FakeStore()
        val other = FakeStore(listOf(atom("她怕打雷")))
        val store = CompositeAtomStore(primary = primary, others = listOf(other))

        // 调用方拿到的是合并视图，原样写回会复制
        store.replaceAll(store.loadAll())

        assertEquals(listOf("她怕打雷"), primary.loadAll().map { it.text })
    }

    @Test
    fun `某个库读失败时按空处理，不影响另一个库`() {
        val broken = object : AtomStore {
            override fun loadAll(): List<Atom> = error("文件坏了")
            override fun replaceAll(atoms: List<Atom>) = Unit
        }
        val store = CompositeAtomStore(
            primary = broken,
            others = listOf(FakeStore(listOf(atom("他在准备面试")))),
        )

        assertEquals(listOf("他在准备面试"), store.loadAll().map { it.text })
    }
}
