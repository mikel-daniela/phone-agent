package com.pocketagent.skilllogic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自然语言命中。
 *
 * ★ 两条方向相反的用例同样重要：
 *   · 「做个 ppt 汇报」必须中（漏了技能就等于没做这个功能）
 *   · 「今天天气不错」必须不中（中了就是每条消息都塞一段技能正文）
 */
class SkillMatchTest {

    @Test
    fun `做个 ppt 会命中演示文稿`() {
        val hit = SkillMatch.best("帮我做个 ppt，讲一下这季度")
        assertNotNull(hit)
        assertEquals("deck", hit!!.skill.id)
    }

    @Test
    fun `写周报会命中文档`() {
        assertEquals("doc", SkillMatch.best("帮我写个周报，这周都在修 bug")!!.skill.id)
    }

    @Test
    fun `整理成表格会命中表格`() {
        assertEquals("sheet", SkillMatch.best("把这些订单整理成表格")!!.skill.id)
    }

    @Test
    fun `选型对比会命中调研`() {
        assertEquals("research", SkillMatch.best("帮我做个选型对比，A 和 B 买哪个")!!.skill.id)
    }

    @Test
    fun `会议纪要不会被泛化成写文档`() {
        // ★ 这一条钉的是"泛动词权重不能压过名词"：以前 doc 收了「写一份」，
        //   于是「写一份会议纪要」判成 doc。去掉泛动词后该是 minutes。
        assertEquals("minutes", SkillMatch.best("写一份会议纪要")!!.skill.id)
    }

    @Test
    fun `闲聊不触发任何技能`() {
        assertNull(SkillMatch.best("今天天气不错，出去走走"))
        assertNull(SkillMatch.best(""))
        assertNull(SkillMatch.best("   "))
    }

    @Test
    fun `两个技能分数接近时不给结论`() {
        val body = "正文"
        val skills = listOf(
            Skill("alpha", "甲", "说明", body, listOf("汇报")),
            Skill("beta", "乙", "说明", body, listOf("汇报")),
        )
        assertEquals(2, SkillMatch.rank("做汇报", skills).size)
        assertNull(SkillMatch.confident("做汇报", skills))
    }

    @Test
    fun `分数拉开时给出结论`() {
        val body = "正文"
        val skills = listOf(
            Skill("alpha", "甲", "说明", body, listOf("汇报", "幻灯片", "演示")),
            Skill("beta", "乙", "说明", body, listOf("汇报")),
        )
        assertEquals("alpha", SkillMatch.confident("做个幻灯片汇报", skills)!!.skill.id)
    }

    @Test
    fun `命中词会如实记录，便于解释为什么选它`() {
        val hit = SkillMatch.best("做个 ppt 汇报")!!
        assertThat(hit.hits).contains("ppt")
        assertThat(hit.score).isAtLeast(SkillMatch.MIN_SCORE)
    }

    @Test
    fun `英文触发词不区分大小写`() {
        assertEquals("deck", SkillMatch.best("make me a PPT deck")!!.skill.id)
    }

    @Test
    fun `空技能表不会崩`() {
        assertEquals(emptyList<SkillMatch.Hit>(), SkillMatch.rank("做个 ppt", emptyList()))
        assertNull(SkillMatch.best("做个 ppt", emptyList()))
    }

    @Test
    fun `同分时保持原始顺序，不按 id 排`() {
        val body = "正文"
        val skills = listOf(
            Skill("zzz", "甲", "说明", body, listOf("汇报")),
            Skill("aaa", "乙", "说明", body, listOf("汇报")),
        )
        assertEquals(listOf("zzz", "aaa"), SkillMatch.rank("汇报", skills).map { it.skill.id })
    }
}
