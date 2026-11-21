package com.pocketagent.agentui

/**
 * 通话动效的**形状与交接**映射 —— 纯函数，没有 Compose。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这两个映射要从 UI 层搬出来
 * ═══════════════════════════════════════════════════════════════
 *
 * `CallMotion` 里原本写的是 `when (VoiceCall.motionFor(phase))` —— 直接切形状。
 * 要改成"形变"就得回答两个问题，而这两个问题都是**规则**，不是画法：
 *
 *  1. 这个形状对应哪个可动目标？（呼吸环 → `CALL_RING`）
 *  2. 从 A 到 B 该走哪条编排？哪些切换**没有**编排（那就该硬切，不许瞎编）？
 *
 * 放在 Compose 里它们只能靠肉眼验证；放在这里就能按用例钉死 ——
 * 尤其"没有编排的组合必须返回 null"这条，是最容易被后来人补出
 * 一条虚构节奏的地方。
 *
 * ⚠️ 任何新增的相位都必须在这两个 `when` 里有明确归属，
 *    编译器会替我们拦住遗漏（枚举穷尽）。
 */
object CallMorph {

    /** 形状 ↔ 可动目标。`STILL`（空闲）没有形状，返回 null。 */
    fun targetOf(motion: VoiceCall.Motion): Choreography.Target? = when (motion) {
        VoiceCall.Motion.BREATHING_RING -> Choreography.Target.CALL_RING
        VoiceCall.Motion.SPINNING -> Choreography.Target.CALL_DOTS
        VoiceCall.Motion.WAVEFORM -> Choreography.Target.CALL_BARS
        VoiceCall.Motion.STILL -> null
    }

    /**
     * 这两种形状之间的交接走哪条编排。
     *
     * ★ 只有三条：听→想、想→说、说→听。
     *   反向（想→听）与跳相位（听→说）**没有**编排 —— 返回 null，
     *   调用方直接换形状。给它们硬凑一条节奏会让"急了"和"正常"看起来一样。
     */
    fun beatBetween(from: VoiceCall.Motion, to: VoiceCall.Motion): Choreography.Beat? = when {
        from == VoiceCall.Motion.BREATHING_RING && to == VoiceCall.Motion.SPINNING ->
            Choreography.Beat.CALL_LISTEN_TO_THINK

        from == VoiceCall.Motion.SPINNING && to == VoiceCall.Motion.WAVEFORM ->
            Choreography.Beat.CALL_THINK_TO_SPEAK

        from == VoiceCall.Motion.WAVEFORM && to == VoiceCall.Motion.BREATHING_RING ->
            Choreography.Beat.CALL_SPEAK_TO_LISTEN

        else -> null
    }
}
