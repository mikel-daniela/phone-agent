package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VoiceSessionMachine` 的迁移表测试。
 *
 * 重点不在"一轮通话能走通"（那个太容易对），而在三件**不改变状态、只改变账单**
 * 的事：
 *
 * 1. 打断必须同时发停播 / 取消在途 / 清队列 —— 少发一件，状态看起来完全正常；
 * 2. 判停之后不能关麦克风 —— 关了，打断就永远收不到信号，而且没有任何报错；
 * 3. 每个被忽略的事件都要写原因 —— 否则"这次开口为什么没打断"只能靠猜。
 *
 * 另有两个防卡死出口（[VoiceEvent.EmptyTurn]、打断中失败）单独覆盖。
 */
class VoiceSessionMachineTest {

    private val allEvents: List<VoiceEvent> = listOf(
        VoiceEvent.StartCall,
        VoiceEvent.StopCall,
        VoiceEvent.EndOfSpeech,
        VoiceEvent.FirstToken,
        VoiceEvent.SpeechFinished,
        VoiceEvent.UserStartedTalking,
        VoiceEvent.EffectsSettled,
        VoiceEvent.EmptyTurn,
        VoiceEvent.Failed("模拟故障"),
    )

    /** 所有通话中的状态（[VoiceState.IDLE] 之外） */
    private val inCallStates: List<VoiceState> =
        VoiceState.values().filter { it.inCall }

    private fun reduce(state: VoiceState, event: VoiceEvent): VoiceTransition =
        VoiceSessionMachine.reduce(state, event)

    private fun moved(state: VoiceState, event: VoiceEvent): VoiceTransition.Moved =
        reduce(state, event) as VoiceTransition.Moved

    private fun ignored(state: VoiceState, event: VoiceEvent): VoiceTransition.Ignored =
        reduce(state, event) as VoiceTransition.Ignored

    // ── 主干：一轮完整通话 ──────────────────────────────────────

    @Test
    fun `一轮完整通话按状态图走通`() {
        val started = moved(VoiceState.IDLE, VoiceEvent.StartCall)
        assertEquals(VoiceState.LISTENING, started.to)

        val thinking = moved(VoiceState.LISTENING, VoiceEvent.EndOfSpeech)
        assertEquals(VoiceState.THINKING, thinking.to)

        val speaking = moved(VoiceState.THINKING, VoiceEvent.FirstToken)
        assertEquals(VoiceState.SPEAKING, speaking.to)

        val backToListening = moved(VoiceState.SPEAKING, VoiceEvent.SpeechFinished)
        assertEquals(VoiceState.LISTENING, backToListening.to)
    }

    @Test
    fun `开始通话要开麦并起第一轮`() {
        val t = moved(VoiceState.IDLE, VoiceEvent.StartCall)
        assertTrue(t.effects.contains(VoiceEffect.StartCapture))
        assertTrue(t.effects.contains(VoiceEffect.BeginListeningTurn))
    }

    @Test
    fun `首 token 之后开始出声`() {
        val t = moved(VoiceState.THINKING, VoiceEvent.FirstToken)
        assertTrue(t.effects.contains(VoiceEffect.StartPlayback))
    }

    // ── 打断：这条是本次实现的理由 ──────────────────────────────

    @Test
    fun `出声时开口打断要发三件事`() {
        val t = moved(VoiceState.SPEAKING, VoiceEvent.UserStartedTalking)
        assertEquals(VoiceState.INTERRUPTED, t.to)

        // ① 恰好三件：多一件说明有人在分支里自己拼了效果，少一件就是漏做
        assertEquals("打断必须恰好三件副作用", 3, t.effects.size)

        // ② 三件各自点名：不能只断言"跟常量一样"，常量自己写错了也会过
        assertTrue("缺停播", t.effects.contains(VoiceEffect.StopPlayback))
        assertTrue("缺取消在途请求 —— 助理嘴停了但 token 还在烧", t.effects.contains(VoiceEffect.CancelInflight))
        assertTrue("缺清空 TTS 队列 —— 下一句会从被丢弃的旧队列接续", t.effects.contains(VoiceEffect.ClearTtsQueue))

        // ③ 分支必须引用常量而不是另拼一份
        assertEquals(VoiceSessionMachine.BARGE_IN_EFFECTS, t.effects)
    }

    @Test
    fun `想的时候开口也算打断`() {
        // 还没出声，但请求已经在途 —— 停播无事可做，取消在途正是省钱的时刻
        val t = moved(VoiceState.THINKING, VoiceEvent.UserStartedTalking)
        assertEquals(VoiceState.INTERRUPTED, t.to)
        assertEquals(VoiceSessionMachine.BARGE_IN_EFFECTS, t.effects)
        assertTrue(t.effects.contains(VoiceEffect.CancelInflight))
    }

    @Test
    fun `在听时开口不是打断`() {
        // ★ 这是最容易错的一条：在听时用户说话是本分。
        //   如果这里也判打断，用户永远说不出一句完整的话
        val t = ignored(VoiceState.LISTENING, VoiceEvent.UserStartedTalking)
        assertEquals(VoiceState.LISTENING, t.from)
    }

    @Test
    fun `打断收敛后回到在听并复位本轮`() {
        val t = moved(VoiceState.INTERRUPTED, VoiceEvent.EffectsSettled)
        assertEquals(VoiceState.LISTENING, t.to)
        assertTrue(
            "不复位本轮的话 VAD 还停在已开口，用户接着说两个词又会触发一次打断",
            t.effects.contains(VoiceEffect.BeginListeningTurn),
        )
    }

    @Test
    fun `打断中重复开口被忽略`() {
        val t = ignored(VoiceState.INTERRUPTED, VoiceEvent.UserStartedTalking)
        assertEquals(VoiceState.INTERRUPTED, t.from)
    }

    @Test
    fun `打断常量必须恰好三件且不重复`() {
        val barge = VoiceSessionMachine.BARGE_IN_EFFECTS
        assertEquals(3, barge.size)
        assertEquals("三件事不能重复", barge.size, barge.toSet().size)
        assertTrue(barge.contains(VoiceEffect.StopPlayback))
        assertTrue(barge.contains(VoiceEffect.CancelInflight))
        assertTrue(barge.contains(VoiceEffect.ClearTtsQueue))
    }

    // ── 防卡死出口 ─────────────────────────────────────────────

    @Test
    fun `想的时候没内容可说要收敛回在听`() {
        // ★ 没有这条出口，THINKING 会等一个永远不来的首 token：
        //   助理一直转圈、麦克风开着、什么都不发生，日志里一行错都没有
        val t = moved(VoiceState.THINKING, VoiceEvent.EmptyTurn)
        assertEquals(VoiceState.LISTENING, t.to)
        assertTrue(t.effects.contains(VoiceEffect.BeginListeningTurn))
    }

    @Test
    fun `取消在途失败也必须收敛`() {
        // ★ 只认 EffectsSettled 的话，取消超时会让通话永久停在"打断中"：
        //   麦克风开着，但没有任何状态在处理它
        val t = moved(VoiceState.INTERRUPTED, VoiceEvent.Failed("取消超时"))
        assertEquals(VoiceState.LISTENING, t.to)
        assertTrue(t.effects.contains(VoiceEffect.BeginListeningTurn))
    }

    @Test
    fun `想的时候出错要取消在途并回听`() {
        val t = moved(VoiceState.THINKING, VoiceEvent.Failed("LLM 500"))
        assertEquals(VoiceState.LISTENING, t.to)
        assertTrue(t.effects.contains(VoiceEffect.CancelInflight))
        assertTrue(t.effects.contains(VoiceEffect.BeginListeningTurn))
    }

    @Test
    fun `出声时出错要停播并回听`() {
        val t = moved(VoiceState.SPEAKING, VoiceEvent.Failed("TTS 404"))
        assertEquals(VoiceState.LISTENING, t.to)
        assertTrue(t.effects.contains(VoiceEffect.StopPlayback))
        assertTrue(t.effects.contains(VoiceEffect.BeginListeningTurn))
    }

    // ── 麦克风常开这条硬约束 ────────────────────────────────────

    @Test
    fun `判停之后不能关麦克风`() {
        val t = moved(VoiceState.LISTENING, VoiceEvent.EndOfSpeech)
        assertTrue(t.effects.contains(VoiceEffect.StartThinking))
        assertFalse(
            "判停就把麦克风关掉的话，用户在助理思考/说话时开口永远收不到 —— 打断功能等于不存在",
            t.effects.contains(VoiceEffect.StopCapture),
        )
    }

    @Test
    fun `想和说的时候麦克风都必须是开的`() {
        assertTrue(VoiceState.THINKING.wantsMicrophone)
        assertTrue(VoiceState.SPEAKING.wantsMicrophone)
        assertTrue(VoiceState.INTERRUPTED.wantsMicrophone)
        assertFalse(VoiceState.IDLE.wantsMicrophone)
    }

    @Test
    fun `只有想和说的时候助理才占话语权`() {
        assertTrue(VoiceState.THINKING.holdsTurn)
        assertTrue(VoiceState.SPEAKING.holdsTurn)
        assertFalse(VoiceState.LISTENING.holdsTurn)
        assertFalse(VoiceState.IDLE.holdsTurn)
        assertFalse(VoiceState.INTERRUPTED.holdsTurn)
    }

    // ── 过期与乱序事件 ─────────────────────────────────────────

    @Test
    fun `出声时收到判停不会跳回在想`() {
        // 打断被触发那一帧，VAD 还会再报一次判停。若它被当成新的一轮，
        // 状态会从 SPEAKING 跳 THINKING，刚才那次打断就白做了
        val t = ignored(VoiceState.SPEAKING, VoiceEvent.EndOfSpeech)
        assertEquals(VoiceState.SPEAKING, t.from)
    }

    @Test
    fun `还没出声就收到播完属于乱序`() {
        val t = ignored(VoiceState.THINKING, VoiceEvent.SpeechFinished)
        assertEquals(VoiceState.THINKING, t.from)
    }

    @Test
    fun `还没判停就收到首 token 属于伪造`() {
        val t = ignored(VoiceState.LISTENING, VoiceEvent.FirstToken)
        assertEquals(VoiceState.LISTENING, t.from)
    }

    // ── 挂断 ───────────────────────────────────────────────────

    @Test
    fun `挂断从任何通话中状态都能收干净`() {
        for (state in inCallStates) {
            val t = moved(state, VoiceEvent.StopCall)
            assertEquals("从 " + state.name + " 挂断应回到空闲", VoiceState.IDLE, t.to)
            assertEquals(
                "从 " + state.name + " 挂断应发完整收尾效果",
                VoiceSessionMachine.CALL_END_EFFECTS,
                t.effects,
            )
        }
    }

    @Test
    fun `挂断效果包含打断三件事加关麦`() {
        val end = VoiceSessionMachine.CALL_END_EFFECTS
        for (e in VoiceSessionMachine.BARGE_IN_EFFECTS) {
            assertTrue("挂断漏了 " + e, end.contains(e))
        }
        assertTrue("挂断最后要关麦", end.contains(VoiceEffect.StopCapture))
        assertEquals(4, end.size)
    }

    @Test
    fun `空闲时挂断没有意义`() {
        val t = ignored(VoiceState.IDLE, VoiceEvent.StopCall)
        assertEquals(VoiceState.IDLE, t.from)
    }

    // ── 穷尽性质：这两条扫全表，新增状态/事件时会自动覆盖 ──────

    @Test
    fun `空闲状态不接任何事件`() {
        for (event in allEvents) {
            val t = reduce(VoiceState.IDLE, event)
            if (event == VoiceEvent.StartCall) {
                assertTrue("开始通话必须能进来", t is VoiceTransition.Moved)
            } else {
                assertTrue(
                    "空闲时收到 " + event + " 不该改变状态",
                    t is VoiceTransition.Ignored,
                )
            }
        }
    }

    @Test
    fun `每个被忽略的事件都必须写清原因`() {
        // ★ 静默忽略是本项目的头号 bug 形态。reason 非空，
        //   "这次开口为什么没打断"才可能在日志里被回答
        for (state in VoiceState.values()) {
            for (event in allEvents) {
                val t = reduce(state, event)
                if (t is VoiceTransition.Ignored) {
                    assertTrue(
                        state.name + " 收到 " + event + " 被忽略却没写原因",
                        t.reason.isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    fun `每个迁移的起点都等于入参状态`() {
        for (state in VoiceState.values()) {
            for (event in allEvents) {
                assertEquals(
                    "迁移结果把起点记错了",
                    state,
                    reduce(state, event).from,
                )
            }
        }
    }

    @Test
    fun `迁移只会落到已知状态`() {
        // 防御性断言：将来若有人加了一个"中间态"却忘了给出口，
        // 这里不会红，但至少每次迁移的目标都是可枚举的已知状态
        val known = VoiceState.values().toSet()
        for (state in VoiceState.values()) {
            for (event in allEvents) {
                val t = reduce(state, event)
                if (t is VoiceTransition.Moved) {
                    assertTrue(t.to in known)
                }
            }
        }
    }
}
