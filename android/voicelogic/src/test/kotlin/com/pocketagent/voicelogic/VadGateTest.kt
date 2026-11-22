package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [`VadGate`] 的测试。
 *
 * 这里覆盖的是四条**判错了不会有任何报错**的规则：
 *
 * 1. 短促噪声（咳嗽、敲桌子、杯子）不能算开口 —— 否则助理每说两句就被打断一次；
 * 2. 开口信号只能报一次 —— 否则上层每 20ms 收到一次，打断被重复触发几十次；
 * 3. 滞后区内的抖动不改变判断 —— 否则一句话被切成十几段；
 * 4. `NaN` 既不是有声也不是静音 —— 当成静音会让麦克风故障时"用户说完了"，
 *    然后助理对着一片寂静长篇回答。
 *
 * 另外把"向上取整"与"至少一帧"这两条算术也钉住：它们决定了 130ms 的保持窗口
 * 是 6 帧还是 7 帧，差一帧就是 20ms 的主观差异，而配置写错时没有任何症状。
 */
class VadGateTest {

    /** 明显有声（高于 -35） */
    private val loud = -30f

    /** 明显安静（低于 -45） */
    private val quiet = -60f

    /** 落在滞后区 [-45, -35]：既不该启动，也不该算静音 */
    private val inHysteresis = -40f

    private fun activeGate(): VadGate {
        val gate = VadGate()
        repeat(gate.config.speechStartHoldFrames) { gate.onFrame(loud) }
        assertTrue("先决条件：门控应已处于说话中", gate.isSpeechActive)
        return gate
    }

    // ── 开口 ───────────────────────────────────────────────────

    @Test
    fun `连续够帧数才报开口`() {
        val gate = VadGate()
        val need = gate.config.speechStartHoldFrames
        assertEquals(6, need) // 120ms / 20ms

        for (i in 1 until need) {
            assertEquals("第 " + i + " 帧不该触发", VadSignal.NONE, gate.onFrame(loud))
        }
        assertEquals(VadSignal.SPEECH_STARTED, gate.onFrame(loud))
        assertTrue(gate.isSpeechActive)
    }

    @Test
    fun `开口只报一次`() {
        // ★ 报多次的话，上层会把一次打断当成几十次，
        //   停播/取消请求/清队列各被执行几十遍
        val gate = activeGate()
        for (i in 1..10) {
            assertEquals("已开口后第 " + i + " 帧不该再报", VadSignal.NONE, gate.onFrame(loud))
        }
    }

    @Test
    fun `短促噪声不触发开口`() {
        // 3 帧 = 60ms 的咳嗽/敲击声
        val gate = VadGate()
        repeat(3) { assertEquals(VadSignal.NONE, gate.onFrame(loud)) }
        assertFalse("60ms 的噪声不该算开口", gate.isSpeechActive)
    }

    @Test
    fun `中间夹一帧安静就要重新数`() {
        // ★ 保持窗口要求"连续"。若改成衰减计数（例如每帧 -1），
        //   那么 20 帧里散落十几帧有声也会攒够，等于没有滤噪
        val gate = VadGate()
        repeat(5) { gate.onFrame(loud) }
        assertEquals(VadSignal.NONE, gate.onFrame(quiet))
        // 重新从头数：再来 5 帧还不够
        repeat(5) { assertEquals(VadSignal.NONE, gate.onFrame(loud)) }
        assertFalse(gate.isSpeechActive)
        // 第 6 帧才够
        assertEquals(VadSignal.SPEECH_STARTED, gate.onFrame(loud))
    }

    @Test
    fun `滞后区里的声音不启动开口`() {
        val gate = VadGate()
        repeat(20) { assertEquals(VadSignal.NONE, gate.onFrame(inHysteresis)) }
        assertFalse("-40dBFS 在滞后区，不该启动", gate.isSpeechActive)
    }

    // ── 判停 ───────────────────────────────────────────────────

    @Test
    fun `静音够时长才判停`() {
        val gate = activeGate()
        val need = gate.config.endSilenceFrames
        assertEquals(15, need) // 300ms / 20ms

        for (i in 1 until need) {
            assertEquals("第 " + i + " 帧静音还不该判停", VadSignal.NONE, gate.onFrame(quiet))
        }
        assertEquals(VadSignal.SPEECH_ENDED, gate.onFrame(quiet))
        assertFalse(gate.isSpeechActive)
    }

    @Test
    fun `两句之间换气那么短的停顿不会判停`() {
        // 200ms 的换气（10 帧）< 300ms 判停线。判停线太短的后果是
        // 前半句被当成完整问题送去 LLM，得到答非所问的回答
        val gate = activeGate()
        repeat(10) { gate.onFrame(quiet) }
        assertTrue("200ms 停顿不该判停", gate.isSpeechActive)

        // 继续说，静音计数应清零
        assertEquals(VadSignal.NONE, gate.onFrame(loud))
        repeat(14) { assertEquals(VadSignal.NONE, gate.onFrame(quiet)) }
        assertTrue(gate.isSpeechActive)
    }

    @Test
    fun `滞后区里的声音不算静音`() {
        val gate = activeGate()
        repeat(30) { assertEquals(VadSignal.NONE, gate.onFrame(inHysteresis)) }
        assertTrue("滞后区内不该判停", gate.isSpeechActive)
    }

    @Test
    fun `真静音会判停`() {
        // -Inf 是 PCM 全 0 算出来的值，是货真价实的安静
        val gate = activeGate()
        repeat(gate.config.endSilenceFrames - 1) { gate.onFrame(Float.NEGATIVE_INFINITY) }
        assertEquals(VadSignal.SPEECH_ENDED, gate.onFrame(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `NaN 既不算有声也不算静音`() {
        // ★ 这是本文件最重要的一条。NaN 与任何数比较都是 false，
        //   若不加特殊处理，激活后会走进"不够安静"分支把静音计数清零 ——
        //   用户说完了永远等不到判停，通话看起来还在"等用户说话"
        val gate = activeGate()
        repeat(13) { gate.onFrame(quiet) }

        assertEquals(VadSignal.NONE, gate.onFrame(Float.NaN))

        assertEquals(
            "NaN 若清掉了静音计数，用户说完了就永远等不到判停",
            VadSignal.NONE,
            gate.onFrame(quiet),
        )
        assertEquals(VadSignal.SPEECH_ENDED, gate.onFrame(quiet))
    }

    @Test
    fun `NaN 不能清零静音计数`() {
        // 差一帧就要判停时插一帧 NaN：计数应原地不动，下一帧立刻判停。
        // 如果 NaN 走了 else 分支把计数清零，这里会得到 NONE
        val gate = activeGate()
        repeat(gate.config.endSilenceFrames - 1) { gate.onFrame(quiet) }
        assertEquals(VadSignal.NONE, gate.onFrame(Float.NaN))
        assertEquals(VadSignal.SPEECH_ENDED, gate.onFrame(quiet))
    }

    // ── 重新武装 ───────────────────────────────────────────────

    @Test
    fun `判停之后可以再次开口`() {
        val gate = activeGate()
        repeat(gate.config.endSilenceFrames) { gate.onFrame(quiet) }
        assertFalse(gate.isSpeechActive)

        repeat(gate.config.speechStartHoldFrames - 1) {
            assertEquals(VadSignal.NONE, gate.onFrame(loud))
        }
        assertEquals(VadSignal.SPEECH_STARTED, gate.onFrame(loud))
    }

    @Test
    fun `reset 之后要重新攒够帧数`() {
        // 打断后必须调用 reset：否则 voicedFrames 可能停在 4/6，
        // 用户接着说两个词就又触发一次开口 —— 连环打断
        val gate = VadGate()
        repeat(5) { gate.onFrame(loud) }
        gate.reset()
        assertFalse(gate.isSpeechActive)

        repeat(5) { assertEquals(VadSignal.NONE, gate.onFrame(loud)) }
        assertEquals(VadSignal.SPEECH_STARTED, gate.onFrame(loud))
    }

    // ── 配置算术 ───────────────────────────────────────────────

    @Test
    fun `保持窗口向上取整`() {
        // 130ms / 20ms = 6.5 → 7 帧。宁可慢一帧（20ms），也不要提前触发
        val cfg = VadConfig(speechStartHoldMs = 130)
        assertEquals(7, cfg.speechStartHoldFrames)
    }

    @Test
    fun `保持窗口至少一帧`() {
        // 0 毫秒若算出 0 帧，等于"一帧有声就触发"—— 第一声噪声就会打断
        val cfg = VadConfig(speechStartHoldMs = 0)
        assertEquals(1, cfg.speechStartHoldFrames)
    }

    @Test
    fun `默认配置与架构文档一致`() {
        val cfg = VadConfig()
        assertEquals(20, cfg.frameMs)
        assertEquals(120, cfg.speechStartHoldMs)
        assertEquals(300, cfg.endSilenceMs)
        assertEquals(6, cfg.speechStartHoldFrames)
        assertEquals(15, cfg.endSilenceFrames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `退出阈值不低于进入阈值时构造失败`() {
        // 写成相等或写反，滞后区就消失了，门控会在边界疯狂翻转 ——
        // 症状是"一句话被切成十几段"，几乎不会有人想到是配置问题
        VadConfig(speechOnDbfs = -45f, speechOffDbfs = -45f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `帧长非正时构造失败`() {
        VadConfig(frameMs = 0)
    }

    @Test
    fun `快照反映内部计数`() {
        val gate = VadGate()
        repeat(3) { gate.onFrame(loud) }
        val snap = gate.snapshot()
        assertEquals(3, snap.voicedFrames)
        assertFalse(snap.isSpeechActive)
        assertEquals(0, snap.silentFrames)
    }
}
