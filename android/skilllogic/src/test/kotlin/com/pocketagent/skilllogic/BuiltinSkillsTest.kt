package com.pocketagent.skilllogic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内置技能本身的质量门槛。
 *
 * ★ 这些断言看起来像"格式检查"，但它们挡的是真实的返工：
 *   一条没有 `triggers` 的技能永远命中不了（等于没写），
 *   一条超过正文上限的技能会让清单里别的条目被裁掉。
 */
class BuiltinSkillsTest {

    @Test
    fun `内置技能覆盖八类工作场景`() {
        assertEquals(8, BuiltinSkills.all.size)
        assertEquals(
            listOf("deck", "doc", "sheet", "research", "minutes", "plan", "review", "mail"),
            BuiltinSkills.all.map { it.id },
        )
    }

    @Test
    fun `id 全部合法且不重复`() {
        assertThat(BuiltinSkills.all.map { it.id }).containsNoDuplicates()
        for (skill in BuiltinSkills.all) {
            assertThat(Skill.ID_PATTERN.matches(skill.id)).isTrue()
        }
    }

    @Test
    fun `每条都有触发词，否则永远命中不了`() {
        for (skill in BuiltinSkills.all) {
            assertThat(skill.triggers).isNotEmpty()
        }
    }

    @Test
    fun `正文都在上限之内，不会把别的段落挤掉`() {
        for (skill in BuiltinSkills.all) {
            assertThat(skill.body.length).isAtMost(Skill.MAX_BODY_CHARS)
        }
    }

    @Test
    fun `摘要都是一句话，清单不会被撑成一屏`() {
        for (skill in BuiltinSkills.all) {
            assertThat(skill.summary.length).isAtMost(Skill.MAX_SUMMARY_CHARS)
            assertThat(skill.summary).doesNotContain("\n")
        }
    }

    @Test
    fun `演示与文档都写了可执行的替代动作，而不是只说不要`() {
        val deck = BuiltinSkills.byId("deck")!!
        assertThat(deck.body).contains("标题写结论")
        assertThat(deck.body).contains("make_slides")
        val doc = BuiltinSkills.byId("doc")!!
        assertThat(doc.body).contains("结论写在第一段")
    }

    @Test
    fun `按 id 与按名字都能取到`() {
        assertNotNull(BuiltinSkills.byId("plan"))
        assertNotNull(BuiltinSkills.byName("排项目计划"))
        assertNull(BuiltinSkills.byId("PLAN"))
        assertNull(BuiltinSkills.byId("不存在的"))
    }

    @Test
    fun `每条技能的名字不会被别条技能误命中`() {
        for (skill in BuiltinSkills.all) {
            val hit = SkillMatch.best(skill.name, BuiltinSkills.all)
            assertEquals(
                "「${skill.name}」应该命中自己",
                skill.id,
                hit?.skill?.id,
            )
        }
    }
}
