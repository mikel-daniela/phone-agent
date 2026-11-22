package com.pocketagent.personalogic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonaAssembler.stateSection] —— 给**自定义角色**那条提示词支路用的 L3。
 *
 * 背景（2026-11-21）：`assistantSystemPrompt` 里自定义角色那一支会整条替换装配器，
 * 代价是 L3 状态段一起没了 —— 自定义角色于是永远不知道自己累不累、在不在生理期、
 * 体检里有没有贫血。用户的原话是"必须验证身体设定会不会真的影响行为"，
 * 而自定义角色正是默认用法。
 *
 * 这里钉的是三件事：
 * 1. 有由头的状态一定会被写出来（不然这一段等于没接）
 * 2. 没有女性专项的体检报告**不会**凭空写出经期（男生也有这份报告）
 * 3. 体检里标红的项会进这一段（贫血要真的影响行为，而不只是存着）
 */
class StateSectionTest {

    private val assembler = PersonaAssembler(TokenCounter { it.length })

    /** 让"现在"落在一个确定的时刻：2026-11-21 10:00 +08:00 */
    private val nowMs = 1_795_000_000_000L

    private fun state(
        mood: Int = 30,
        energy: Int = 20,
        health: HealthReport = HealthReport(),
        lastActiveAtMs: Long = nowMs,
    ) = PersonaState(
        mood = mood,
        energy = energy,
        physiology = Physiology(lastActiveAtMs = lastActiveAtMs),
        health = health,
    )

    @Test
    fun `有由头的状态会写进这一段`() {
        val text = assembler.stateSection(state(mood = 20, energy = 15))
        assertTrue(text, text.contains("## 此刻的状态"))
        assertTrue(text, text.contains("兴致不高"))
    }

    @Test
    fun `体检里贫血会进这一段`() {
        val anemic = HealthReport(blood = BloodPanel(hb = 96.0))
        val text = assembler.stateSection(state(health = anemic))
        assertTrue(text, text.contains("## 你的身体"))
        assertTrue(text, text.contains("血红蛋白偏低"))
    }

    @Test
    fun `没有女性专项的报告不写经期`() {
        val text = assembler.stateSection(state(health = HealthReport(female = null)))
        assertFalse(text, text.contains("生理期"))
    }

    @Test
    fun `有女性专项且正处经期才写经期`() {
        // 经期第一天：epoch 除以周期长度取余为 0
        val female = HealthReport(female = FemalePanel(cycleLengthDays = 28, periodLengthDays = 5))
        val dayMs = 86_400_000L
        val firstDayMs = dayMs * 28 * 100
        val text = assembler.stateSection(state(health = female, lastActiveAtMs = firstDayMs))
        assertTrue(text, text.contains("生理期"))
    }

    @Test
    fun `还没交互过的时间不加身体叙事`() {
        // `lastActiveAtMs = 0` 是"时间未知"的哨兵；把它当 1970 年会算出荒唐的时段
        val text = assembler.stateSection(state(lastActiveAtMs = 0L))
        assertFalse(text, text.contains("生理期"))
    }
}
