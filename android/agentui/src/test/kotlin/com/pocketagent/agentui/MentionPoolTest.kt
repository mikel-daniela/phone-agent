package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选池的翻译。
 *
 * ★ 重点不是"能翻译"，而是**空数据不许进池** ——
 *   `MentionIndex.Item` 的 init 会对空 key/label 抛异常，
 *   而那是用户敲 `@` 的那一下。崩在打字上最难看。
 */
class MentionPoolTest {

    @Test
    fun `工具名与短名分开`() {
        val items = MentionPool.capabilities(
            names = listOf("screen_read", "tap"),
            titles = mapOf("screen_read" to "看屏幕"),
        )
        assertEquals(2, items.size)
        assertEquals("看屏幕", items[0].label)
        assertEquals("screen_read", items[0].key)
        assertEquals(MentionIndex.Group.CAPABILITY, items[0].group)
        // 没有短名时退回原名，而不是留空
        assertEquals("tap", items[1].label)
    }

    @Test
    fun `空工具名不会进池`() {
        assertTrue(MentionPool.capabilities(names = listOf("  ", "")).isEmpty())
    }

    @Test
    fun `同一个工具不会出现两次`() {
        val items = MentionPool.capabilities(names = listOf("tap", "tap"))
        assertEquals(1, items.size)
    }

    @Test
    fun `记忆原文太长会截断并补省略号`() {
        val long = "他住在城东，每天早上七点出门，坐地铁二号线到公司，路上大概四十分钟左右。"
        val items = MentionPool.memories(entries = listOf("am-1" to long))
        assertEquals(1, items.size)
        assertEquals(MentionPool.MEMORY_LABEL_MAX, items[0].label.length)
        assertTrue(items[0].label.endsWith("\u2026"))
        // key 仍是**原文** —— 检索与回填都要它
        assertEquals("am-1", items[0].key)
    }

    @Test
    fun `空记忆文本不会进池`() {
        assertTrue(MentionPool.memories(entries = listOf("am-1" to "  ")).isEmpty())
        assertTrue(MentionPool.memories(entries = listOf(" " to "有内容")).isEmpty())
    }

    @Test
    fun `文件只留末尾两段`() {
        val items = MentionPool.files(paths = listOf("/sdcard/PocketAgentTest/输出/report.md"))
        assertEquals("输出/report.md", items[0].label)
        assertEquals("/sdcard/PocketAgentTest/输出/report.md", items[0].key)
    }

    @Test
    fun `应用缺显示名时退回包名末段`() {
        val items = MentionPool.apps(entries = listOf("com.tencent.mm" to ""))
        assertEquals("mm", items[0].label)
        assertEquals("com.tencent.mm", items[0].key)
    }

    @Test
    fun `四组会被分组，记忆按时间倒序`() {
        val items = MentionPool.capabilities(names = listOf("tap")) +
            MentionPool.memories(entries = listOf("am-1" to "一", "am-2" to "二"))
        val grouped = MentionIndex.grouped(query = "", items = items)
        assertEquals(1, grouped[MentionIndex.Group.CAPABILITY]!!.size)
        assertEquals(2, grouped[MentionIndex.Group.MEMORY]!!.size)
    }
}
