package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `@` 面板的触发判定。
 *
 * ★ 这些用例防的是**用户当场就能遇到**的那几种：
 *   邮箱地址被当成提及、打完空格面板还挂着、点上候选之后文本变成两截。
 */
class MentionTriggerTest {

    @Test
    fun `末尾打一个 at 就是正在提及`() {
        val a = MentionTrigger.active("帮我看看 @")
        assertEquals(5, a!!.start)
        assertEquals("", a.query)
    }

    @Test
    fun `at 后面已经打的内容就是检索词`() {
        val a = MentionTrigger.active("帮我 @屏幕")
        assertEquals("屏幕", a!!.query)
    }

    @Test
    fun `打完空格就当提及结束`() {
        assertNull(MentionTrigger.active("@屏幕 帮我看看"))
    }

    @Test
    fun `邮箱不算提及`() {
        assertNull(MentionTrigger.active("发给 a@b.com"))
    }

    @Test
    fun `句首的 at 是提及`() {
        val a = MentionTrigger.active("@记")
        assertEquals(0, a!!.start)
        assertEquals("记", a.query)
    }

    @Test
    fun `没有 at 就没有面板`() {
        assertNull(MentionTrigger.active("今天天气不错"))
    }

    @Test
    fun `多个 at 时只看最后一个`() {
        val a = MentionTrigger.active("@屏幕 然后 @记")
        assertEquals(7, a!!.start)
        assertEquals("记", a.query)
    }

    @Test
    fun `选中之后 at 与检索词一起被换掉`() {
        val text = "帮我 @屏幕"
        val a = MentionTrigger.active(text)!!
        assertEquals("帮我 读取屏幕 ", MentionTrigger.replace(text, a, "读取屏幕"))
    }

    @Test
    fun `选中之后补一个空格，接着打不会粘在一起`() {
        val text = "@记"
        val a = MentionTrigger.active(text)!!
        val out = MentionTrigger.replace(text, a, "记得的事")
        assertEquals("记得的事 ", out)
        assertEquals("记得的事 还有", out + "还有")
    }

    @Test
    fun `空标签视为不换`() {
        val text = "帮我 @"
        val a = MentionTrigger.active(text)!!
        assertEquals(text, MentionTrigger.replace(text, a, "   "))
    }

    @Test
    fun `标签首尾空白会被清掉`() {
        val text = "@x"
        val a = MentionTrigger.active(text)!!
        assertEquals("读屏幕 ", MentionTrigger.replace(text, a, "  读屏幕  "))
    }
}
