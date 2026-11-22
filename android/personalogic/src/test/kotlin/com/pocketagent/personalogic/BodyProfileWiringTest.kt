package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **体检报告真的会改变行为** —— 钉的是"算好了有没有人用"。
 *
 * 2026-11-21 用户要求「必须验证角色身体设定会不会真的影响行为和反应」。
 * 当时审计发现 [BodyProfile] 里五个字段只有 `fatigueMultiplier` 与
 * `circadianShiftHours` 有消费点，其余**算出来了但没有任何人读**：
 *
 * | 字段 | 当时的消费点 |
 * |---|---|
 * | `fatigueMultiplier` | `PhysiologyEngine.energyModifier` ✅ |
 * | `circadianShiftHours` | `PhysiologyEngine.energyModifier` ✅ |
 * | `recoverMultiplier` | **无** |
 * | `stressMultiplier` | **无** |
 * | `coldSensitivity` | **无** |
 * | `painTolerance` / `intimacyNeed` | **无** |
 *
 * 这个文件钉住修好之后的行为：贫血/甲减的人同样的休息恢复得更少、
 * 压力敏感的人同一句重话皮质醇顶得更高、推导体质会出现在提示词里。
 */
class BodyProfileWiringTest {

    private val h = 3_600_000.0

    // ── ① 恢复速率 ───────────────────────────────────────────────

    @Test
    fun `恢复慢的人同样睡八小时只还掉一半的债`() {
        val tired = Physiology(sleepDebtHours = 4.0)
        val healthy = PhysiologyEngine.advance(tired, (8 * h).toLong(), BodyProfile.Default)
        val slow = PhysiologyEngine.advance(
            tired,
            (8 * h).toLong(),
            BodyProfile(recoverRate = RecoverRate.SLOW),
        )
        assertTrue("正常人睡 8 小时该还掉大半，实际剩 ${healthy.sleepDebtHours}", healthy.sleepDebtHours < 1.0)
        assertTrue("恢复慢的人不该还这么多，实际剩 ${slow.sleepDebtHours}", slow.sleepDebtHours > healthy.sleepDebtHours)
    }

    @Test
    fun `恢复快的人还债更快`() {
        val tired = Physiology(sleepDebtHours = 4.0)
        val normal = PhysiologyEngine.advance(tired, (4 * h).toLong(), BodyProfile.Default)
        val fast = PhysiologyEngine.advance(
            tired,
            (4 * h).toLong(),
            BodyProfile(recoverRate = RecoverRate.FAST),
        )
        assertTrue("恢复快的人剩的应该更少", fast.sleepDebtHours < normal.sleepDebtHours)
    }

    @Test
    fun `甲减的体检报告会推导出恢复慢`() {
        // 这是"接线"的另一半：报告 → 体质 → 引擎。三段都要真的通。
        val report = HealthReport(thyroid = ThyroidPanel(tsh = 6.5))
        assertEquals(RecoverRate.SLOW, report.derive().recoverRate)
        val tired = Physiology(sleepDebtHours = 4.0)
        val slow = PhysiologyEngine.advance(tired, (8 * h).toLong(), report.derive())
        val normal = PhysiologyEngine.advance(tired, (8 * h).toLong(), HealthReport().derive())
        assertTrue("填了甲减之后，同样的休息恢复得更少", slow.sleepDebtHours > normal.sleepDebtHours)
    }

    // ── ② 压力敏感度 ─────────────────────────────────────────────

    @Test
    fun `压力敏感的人同一句重话皮质醇顶得更高`() {
        val engine = HormoneEngine()
        val calm = engine.step(Hormones(), HormoneEvent.Conflict, 0L, null, stressMultiplier = 1.0)
        val tense = engine.step(Hormones(), HormoneEvent.Conflict, 0L, null, stressMultiplier = 1.4)
        assertTrue(
            "敏感的人皮质醇该更高：${calm.cortisol} vs ${tense.cortisol}",
            tense.cortisol > calm.cortisol,
        )
    }

    @Test
    fun `压力敏感只影响往上顶的方向，不影响回落`() {
        // ★ 否则"敏感"会变成"永远消不下去"—— 那是另一个病，不是敏感。
        val engine = HormoneEngine()
        val baseline = Hormones(cortisol = 70)
        val calm = engine.step(baseline, HormoneEvent.Intimacy, 0L, null, stressMultiplier = 1.0)
        val tense = engine.step(baseline, HormoneEvent.Intimacy, 0L, null, stressMultiplier = 1.4)
        assertEquals("正面事件无处可敏感", calm.cortisol, tense.cortisol)
    }

    @Test
    fun `静息心率快会推导出压力敏感`() {
        val report = HealthReport(vitals = Vitals(restingHeartRate = 92))
        assertEquals(StressSensitivity.HIGH, report.derive().stressSensitivity)
        assertEquals(1.4, report.derive().stressMultiplier, 1e-9)
    }

    // ── ③ 推导体质进提示词 ───────────────────────────────────────

    private fun section(report: HealthReport): String {
        val assembler = PersonaAssembler(TokenCounter { it.length })
        val state = PersonaState(health = report, physiology = Physiology(lastActiveAtMs = 1_795_000_000_000L))
        return assembler.stateSection(state)
    }

    @Test
    fun `很怕疼的人会写进身体那一段`() {
        val text = section(HealthReport(beyond = BeyondReport(painTolerance = 12)))
        assertTrue("该写「怕疼」，实际：$text", text.contains("很怕疼"))
    }

    @Test
    fun `耐疼的人也会写，但写的是另一面`() {
        val text = section(HealthReport(beyond = BeyondReport(painTolerance = 88)))
        assertTrue("该写「耐疼」，实际：$text", text.contains("耐疼"))
    }

    @Test
    fun `亲密需求高与低写的是两句不同的话`() {
        val high = section(HealthReport(beyond = BeyondReport(intimacyNeed = 85)))
        val low = section(HealthReport(beyond = BeyondReport(intimacyNeed = 10)))
        assertTrue("高需求该写「需要被碰到」，实际：$high", high.contains("需要被碰到"))
        assertTrue("低需求该写「不太习惯被碰」，实际：$low", low.contains("不太习惯被碰"))
        assertTrue("两句必须不同 —— 否则填了没用", high != low)
    }

    @Test
    fun `默认档案不写这些多余的话`() {
        val text = section(HealthReport())
        assertTrue("默认该什么都不说，实际：$text", !text.contains("怕疼") && !text.contains("耐疼"))
        assertTrue("默认不该提亲密需求", !text.contains("需要被碰到") && !text.contains("不太习惯被碰"))
    }

    @Test
    fun `贫血会同时推高怕冷与疲劳`() {
        val profile = HealthReport(blood = BloodPanel(hb = 96.0)).derive()
        assertEquals(FatigueThreshold.LOW, profile.fatigueThreshold)
        assertEquals(ColdSensitivity.HIGH, profile.coldSensitivity)
        val text = section(HealthReport(blood = BloodPanel(hb = 96.0)))
        assertTrue("贫血的人该被写成怕冷，实际：$text", text.contains("更怕冷"))
    }
}
