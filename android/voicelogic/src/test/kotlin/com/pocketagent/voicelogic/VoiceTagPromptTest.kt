package com.pocketagent.voicelogic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音标签的提示词。
 *
 * 这一份测的不是文案好不好听，是**词表有没有漏**：
 * 提示词里少一个词，模型就永远不会用那个词；
 * 多一个词，模型造出来的标签会被解析器当正文念出来。
 */
class VoiceTagPromptTest {

    private val section = VoiceTagPrompt.promptSection()

    @Test
    fun `每一个标签的中文名都出现在提示词里`() {
        val missing = VoiceTag.entries.filter { !section.contains(it.label) }

        assertTrue("这些标签没写进提示词：$missing", missing.isEmpty())
    }

    @Test
    fun `七个分类都出现在提示词里`() {
        for (kind in VoiceTagKind.entries) {
            assertTrue("少了分类：${kind.label}", section.contains(kind.label))
        }
    }

    @Test
    fun `提示词里给了正确的写法示例`() {
        assertTrue(section.contains("[叹气]"))
    }

    @Test
    fun `提示词说明了标签不会显示在屏幕上`() {
        // 不写这一句，模型会怕污染界面而不敢标。
        assertTrue(section.contains("不会出现在屏幕上"))
    }

    @Test
    fun `提示词限制了一轮的标签数量`() {
        assertTrue(section.contains("一两个"))
    }

    @Test
    fun `提示词没有用列表符号`() {
        val bulletLines = section.lines().filter { it.trimStart().startsWith("- ") || it.trimStart().startsWith("* ") }

        assertTrue("这些行带列表符号，会被照着学：$bulletLines", bulletLines.isEmpty())
    }

    @Test
    fun `提示词没有加粗标记`() {
        assertFalse(section.contains("**"))
    }

    @Test
    fun `提示词以小节标题开头`() {
        assertTrue(section.startsWith("## "))
    }

    @Test
    fun `词表摘要按分类给出数量`() {
        val summary = VoiceTagPrompt.vocabularySummary()

        assertTrue(summary.contains("情绪"))
        assertTrue(summary.contains("呼吸"))
        assertTrue(summary.contains("停顿"))
    }

    @Test
    fun `词表摘要与词表本身对得上`() {
        val summary = VoiceTagPrompt.vocabularySummary()

        val counted = VoiceTagKind.entries.sumOf { kind -> VoiceTag.entries.count { it.kind == kind } }
        val summed = Regex("""(\d+)""").findAll(summary).sumOf { it.groupValues[1].toInt() }

        assertTrue("摘要里的数加起来是 $summed，词表有 $counted 个", summed == counted)
    }
}
