package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 通话动效的交接规则 —— 为什么这几条值得单测：
 *
 * 1. `targetOf(STILL)` 必须是 null。有个非空目标就会有"幽灵形状"留在屏幕上
 *    （挂断之后还在转），而这在真机上只有一帧，肉眼几乎抓不到。
 * 2. 只有三条交接有编排。**其余组合必须是 null** —— 一旦有人给"想→听"
 *    补一条节奏，"用户打断"就和"正常说完"长得一样了。
 * 3. 三条编排必须指向**不同**的 Beat：复制粘贴时最容易写成同一个。
 */
class CallMorphTest {

    @Test
    fun `三种形状各自有目标，空闲没有形状`() {
        assertEquals(
            Choreography.Target.CALL_RING,
            CallMorph.targetOf(VoiceCall.Motion.BREATHING_RING),
        )
        assertEquals(
            Choreography.Target.CALL_DOTS,
            CallMorph.targetOf(VoiceCall.Motion.SPINNING),
        )
        assertEquals(
            Choreography.Target.CALL_BARS,
            CallMorph.targetOf(VoiceCall.Motion.WAVEFORM),
        )
        assertNull(
            "空闲不该有形状 —— 有的话挂断之后屏幕上会留下一个静止的图形",
            CallMorph.targetOf(VoiceCall.Motion.STILL),
        )
    }

    @Test
    fun `三条交接各有自己的编排`() {
        assertEquals(
            Choreography.Beat.CALL_LISTEN_TO_THINK,
            CallMorph.beatBetween(VoiceCall.Motion.BREATHING_RING, VoiceCall.Motion.SPINNING),
        )
        assertEquals(
            Choreography.Beat.CALL_THINK_TO_SPEAK,
            CallMorph.beatBetween(VoiceCall.Motion.SPINNING, VoiceCall.Motion.WAVEFORM),
        )
        assertEquals(
            Choreography.Beat.CALL_SPEAK_TO_LISTEN,
            CallMorph.beatBetween(VoiceCall.Motion.WAVEFORM, VoiceCall.Motion.BREATHING_RING),
        )
    }

    @Test
    fun `没有编排的组合一律返回 null，不许硬凑节奏`() {
        val all = VoiceCall.Motion.entries
        val scored = setOf(
            VoiceCall.Motion.BREATHING_RING to VoiceCall.Motion.SPINNING,
            VoiceCall.Motion.SPINNING to VoiceCall.Motion.WAVEFORM,
            VoiceCall.Motion.WAVEFORM to VoiceCall.Motion.BREATHING_RING,
        )
        for (from in all) {
            for (to in all) {
                if ((from to to) in scored) continue
                assertNull(
                    "$from → $to 本来没有编排，现在有一条了 —— 请确认这是设计而不是顺手补的",
                    CallMorph.beatBetween(from, to),
                )
            }
        }
    }

    @Test
    fun `自己到自己没有编排`() {
        for (motion in VoiceCall.Motion.entries) {
            assertNull(CallMorph.beatBetween(motion, motion))
        }
    }
}
