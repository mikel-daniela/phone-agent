package com.pocketagent.skilllogic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/` 触发。
 *
 * ★ 与 `MentionTriggerTest` 同一套判据、同一批边界（邮箱/路径/空格）。
 *   两条线必须行为一致，否则用户在 `@` 与 `/` 之间会感觉"这个面板时灵时不灵"。
 */
class SlashTriggerTest {

    @Test
    fun `开头一个斜杠就开面板`() {
        val active = SlashTrigger.active("/")
        assertNotNull(active)
        assertEquals(0, active!!.start)
        assertEquals("", active.query)
    }

    @Test
    fun `斜杠后面打的就是检索词`() {
        val active = SlashTrigger.active("/pp")!!
        assertEquals("pp", active.query)
    }

    @Test
    fun `空格之后打斜杠也算`() {
        val active = SlashTrigger.active("帮我 /ppt")!!
        assertEquals(3, active.start)
        assertEquals("ppt", active.query)
    }

    @Test
    fun `打完空格就当挑完了`() {
        assertNull(SlashTrigger.active("/做演示文稿 帮我做个季度汇报"))
    }

    @Test
    fun `网址里的斜杠不算`() {
        assertNull(SlashTrigger.active("看这个 http://example.com"))
    }

    @Test
    fun `路径里的斜杠不算`() {
        assertNull(SlashTrigger.active("读一下 docs/readme.md"))
    }

    @Test
    fun `两个斜杠是命令行，不弹面板`() {
        assertNull(SlashTrigger.active("//rm -rf"))
    }

    @Test
    fun `没有斜杠就没有面板`() {
        assertNull(SlashTrigger.active("帮我做个 ppt"))
        assertNull(SlashTrigger.active(""))
    }

    @Test
    fun `选中之后插的是技能名并补空格`() {
        val active = SlashTrigger.active("/pp")!!
        assertEquals("/做演示文稿 ", SlashTrigger.replace("/pp", active, "做演示文稿"))
    }

    @Test
    fun `选中之后面板立刻收起`() {
        val active = SlashTrigger.active("/pp")!!
        val replaced = SlashTrigger.replace("/pp", active, "做演示文稿")
        assertNull("补了空格之后不该再判成正在输入", SlashTrigger.active(replaced))
    }

    @Test
    fun `选中之后还能接着打`() {
        val active = SlashTrigger.active("帮我 /pp")!!
        assertEquals("帮我 /做演示文稿 ", SlashTrigger.replace("帮我 /pp", active, "做演示文稿"))
    }

    @Test
    fun `空名字原样返回`() {
        val active = SlashTrigger.active("/pp")!!
        assertEquals("/pp", SlashTrigger.replace("/pp", active, ""))
    }

    @Test
    fun `开头的技能名能被解析出来，正文去掉前缀`() {
        val pick = SlashTrigger.resolve("/做演示文稿 帮我做一份季度汇报")
        assertNotNull(pick)
        assertEquals("deck", pick!!.skill.id)
        assertEquals("帮我做一份季度汇报", pick.text)
    }

    @Test
    fun `也认英文 id`() {
        val pick = SlashTrigger.resolve("/deck 讲一下这季度")
        assertEquals("deck", pick!!.skill.id)
        assertEquals("讲一下这季度", pick.text)
    }

    @Test
    fun `前缀后面没有空格也能解析`() {
        val pick = SlashTrigger.resolve("/deck讲一下")
        assertNotNull(pick)
        assertEquals("deck", pick!!.skill.id)
    }

    @Test
    fun `不认识的技能名不算点名`() {
        assertNull(SlashTrigger.resolve("/不存在的技能 帮我做点什么"))
        assertNull(SlashTrigger.resolve("没有斜杠"))
        assertNull(SlashTrigger.resolve("/"))
    }

    @Test
    fun `只点名不写字时正文是空的`() {
        val pick = SlashTrigger.resolve("/做演示文稿")
        assertNotNull(pick)
        assertEquals("", pick!!.text)
    }

    @Test
    fun `中文名字里的冒号不会被当成前缀分隔`() {
        val skills = BuiltinSkills.all
        val names = skills.map { it.name }
        assertThat(names).containsNoDuplicates()
    }
}
