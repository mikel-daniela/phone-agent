package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音标签 → 厂商能吃的东西。
 *
 * 这份测试守的是项目里最贵的一条教训：**表达不出来必须说出来**。
 * 静默降级听起来只是"声音平了一点"，用户会以为是模型不行，
 * 然后去换厂商 —— 真正的问题永远查不到。
 */
class VoiceTagRendererTest {

    private fun placements(vararg pairs: Pair<VoiceTag, Int>): List<PlacedTag> =
        pairs.map { PlacedTag(it.first, it.second) }

    // ── 一、选档 ────────────────────────────────────────────────

    @Test
    fun `支持指令时选指令档`() {
        val caps = VoiceCapabilities(instructions = true, ssml = true, inlineTokens = setOf("[uv_break]"))

        assertEquals(TtsRenderMode.INSTRUCTIONS, VoiceTagRenderer.chooseMode(caps))
    }

    @Test
    fun `不支持指令但支持 SSML 时选 SSML`() {
        val caps = VoiceCapabilities(ssml = true, inlineTokens = setOf("[uv_break]"))

        assertEquals(TtsRenderMode.SSML, VoiceTagRenderer.chooseMode(caps))
    }

    @Test
    fun `只认内嵌 token 时选内嵌档`() {
        val caps = VoiceCapabilities(inlineTokens = setOf("[uv_break]"))

        assertEquals(TtsRenderMode.INLINE_TAG, VoiceTagRenderer.chooseMode(caps))
    }

    @Test
    fun `什么都不支持时落到纯文本`() {
        assertEquals(TtsRenderMode.PLAIN, VoiceTagRenderer.chooseMode(VoiceCapabilities()))
        assertTrue(VoiceCapabilities().isPlainOnly)
    }

    // ── 二、指令档 ──────────────────────────────────────────────

    @Test
    fun `指令档正文保持纯净`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了，随你吧",
            tags = listOf(VoiceTag.SIGH, VoiceTag.SAD),
            placements = placements(VoiceTag.SIGH to 0, VoiceTag.SAD to 0),
            capabilities = VoiceCapabilities(instructions = true),
        )

        assertEquals("算了，随你吧", render.text)
        assertEquals(TtsRenderMode.INSTRUCTIONS, render.mode)
    }

    @Test
    fun `指令档把叹气写进说明`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SIGH),
            placements = placements(VoiceTag.SIGH to 0),
            capabilities = VoiceCapabilities(instructions = true),
        )

        assertTrue(render.instructions!!.contains("sigh"))
    }

    @Test
    fun `指令档把情绪写进说明`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SAD),
            placements = placements(VoiceTag.SAD to 0),
            capabilities = VoiceCapabilities(instructions = true),
        )

        assertTrue(render.instructions!!.contains("sad"))
    }

    @Test
    fun `没有标签时不生成说明`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(instructions = true),
        )

        assertNull(render.instructions)
        assertFalse(render.isDegraded)
    }

    @Test
    fun `同一个标签出现两次时说明里带次数`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了算了",
            tags = listOf(VoiceTag.SIGH, VoiceTag.SIGH),
            placements = placements(VoiceTag.SIGH to 0, VoiceTag.SIGH to 2),
            capabilities = VoiceCapabilities(instructions = true),
        )

        assertTrue(render.instructions!!.contains("x2"))
    }

    @Test
    fun `指令档一个标签都不丢`() {
        val tags = listOf(VoiceTag.SIGH, VoiceTag.WHISPER, VoiceTag.SAD)

        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = tags,
            placements = placements(*tags.map { it to 0 }.toTypedArray()),
            capabilities = VoiceCapabilities(instructions = true),
        )

        assertTrue(render.droppedTags.isEmpty())
    }

    // ── 三、SSML 档 ─────────────────────────────────────────────

    @Test
    fun `SSML 档带 speak 外壳`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertEquals("<speak>算了</speak>", render.text)
    }

    @Test
    fun `SSML 档把停顿插在标签位置`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了随你",
            tags = listOf(VoiceTag.PAUSE),
            placements = placements(VoiceTag.PAUSE to 2),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertEquals("<speak>算了<break time=\"350ms\"/>随你</speak>", render.text)
    }

    @Test
    fun `SSML 档必须转义正文里的尖括号和与号`() {
        val render = VoiceTagRenderer.render(
            spoken = "1 < 2 & 3 > 2",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertTrue(render.text.contains("&lt;"))
        assertTrue(render.text.contains("&amp;"))
        assertTrue(render.text.contains("&gt;"))
    }

    @Test
    fun `SSML 转义不会二次转义`() {
        val render = VoiceTagRenderer.render(
            spoken = "a<b",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertFalse(render.text.contains("&amp;lt;"))
    }

    @Test
    fun `SSML 表达不了的标签要记账`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SIGH),
            placements = placements(VoiceTag.SIGH to 0),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertEquals(listOf(VoiceTag.SIGH), render.droppedTags)
        assertTrue(render.isDegraded)
    }

    @Test
    fun `长停顿用更长的 break`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了随你",
            tags = listOf(VoiceTag.LONG_PAUSE),
            placements = placements(VoiceTag.LONG_PAUSE to 2),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertTrue(render.text.contains("900ms"))
    }

    // ── 四、内嵌档 ──────────────────────────────────────────────

    @Test
    fun `内嵌档把 token 插在标签位置`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了随你",
            tags = listOf(VoiceTag.PAUSE),
            placements = placements(VoiceTag.PAUSE to 2),
            capabilities = VoiceCapabilities(inlineTokens = setOf("[uv_break]")),
        )

        assertEquals("算了[uv_break]随你", render.text)
    }

    @Test
    fun `内嵌档多个标签从后往前插位置不会错`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了随你",
            tags = listOf(VoiceTag.PAUSE, VoiceTag.PAUSE),
            placements = placements(VoiceTag.PAUSE to 0, VoiceTag.PAUSE to 2),
            capabilities = VoiceCapabilities(inlineTokens = setOf("[uv_break]")),
        )

        assertEquals("[uv_break]算了[uv_break]随你", render.text)
    }

    @Test
    fun `内嵌档白名单外的标签要记账`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SIGH),
            placements = placements(VoiceTag.SIGH to 0),
            capabilities = VoiceCapabilities(inlineTokens = setOf("[uv_break]")),
        )

        assertEquals("算了", render.text)
        assertEquals(listOf(VoiceTag.SIGH), render.droppedTags)
    }

    // ── 五、纯文本档 ────────────────────────────────────────────

    @Test
    fun `纯文本档把所有标签记为丢弃`() {
        val tags = listOf(VoiceTag.SIGH, VoiceTag.SAD)

        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = tags,
            placements = placements(*tags.map { it to 0 }.toTypedArray()),
            capabilities = VoiceCapabilities(),
        )

        assertEquals(tags, render.droppedTags)
        assertTrue(render.isDegraded)
        assertEquals("算了", render.text)
    }

    // ── 六、长度上限 ────────────────────────────────────────────

    @Test
    fun `没给上限时不判超长`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(),
        )

        assertNull(render.limit)
        assertEquals(0, render.overflow)
        assertTrue(render.withinLimit)
    }

    @Test
    fun `超长时报出超了多少字`() {
        val render = VoiceTagRenderer.render(
            spoken = "一二三四五",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(maxCharacters = 3),
        )

        assertEquals(2, render.overflow)
        assertFalse(render.withinLimit)
    }

    @Test
    fun `按渲染之后的长度判超长而不是原文长度`() {
        // 原文 8 字，只在原文上量不会超；带上 SSML 外壳就超了。
        val render = VoiceTagRenderer.render(
            spoken = "一二三四五六七八",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(ssml = true, maxCharacters = 15),
        )

        // <speak> + 8 字 + </speak> = 7 + 8 + 8 = 23
        assertEquals(23, render.text.length)
        assertEquals(8, render.overflow)
    }

    @Test
    fun `刚好等于上限不算超`() {
        val render = VoiceTagRenderer.render(
            spoken = "一二三",
            tags = emptyList(),
            placements = emptyList(),
            capabilities = VoiceCapabilities(maxCharacters = 3),
        )

        assertTrue(render.withinLimit)
    }

    // ── 七、记账 ────────────────────────────────────────────────

    @Test
    fun `给了标签却没给位置也算丢弃`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SIGH),
            placements = emptyList(),
            capabilities = VoiceCapabilities(ssml = true),
        )

        assertEquals(listOf(VoiceTag.SIGH), render.droppedTags)
    }

    @Test
    fun `同一个标签不会在丢弃表里出现两次`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SIGH, VoiceTag.SIGH),
            placements = emptyList(),
            capabilities = VoiceCapabilities(),
        )

        assertEquals(listOf(VoiceTag.SIGH), render.droppedTags)
    }

    @Test
    fun `日志行带档位和丢弃数量`() {
        val render = VoiceTagRenderer.render(
            spoken = "算了",
            tags = listOf(VoiceTag.SIGH),
            placements = placements(VoiceTag.SIGH to 0),
            capabilities = VoiceCapabilities(),
        )

        assertTrue(render.logLine().contains("纯文本"))
        assertTrue(render.logLine().contains("1"))
    }

    // ── 八、按上限切段 ──────────────────────────────────────────

    @Test
    fun `没超上限时原样一段`() {
        assertEquals(listOf("算了"), VoiceTagRenderer.splitByLimit("算了", 10))
    }

    @Test
    fun `空文本切出零段`() {
        assertTrue(VoiceTagRenderer.splitByLimit("", 10).isEmpty())
    }

    @Test
    fun `超长文本按句子切成多段`() {
        val text = "一二三四五。六七八九十。"

        val pieces = VoiceTagRenderer.splitByLimit(text, 10)

        assertEquals(listOf("一二三四五。", "六七八九十。"), pieces)
    }

    @Test
    fun `每一段都不超过上限`() {
        val text = "第一句话。第二句话。第三句话。第四句话。"

        val pieces = VoiceTagRenderer.splitByLimit(text, 12)

        for (piece in pieces) {
            assertTrue("段落超长：$piece（${piece.length}）", piece.length <= 12)
        }
    }

    @Test
    fun `切段之后拼起来是原文`() {
        val text = "第一句话。第二句话。第三句话。"

        assertEquals(text, VoiceTagRenderer.splitByLimit(text, 12).joinToString(""))
    }

    @Test
    fun `没有标点的超长句子退回硬切`() {
        val text = "一".repeat(25)

        val pieces = VoiceTagRenderer.splitByLimit(text, 10)

        assertEquals(3, pieces.size)
        assertEquals(25, pieces.joinToString("").length)
    }

    @Test
    fun `上限非正数直接报错`() {
        val failure = runCatching { VoiceTagRenderer.splitByLimit("算了", 0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
