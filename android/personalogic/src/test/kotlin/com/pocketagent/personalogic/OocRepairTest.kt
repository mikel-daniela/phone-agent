package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OocRepair` 的离线验证。
 *
 * 两个测试各守一类错误：
 * - **该修的时候没修**：她明明刚说了「作为一个 AI…」，下一轮提示词却干干净净；
 * - **不该修的时候乱修**：一句正常的话被当成出戏，于是她开始莫名其妙地道歉。
 */
class OocRepairTest {

    private fun breakAt(turn: Int, kind: OocRepair.Kind) = OocRepair.Break(turn, kind)

    // ── 记录 / 窗口 ─────────────────────────────────────────────

    @Test
    fun `一条记录都没有的时候不修`() {
        val report = OocRepair.measure(emptyList(), turns = 5)
        assertFalse(report.isBreaking)
        assertFalse(OocRepair.shouldRepair(report))
        assertEquals("", OocRepair.repairSection(report))
        assertNull(report.worst)
    }

    @Test
    fun `最近三轮回过神来就要修`() {
        val report = OocRepair.measure(listOf(breakAt(4, OocRepair.Kind.IDENTITY)), turns = 5)
        assertTrue(report.isBreaking)
        assertTrue(OocRepair.shouldRepair(report))
        assertTrue(OocRepair.repairSection(report).contains("回到你自己"))
    }

    @Test
    fun `三窗口边界_刚好第三轮还算`() {
        // turns=5，窗口是 > 5-3 = >2 ⇒ 第 3 轮在内，第 2 轮在外。
        assertTrue(OocRepair.measure(listOf(breakAt(3, OocRepair.Kind.META)), 5).isBreaking)
        assertFalse(OocRepair.measure(listOf(breakAt(2, OocRepair.Kind.META)), 5).isBreaking)
    }

    @Test
    fun `很久以前的旧账不影响现在`() {
        val report = OocRepair.measure(
            listOf(breakAt(1, OocRepair.Kind.IDENTITY), breakAt(2, OocRepair.Kind.REFUSAL)),
            turns = 30,
        )
        assertFalse(report.isBreaking)
        assertEquals(2, report.breaks.size)
        assertTrue("占比要算得出来", (report.ratio ?: 0.0) > 0.0)
    }

    @Test
    fun `一轮都没聊的时候占比是 null 而不是零`() {
        assertNull(OocRepair.measure(emptyList(), turns = 0).ratio)
    }

    @Test
    fun `先说最重的那件_同样重就取最新的`() {
        val report = OocRepair.measure(
            listOf(
                breakAt(3, OocRepair.Kind.META),
                breakAt(4, OocRepair.Kind.REFUSAL),
                breakAt(5, OocRepair.Kind.IDENTITY),
            ),
            turns = 5,
        )
        assertEquals(OocRepair.Kind.IDENTITY, report.worst)

        val tie = OocRepair.measure(
            listOf(breakAt(3, OocRepair.Kind.META), breakAt(4, OocRepair.Kind.META)),
            turns = 4,
        )
        assertEquals("并列时取最新的那一类（同一类在这里看不出差别，但顺序要对）", 4, tie.recent.last().turn)
    }

    @Test
    fun `乱序喂进来也会按轮次排好`() {
        val report = OocRepair.measure(
            listOf(breakAt(5, OocRepair.Kind.META), breakAt(1, OocRepair.Kind.IDENTITY)),
            turns = 5,
        )
        assertEquals(listOf(1, 5), report.breaks.map { it.turn })
    }

    // ── 检测 ────────────────────────────────────────────────────

    @Test
    fun `身份否认最优先_哪怕同时像拒答`() {
        assertEquals(
            OocRepair.Kind.IDENTITY,
            OocRepair.detect("作为一个 AI 语言模型，我不能提供这个。", hardRefusal = true),
        )
    }

    @Test
    fun `只有硬拒答的时候算推掉活儿`() {
        assertEquals(
            OocRepair.Kind.REFUSAL,
            OocRepair.detect("这个我真帮不了。", hardRefusal = true),
        )
    }

    @Test
    fun `念设定算另一类`() {
        assertEquals(
            OocRepair.Kind.META,
            OocRepair.detect("按设定我不该说这个。", hardRefusal = false),
        )
    }

    @Test
    fun `正常回话不会被当成出戏`() {
        val ordinary = listOf(
            "行，我看下。",
            "也行吧。你说呢。",
            "弄好了，在图库里。",
            "大概吧，我也说不准。",
        )
        ordinary.forEach {
            assertNull("不该判成跑偏：$it", OocRepair.detect(it, hardRefusal = false))
        }
    }

    @Test
    fun `空回复不判`() {
        assertNull(OocRepair.detect("   ", hardRefusal = false))
    }

    // ── 修复段本身的文风 ────────────────────────────────────────

    private fun sampleSection(): String =
        OocRepair.repairSection(
            OocRepair.measure(listOf(breakAt(3, OocRepair.Kind.IDENTITY)), turns = 3),
        )

    @Test
    fun `修复段里没有列表符号和加粗`() {
        val section = sampleSection()
        assertFalse(section.contains("- "))
        assertFalse(section.contains("**"))
        assertFalse(section.contains("——"))
    }

    @Test
    fun `修复段里没有元话语`() {
        val section = sampleSection()
        listOf("下面", "以下", "上面", "本文", "本节").forEach {
            assertFalse("不该出现「$it」", section.contains(it))
        }
    }

    @Test
    fun `修复段过得了提示词文风体检`() {
        val report = PromptVoice.audit(sampleSection())
        assertTrue("体检不通过：${report.breakdown()}", report.isClean)
    }

    @Test
    fun `修复段不长`() {
        val section = sampleSection()
        assertTrue("修复段 ${section.length} 字，太长了", section.length <= OocRepair.MAX_SECTION_CHARS)
    }

    @Test
    fun `日志里不出现原文`() {
        val report = OocRepair.measure(listOf(breakAt(2, OocRepair.Kind.IDENTITY)), turns = 2)
        val line = report.logLine()
        assertFalse(line.contains("作为一个"))
        assertFalse(line.contains("语言模型"))
        assertTrue(line.contains("跑偏记录"))
    }

    @Test
    fun `不同种类会给不同的最后一句话`() {
        val identity = OocRepair.repairSection(
            OocRepair.measure(listOf(breakAt(1, OocRepair.Kind.IDENTITY)), 1),
        )
        val refusal = OocRepair.repairSection(
            OocRepair.measure(listOf(breakAt(1, OocRepair.Kind.REFUSAL)), 1),
        )
        assertTrue(identity.contains("不用跟人交代"))
        assertTrue(refusal.contains("别绕"))
    }
}
