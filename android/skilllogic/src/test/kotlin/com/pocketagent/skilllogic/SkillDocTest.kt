package com.pocketagent.skilllogic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SKILL.md` 解析。
 *
 * ★ 这批测试盯的不是"能解析好文件"，而是**坏文件会怎样** ——
 *   导入界面要靠这里的 reason 告诉用户错在哪，而不是崩。
 */
class SkillDocTest {

    private fun ok(text: String, origin: String = ""): Skill {
        val r = SkillDoc.parse(text, origin)
        assertTrue("期望解析成功，实际是 $r", r is SkillDoc.Result.Ok)
        return (r as SkillDoc.Result.Ok).skill
    }

    private fun bad(text: String): String {
        val r = SkillDoc.parse(text)
        assertTrue("期望解析失败，实际是 $r", r is SkillDoc.Result.Bad)
        return (r as SkillDoc.Result.Bad).reason
    }

    @Test
    fun `标准技能文件能解析出四样东西`() {
        val skill = ok(
            """
            ---
            name: ppt-deck
            description: 对外汇报要用的材料
            triggers: ppt, 汇报, 演示
            ---
            先定一句主张，再排页。
            """.trimIndent(),
        )
        assertEquals("ppt-deck", skill.id)
        assertEquals("ppt-deck", skill.name)
        assertEquals("对外汇报要用的材料", skill.summary)
        assertEquals("先定一句主张，再排页。", skill.body)
        assertEquals(listOf("ppt", "汇报", "演示"), skill.triggers)
        assertEquals(SkillSource.IMPORTED, skill.source)
    }

    @Test
    fun `中文名字会退回文件名作为 id`() {
        val skill = ok(
            """
            ---
            name: 做演示文稿
            description: 对外汇报
            ---
            正文。
            """.trimIndent(),
            origin = "deck-maker.md",
        )
        assertEquals("deck-maker", skill.id)
        assertEquals("做演示文稿", skill.name)
    }

    @Test
    fun `名字和文件名都是中文时用哈希兜底，不是统统叫 skill`() {
        val text = "---\nname: 做表\ndescription: 整理数据\n---\n正文。"
        val s1 = ok(text, origin = "我的技能.md")
        val s2 = ok(text, origin = "另一个.md")
        assertTrue("id 应带哈希尾巴，实际 ${s1.id}", s1.id.startsWith("skill-"))
        assertEquals("同一份文件反复导入必须得到同一个 id", s1.id, s2.id)
    }

    @Test
    fun `没有 front matter 会给出能照做的提示`() {
        val reason = bad("这就是一段普通的 markdown，没有头部。")
        assertThat(reason).contains("第一行必须是三个短横线")
    }

    @Test
    fun `front matter 没闭合时说明是哪里缺了`() {
        val reason = bad("---\nname: x\ndescription: y\n正文没有闭合")
        assertThat(reason).contains("找不到结束的")
    }

    @Test
    fun `缺 description 会拒绝，因为模型没法判断什么时候用`() {
        val reason = bad("---\nname: x\n---\n正文。")
        assertThat(reason).contains("缺 `description`")
    }

    @Test
    fun `缺 name 会拒绝`() {
        val reason = bad("---\ndescription: y\n---\n正文。")
        assertThat(reason).contains("缺 `name`")
    }

    @Test
    fun `没有正文会拒绝`() {
        val reason = bad("---\nname: x\ndescription: y\n---\n   \n")
        assertThat(reason).contains("没有正文")
    }

    @Test
    fun `空文件给出空文件提示`() {
        assertThat(bad("   \n\n")).contains("空的")
    }

    @Test
    fun `带 BOM 与 CRLF 的 Windows 文件也能读`() {
        val text = "\uFEFF---\r\nname: win-file\r\ndescription: 记事本存的\r\n---\r\n正文第一行\r\n第二行\r\n"
        val skill = ok(text, origin = "x.md")
        assertEquals("win-file", skill.id)
        assertEquals("正文第一行\n第二行", skill.body)
    }

    @Test
    fun `正文太长会被截断并留下说明`() {
        val body = "规则。".repeat(2000)
        val skill = ok("---\nname: long\ndescription: 长文\n---\n$body", origin = "l.md")
        assertTrue("截断后仍超上限：${skill.body.length}", skill.body.length <= Skill.MAX_BODY_CHARS + 32)
        assertThat(skill.body).contains("太长，后面被截掉了")
    }

    @Test
    fun `摘要过长会被裁到上限`() {
        val skill = ok("---\nname: xx\ndescription: ${"很长的说明".repeat(30)}\n---\n正文。")
        assertEquals(Skill.MAX_SUMMARY_CHARS, skill.summary.length)
    }

    @Test
    fun `triggers 支持方括号与顿号`() {
        val a = ok("---\nname: aa\ndescription: d\ntriggers: [ppt, 汇报]\n---\n正文。")
        assertEquals(listOf("ppt", "汇报"), a.triggers)
        val b = ok("---\nname: bb\ndescription: d\ntriggers: 表格、统计\n---\n正文。")
        assertEquals(listOf("表格", "统计"), b.triggers)
    }

    @Test
    fun `title 可以当 name 的别名`() {
        val skill = ok("---\ntitle: 排期\ndescription: 项目计划\n---\n正文。")
        assertEquals("排期", skill.name)
    }
}
