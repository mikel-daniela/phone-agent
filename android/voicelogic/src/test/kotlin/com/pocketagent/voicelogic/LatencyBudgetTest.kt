package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [`LatencyLedger`] 的测试。
 *
 * 这个文件真正的主题只有一条：**缺数据不能被当成好数据**。
 *
 * 一条只测了两段的回合，如果按"缺的当 0"算，总账会漂亮得离谱并判定合格 ——
 * 于是有三段永远没人观测，而指标显示一切正常。这是本项目最忌讳的那类问题
 * 在度量层的样子：不是算错，是算出一个不该算的数。
 *
 * 其次是把两个常量钉死：四段 target 之和必须等于 800（架构文档给的数字），
 * 总账上限必须**小于**四段上限之和（否则总账判永远被单段判覆盖，形同虚设）。
 */
class LatencyBudgetTest {

    private fun complete(
        vad: Long = 300,
        asr: Long = 200,
        llm: Long = 200,
        tts: Long = 100,
    ): LatencyLedger = LatencyLedger()
        .mark(LatencySegment.VAD_ENDPOINT, vad)
        .mark(LatencySegment.ASR_FIRST_TEXT, asr)
        .mark(LatencySegment.LLM_FIRST_TOKEN, llm)
        .mark(LatencySegment.TTS_FIRST_CHUNK, tts)

    // ── 常量一致性（改一处忘另一处在这里会红） ──────────────────

    @Test
    fun `四段目标之和必须是八百毫秒`() {
        val sum = LatencySegment.values().sumOf { it.targetMs }
        assertEquals(
            "架构文档 §4.5.2 给的目标是 ≤800ms；改了某一段就要同步改 TARGET_TOTAL_MS",
            LatencySegment.TARGET_TOTAL_MS,
            sum,
        )
    }

    @Test
    fun `总账上限必须小于四段上限之和`() {
        val sumOfLimits = LatencySegment.values().sumOf { it.limitMs }
        assertTrue(
            "总账上限 " + LatencySegment.LIMIT_TOTAL_MS + "ms 不小于四段上限之和 " +
                sumOfLimits + "ms —— 那么总账判定永远不会独立触发，等于没有",
            LatencySegment.LIMIT_TOTAL_MS < sumOfLimits,
        )
    }

    @Test
    fun `每段的上限必须大于目标`() {
        for (seg in LatencySegment.values()) {
            assertTrue(
                seg.displayName + " 的 limit(" + seg.limitMs + ") 必须大于 target(" + seg.targetMs + ")",
                seg.limitMs > seg.targetMs,
            )
        }
    }

    // ── 合格路径 ───────────────────────────────────────────────

    @Test
    fun `四段都正好达到目标时判定合格`() {
        val r = complete().report()
        assertTrue(r.isComplete)
        assertEquals(800L, r.measuredTotalMs)
        assertEquals(LatencyVerdict.WithinBudget, r.verdict)
        assertTrue("四段都正好等于目标，不该出现在慢段清单里", r.slowSegments.isEmpty())
    }

    @Test
    fun `合计正好等于上限时仍然合格`() {
        // 边界口径：判定用「严格大于」。等于上限算合格，
        // 否则"上限 1200"这句话就要理解成"最严 1199"，没人会这么读
        val r = complete(vad = 500, asr = 400, llm = 200, tts = 100).report()
        assertEquals(1_200L, r.measuredTotalMs)
        assertEquals(LatencyVerdict.WithinBudget, r.verdict)
    }

    @Test
    fun `某段超目标但未超上限只记慢段不判失败`() {
        val r = complete(vad = 400).report()
        assertEquals(900L, r.measuredTotalMs)
        assertEquals(LatencyVerdict.WithinBudget, r.verdict)
        assertEquals(listOf(LatencySegment.VAD_ENDPOINT), r.slowSegments)
    }

    @Test
    fun `合格时摘要也要念出慢段`() {
        // 只写"合格"的日志会让"四段全程磨蹭"看起来跟"四段飞快"一样
        val text = complete(vad = 400).report().summary()
        assertTrue("摘要里应出现慢段名字", text.contains(LatencySegment.VAD_ENDPOINT.displayName))
        assertTrue("摘要应带上目标值", text.contains("300ms"))
    }

    // ── 超限路径 ───────────────────────────────────────────────

    @Test
    fun `单段爆掉要判超限并点名`() {
        val report = complete(vad = 601).report()
        val verdict = report.verdict
        assertTrue(verdict is LatencyVerdict.OverBudget)
        verdict as LatencyVerdict.OverBudget
        assertEquals(listOf(LatencySegment.VAD_ENDPOINT), verdict.overSegments)
        assertFalse("四段之和 1101 没超总账上限，不该报总账超限", verdict.totalOver)
    }

    @Test
    fun `每段都不爆表但合起来崩了必须被总账抓到`() {
        // 500 / 440 / 440 / 290 = 1670：每一段单看都"还行"（未超 limit），
        // 但用户实际等了一秒七。这种整体性劣化只有总账能看见
        val r = complete(vad = 500, asr = 440, llm = 440, tts = 290).report()
        val verdict = r.verdict
        assertTrue(verdict is LatencyVerdict.OverBudget)
        verdict as LatencyVerdict.OverBudget
        assertTrue(verdict.totalOver)
        assertTrue("没有单段超 limit", verdict.overSegments.isEmpty())
        assertTrue(r.summary().contains("合计"))
    }

    @Test
    fun `总账上限的边界是不超过即合格`() {
        // 没用满容差的一千零一毫秒：合格
        val under = complete(vad = 501, asr = 200, llm = 200, tts = 100).report()
        assertEquals(1_001L, under.measuredTotalMs)
        assertEquals(LatencyVerdict.WithinBudget, under.verdict)

        // 过了上限：判超限
        val over = complete(vad = 505, asr = 300, llm = 300, tts = 100).report()
        assertEquals(1_205L, over.measuredTotalMs)
        val v = over.verdict
        assertTrue(v is LatencyVerdict.OverBudget)
        assertTrue((v as LatencyVerdict.OverBudget).totalOver)
    }

    // ── 缺数据：这个文件的核心 ─────────────────────────────────

    @Test
    fun `只测了一段必须判未完成而不是合格`() {
        val r = LatencyLedger().mark(LatencySegment.VAD_ENDPOINT, 100).report()
        assertFalse(r.isComplete)
        val v = r.verdict
        assertTrue(v is LatencyVerdict.Incomplete)
        v as LatencyVerdict.Incomplete
        assertEquals(3, v.missing.size)
        assertTrue(v.missing.contains(LatencySegment.ASR_FIRST_TEXT))
        assertTrue(v.missing.contains(LatencySegment.LLM_FIRST_TOKEN))
        assertTrue(v.missing.contains(LatencySegment.TTS_FIRST_CHUNK))
    }

    @Test
    fun `未测段的实测值必须是空而不是零`() {
        val ledger = LatencyLedger().mark(LatencySegment.VAD_ENDPOINT, 100)
        assertNull(
            "把没测到的段返回 0，会让调用方算出一个不该算的总和",
            ledger.markedMs(LatencySegment.ASR_FIRST_TEXT),
        )
    }

    @Test
    fun `完全没测也不等于合格`() {
        val r = LatencyLedger().report()
        assertFalse(r.isComplete)
        assertEquals(0L, r.measuredTotalMs)
        assertEquals(4, (r.verdict as LatencyVerdict.Incomplete).missing.size)
    }

    @Test
    fun `缺段时即使已测的段超限也先报未完成`() {
        // 判定顺序：完整性优先于超限。反过来的话，
        // 一条只测了 VAD 且爆表的回合会报"超限"，掩盖"另外三段根本没测"
        val r = LatencyLedger().mark(LatencySegment.VAD_ENDPOINT, 9_999).report()
        assertTrue(r.verdict is LatencyVerdict.Incomplete)
    }

    @Test
    fun `摘要要说清缺了哪几段`() {
        val text = LatencyLedger().mark(LatencySegment.ASR_FIRST_TEXT, 200).report().summary()
        assertTrue(text.contains(LatencySegment.VAD_ENDPOINT.displayName))
        assertTrue(text.contains(LatencySegment.TTS_FIRST_CHUNK.displayName))
    }

    // ── 打点语义 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `负数耗时直接拒绝`() {
        LatencyLedger().mark(LatencySegment.VAD_ENDPOINT, -1)
    }

    @Test
    fun `重复打同一段以最后一次为准`() {
        val r = LatencyLedger()
            .mark(LatencySegment.VAD_ENDPOINT, 900)
            .mark(LatencySegment.VAD_ENDPOINT, 300)
            .report()
        assertEquals(300L, r.marks[LatencySegment.VAD_ENDPOINT])
    }

    @Test
    fun `reset 后回到一段都没测`() {
        val ledger = complete()
        assertTrue(ledger.isComplete())
        ledger.reset()
        assertFalse(ledger.isComplete())
    }

    @Test
    fun `乱序打点不影响判定`() {
        // 真实链路里 ASR 首字可能迟到，强行校验顺序只会制造假失败
        val r = LatencyLedger()
            .mark(LatencySegment.TTS_FIRST_CHUNK, 100)
            .mark(LatencySegment.LLM_FIRST_TOKEN, 200)
            .mark(LatencySegment.ASR_FIRST_TEXT, 200)
            .mark(LatencySegment.VAD_ENDPOINT, 300)
            .report()
        assertEquals(LatencyVerdict.WithinBudget, r.verdict)
        assertEquals(800L, r.measuredTotalMs)
    }
}
