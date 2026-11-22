package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音标签解析。
 *
 * 两件必须保住的事：
 *  1. 标签**不许漏到屏幕上**（气泡里出现方括号，她就变成念标注的机器了）；
 *  2. 不是标签的括号**不许被吃掉**（「（他犹豫了一下）」是正文）。
 */
class VoiceTagParserTest {

    // ── 一、基本剥离 ────────────────────────────────────────────

    @Test
    fun `英文方括号标签被剥掉`() {
        val parse = VoiceTagParser.split("嗯[sigh]算了")

        assertEquals("嗯算了", parse.spoken)
        assertEquals(listOf(VoiceTag.SIGH), parse.tags)
    }

    @Test
    fun `中文圆括号别名也认`() {
        val parse = VoiceTagParser.split("（叹气）算了，随你吧")

        assertEquals("算了，随你吧", parse.spoken)
        assertEquals(listOf(VoiceTag.SIGH), parse.tags)
    }

    @Test
    fun `半角圆括号认`() {
        val parse = VoiceTagParser.split("(叹气)算了")

        assertEquals("算了", parse.spoken)
        assertEquals(listOf(VoiceTag.SIGH), parse.tags)
    }

    @Test
    fun `星号包裹认`() {
        val parse = VoiceTagParser.split("*叹气*算了")

        assertEquals("算了", parse.spoken)
        assertEquals(listOf(VoiceTag.SIGH), parse.tags)
    }

    @Test
    fun `中英混写的标签都认`() {
        val parse = VoiceTagParser.split("[whisper]别出声")

        assertEquals("别出声", parse.spoken)
        assertEquals(listOf(VoiceTag.WHISPER), parse.tags)
    }

    @Test
    fun `标签带大小写和空白也能认`() {
        val parse = VoiceTagParser.split("[ SIGH ]算了")

        assertEquals("算了", parse.spoken)
        assertEquals(listOf(VoiceTag.SIGH), parse.tags)
    }

    // ── 二、不是标签的括号必须留下 ──────────────────────────────

    @Test
    fun `动作描写原样留在文本里`() {
        val raw = "（他犹豫了一下）算了"

        val parse = VoiceTagParser.split(raw)

        assertEquals(raw, parse.spoken)
        assertTrue(parse.tags.isEmpty())
    }

    @Test
    fun `超长的方括号不当标签`() {
        val raw = "[" + "一".repeat(25) + "]"

        val parse = VoiceTagParser.split(raw)

        assertEquals(raw, parse.spoken)
        assertTrue(parse.tags.isEmpty())
    }

    @Test
    fun `方括号里带空格不认`() {
        val raw = "[大 笑]"

        val parse = VoiceTagParser.split(raw)

        assertEquals(raw, parse.spoken)
        assertTrue(parse.tags.isEmpty())
    }

    @Test
    fun `跨行的方括号不认`() {
        val raw = "[叹\n气]"

        val parse = VoiceTagParser.split(raw)

        assertEquals(raw, parse.spoken)
        assertTrue(parse.tags.isEmpty())
    }

    // ── 三、文本干净度 ──────────────────────────────────────────

    @Test
    fun `剥完之后文本里不该再有标签字样`() {
        val parse = VoiceTagParser.split("嗯[叹气]算了[whisper]随你")

        assertFalse(parse.spoken.contains("叹气"))
        assertFalse(parse.spoken.contains("["))
    }

    @Test
    fun `重复空白被压成一个`() {
        val parse = VoiceTagParser.split("算了  [叹气]随你吧")

        // 原文两个空格，剥掉标签后压成一个。
        assertEquals("算了" + " " + "随你吧", parse.spoken)
    }

    @Test
    fun `首尾空白被去掉`() {
        val parse = VoiceTagParser.split("  [叹气]算了  ")

        assertEquals("算了", parse.spoken)
    }

    @Test
    fun `连续空行压成两个换行`() {
        val parse = VoiceTagParser.split("算了[叹气]\n\n\n随你")

        assertEquals("算了\n\n随你", parse.spoken)
    }

    @Test
    fun `英文里紧挨标签的两个空格被合并成一个`() {
        val parse = VoiceTagParser.split("I am [sigh] fine")

        // 标签两侧各有一个空格，剥掉之后不该剩两个。
        assertEquals("I am" + " " + "fine", parse.spoken)
    }

    @Test
    fun `strip 与 split 的文本一致`() {
        val raw = "嗯[叹气]算了"

        assertEquals(VoiceTagParser.split(raw).spoken, VoiceTagParser.strip(raw))
    }

    // ── 四、位置 ────────────────────────────────────────────────

    @Test
    fun `句首标签的位置是零`() {
        val parse = VoiceTagParser.split("[叹气]算了")

        assertEquals(0, parse.placements.single().at)
    }

    @Test
    fun `句中标签的位置跟着前面的字走`() {
        val parse = VoiceTagParser.split("算了[叹气]随你")

        assertEquals(2, parse.placements.single().at)
    }

    @Test
    fun `句尾标签的位置落在文本末尾`() {
        val parse = VoiceTagParser.split("算了[叹气]")

        assertEquals(2, parse.placements.single().at)
    }

    @Test
    fun `压空白之后位置仍然对得上`() {
        // 「算了」+两个空格被压成一个空格，标签应当落在 3 而不是 4。
        val parse = VoiceTagParser.split("算了  [叹气]随你")

        assertEquals(3, parse.placements.single().at)
        assertEquals('随', parse.spoken[parse.placements.single().at])
    }

    @Test
    fun `去掉开头空白之后位置整体前移`() {
        val parse = VoiceTagParser.split("\n[叹气]算了")

        assertEquals(0, parse.placements.single().at)
        assertEquals("算了", parse.spoken)
    }

    @Test
    fun `多个标签的位置按出现顺序递增`() {
        val parse = VoiceTagParser.split("[叹气]算了[轻笑]随你")

        assertEquals(listOf(VoiceTag.SIGH, VoiceTag.GIGGLE), parse.tags)
        assertEquals(2, parse.placements.size)
        assertEquals(0, parse.placements[0].at)
        assertEquals(2, parse.placements[1].at)
    }

    @Test
    fun `位置永远落在文本范围内`() {
        val parse = VoiceTagParser.split("算了[叹气]")

        for (placed in parse.placements) {
            assertTrue("位置越界：${placed.at}", placed.at in 0..parse.spoken.length)
        }
    }

    // ── 五、空与纯标签 ──────────────────────────────────────────

    @Test
    fun `空串解析成空`() {
        val parse = VoiceTagParser.split("")

        assertTrue(parse.isEmpty)
        assertFalse(parse.isSilentOnly)
    }

    @Test
    fun `只有标签没有字时标记为纯标签`() {
        val parse = VoiceTagParser.split("[叹气]")

        assertTrue(parse.isSilentOnly)
        assertFalse(parse.isEmpty)
        assertEquals(1, parse.tags.size)
    }

    @Test
    fun `只有空白不算纯标签`() {
        val parse = VoiceTagParser.split("   ")

        assertTrue(parse.isEmpty)
    }

    @Test
    fun `纯标签的文本为空串`() {
        assertEquals("", VoiceTagParser.strip("[叹气][轻笑]"))
    }

    // ── 六、流式过滤 ────────────────────────────────────────────

    @Test
    fun `流式下未闭合的方括号先扣住`() {
        val filter = StreamingVoiceTagFilter()

        assertEquals("", filter.feed("[叹"))
    }

    @Test
    fun `流式下标签闭合之后只放行干净的文本`() {
        val filter = StreamingVoiceTagFilter()

        assertEquals("", filter.feed("[叹"))
        assertEquals("算了", filter.feed("气]算了"))
        assertEquals(listOf(VoiceTag.SIGH), filter.tags())
    }

    @Test
    fun `流式下普通文字直接放行`() {
        val filter = StreamingVoiceTagFilter()

        assertEquals("算了", filter.feed("算了"))
    }

    @Test
    fun `流式下末尾没闭合的半截标签被丢掉`() {
        val filter = StreamingVoiceTagFilter()

        assertEquals("算了", filter.feed("算了[叹"))
        // 输出被掐断留下的半截壳，不该出现在屏幕上。
        assertEquals("", filter.flush())
    }

    @Test
    fun `流式下未闭合的普通括号最终会放出来`() {
        val filter = StreamingVoiceTagFilter(pendingMax = 4)

        // 「[」后面跟了 5 个字符还没闭合，超过 pendingMax，判定不是标签。
        val out = filter.feed("[一二三四五")

        assertEquals("[一二三四五", out)
    }

    @Test
    fun `流式下位置以整段为坐标系累计`() {
        val filter = StreamingVoiceTagFilter()

        filter.feed("算了")
        filter.feed("[叹气]")
        assertEquals(2, filter.placements().single().at)
    }

    @Test
    fun `流式下不认识的括号内容原样放出`() {
        val filter = StreamingVoiceTagFilter()

        assertEquals("（见附件）", filter.feed("（见附件）"))
        assertTrue(filter.tags().isEmpty())
    }

    @Test
    fun `流式下流式文本与整段解析结果一致`() {
        val filter = StreamingVoiceTagFilter()
        val streamed = StringBuilder()
        for (chunk in listOf("嗯", "[叹", "气]算", "了，", "随你吧")) {
            streamed.append(filter.feed(chunk))
        }
        streamed.append(filter.flush())

        assertEquals(VoiceTagParser.strip("嗯[叹气]算了，随你吧"), streamed.toString())
    }

    // ── 七、词表约束 ────────────────────────────────────────────

    @Test
    fun `标签词表能被中文别名反查`() {
        assertEquals(VoiceTag.SIGH, VoiceTag.of("叹气"))
        assertEquals(VoiceTag.SIGH, VoiceTag.of("sigh"))
        assertEquals(VoiceTag.LAUGH, VoiceTag.of("笑"))
        assertNull(VoiceTag.of("温柔而略带忧伤"))
    }

    @Test
    fun `整体基调取第一个情绪标签`() {
        val parse = VoiceTagParser.split("[轻笑]算了[难过]随你")

        assertEquals(VoiceTag.SAD, VoiceTag.dominantTone(parse.tags))
    }

    @Test
    fun `一条消息里有两个情绪时基调取前面的`() {
        // 先说笑再沉下来 —— 整条的调门必须一开始就定，
        // 让最后一句的难过决定整条，前面那句就念错了。
        val parse = VoiceTagParser.split("[开心]你来啦[难过]算了")

        assertEquals(listOf(VoiceTag.HAPPY, VoiceTag.SAD), parse.tags)
        assertEquals(VoiceTag.HAPPY, VoiceTag.dominantTone(parse.tags))
    }

    @Test
    fun `没有情绪标签时基调为空`() {
        val parse = VoiceTagParser.split("[耳语]别出声")

        assertNull(VoiceTag.dominantTone(parse.tags))
    }
}
