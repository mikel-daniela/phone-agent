package com.pocketagent.skilllogic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词里的两段。
 *
 * ★ 盯的是预算与顺序：技能正文再长也不能把人设挤掉，清单再长也要说清"还有几条"。
 */
class SkillPromptTest {

    private fun fake(index: Int, chars: Int = 40): Skill = Skill(
        id = "s$index",
        name = "技能$index",
        summary = "说".repeat(chars),
        body = "正文$index",
        triggers = listOf("触发$index"),
    )

    @Test
    fun `没有技能时两段都是空串`() {
        assertEquals("", SkillPrompt.listSection(emptyList()))
        assertEquals(
            "",
            SkillPrompt.sectionFor(emptyList(), picked = null, matched = null),
        )
    }

    @Test
    fun `目录段列出名字与摘要，但不含正文`() {
        val section = SkillPrompt.listSection(BuiltinSkills.all)
        assertThat(section).contains("## 你能用的工作技能")
        for (skill in BuiltinSkills.all) {
            assertThat(section).contains(skill.name)
        }
        assertThat(section).doesNotContain(BuiltinSkills.all.first().body)
    }

    @Test
    fun `目录超预算时整行丢弃并说明还有几条`() {
        val many = (0 until 40).map { fake(it) }
        val section = SkillPrompt.listSection(many)
        assertTrue("目录段超预算：${section.length}", section.length <= SkillPrompt.MAX_LIST_CHARS + 80)
        assertThat(section).contains("还有")
        assertThat(section).contains("条没列出来")
    }

    @Test
    fun `点名时的命中段带上正文`() {
        val deck = BuiltinSkills.byId("deck")!!
        val section = SkillPrompt.activeSection(deck, SkillPrompt.How.PICKED)
        assertThat(section).contains("这一轮按「做演示文稿」做")
        assertThat(section).contains("标题写结论")
    }

    @Test
    fun `判出来的命中段允许模型忽略`() {
        val deck = BuiltinSkills.byId("deck")!!
        val section = SkillPrompt.activeSection(deck, SkillPrompt.How.MATCHED)
        assertThat(section).contains("如果明显不相关，忽略这一段")
    }

    @Test
    fun `两段都提醒别改变说话方式`() {
        val deck = BuiltinSkills.byId("deck")!!
        for (how in SkillPrompt.How.values()) {
            assertThat(SkillPrompt.activeSection(deck, how)).contains("不改变你说话的方式")
        }
    }

    @Test
    fun `点名优先于自动判断`() {
        val deck = BuiltinSkills.byId("deck")!!
        val doc = BuiltinSkills.byId("doc")!!
        val section = SkillPrompt.sectionFor(BuiltinSkills.all, picked = deck, matched = doc)
        assertThat(section).contains("按「做演示文稿」做")
        assertThat(section).doesNotContain("按「写文档报告」做")
    }

    @Test
    fun `只自动判断时用判断的那条`() {
        val doc = BuiltinSkills.byId("doc")!!
        val section = SkillPrompt.sectionFor(BuiltinSkills.all, picked = null, matched = doc)
        assertThat(section).contains("写文档报告")
    }
}
