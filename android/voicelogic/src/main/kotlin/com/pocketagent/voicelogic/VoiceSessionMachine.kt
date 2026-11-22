package com.pocketagent.voicelogic

/**
 * 语音会话状态机 —— 纯函数，可离线穷举。
 *
 * ## 为什么是 `object` + `reduce(state, event)` 而不是有状态的类
 *
 * 有状态类的每一次迁移都会改写内部字段，测试只能验证「走完这一串之后长什么样」，
 * 无法验证「第 3 步那一下到底发了哪些副作用」。而本模块最贵的 bug 恰恰是
 * **副作用漏发**（打断时忘了取消在途请求）—— 它不改变最终状态，只改变账单。
 *
 * 所以：状态是入参、效果是出参。每一步的副作用都能被单独断言。
 *
 * ## 迁移表是穷尽的，而且必须保持穷尽
 *
 * [reduce] 用 `when (state) { when (event) { ... } }` 且**不写 else**。
 * 新增一个 [VoiceEvent] 或 [VoiceState] 时，所有缺分支的 `when` 直接编译失败。
 *
 * ⚠️ 这是刻意的摩擦。写成 `else -> ignored(...)` 会让「新事件在这个状态下该做什么」
 * 退化成运行期的沉默 —— 而沉默在本项目里等于错。
 */
object VoiceSessionMachine {

    /**
     * 打断三件事。
     *
     * ★ **单点构造**：只有这一处定义「打断要做哪三件事」。
     * 三个调用点（[VoiceState.THINKING]、[VoiceState.SPEAKING] 的 BargeIn 分支，
     * 以及 [CALL_END_EFFECTS] 的复用）都引用它，所以将来要加第四件事
     * （例如「向用户播一声轻提示」）时改一处、全部生效。
     *
     * 如果三个分支各写一份 listOf(...)，那么「漏掉 CancelInflight」就是一次
     * 复制粘贴事故 —— 停播照常、状态照常、测试照常绿，只有账单异常。
     */
    val BARGE_IN_EFFECTS: List<VoiceEffect> = listOf(
        VoiceEffect.StopPlayback,
        VoiceEffect.CancelInflight,
        VoiceEffect.ClearTtsQueue,
    )

    /** 开始通话：开麦 + 起第一轮听 */
    private val CALL_START_EFFECTS: List<VoiceEffect> = listOf(
        VoiceEffect.StartCapture,
        VoiceEffect.BeginListeningTurn,
    )

    /**
     * 结束通话的四件事 = 打断三件事 + 关麦。
     *
     * 刻意复用 [BARGE_IN_EFFECTS] 而不是再写一遍：挂断同样要让嘴停下、
     * 让在途请求别再烧钱，这两件事和打断一模一样，只是额外关掉麦克风。
     */
    val CALL_END_EFFECTS: List<VoiceEffect> = BARGE_IN_EFFECTS + VoiceEffect.StopCapture

    /**
     * 处理一个事件。
     *
     * @param state 当前状态（不持有任何隐式状态，调用方必须显式传入）
     * @param event 到达的事件
     * @return 新状态与需要宿主执行的副作用；事件无意义时返回 [VoiceTransition.Ignored]
     */
    fun reduce(state: VoiceState, event: VoiceEvent): VoiceTransition = when (state) {
        // ── 空闲：除了开始通话，什么都不接 ──────────────────────────
        // 这里最容易写成「顺手处理一下」，但 IDLE 时收到的所有事件都是
        // 上一通电话的残响（迟到的 SpeechFinished、VAD 的尾帧）。
        // 一旦让它们改变状态，就会出现「没在通话却开始说话」的幽灵会话。
        VoiceState.IDLE -> when (event) {
            VoiceEvent.StartCall ->
                VoiceTransition.Moved(state, VoiceState.LISTENING, CALL_START_EFFECTS)

            VoiceEvent.StopCall -> ignored(state, "没有通话可结束")
            VoiceEvent.EndOfSpeech -> ignored(state, "空闲时收到判停 —— 上一轮的残响")
            VoiceEvent.FirstToken -> ignored(state, "空闲时收到首 token —— 请求本不该存在")
            VoiceEvent.SpeechFinished -> ignored(state, "空闲时收到播完 —— 已经没在播了")
            VoiceEvent.UserStartedTalking -> ignored(state, "空闲时没有人该在说话")
            VoiceEvent.EffectsSettled -> ignored(state, "空闲时没有待收敛的副作用")
            VoiceEvent.EmptyTurn -> ignored(state, "空闲时没有本轮")
            is VoiceEvent.Failed -> ignored(state, "空闲时无环节可失败")
        }

        // ── 在听 ────────────────────────────────────────────────────
        VoiceState.LISTENING -> when (event) {
            VoiceEvent.StartCall -> ignored(state, "已经在通话中")
            VoiceEvent.StopCall ->
                VoiceTransition.Moved(state, VoiceState.IDLE, CALL_END_EFFECTS)

            VoiceEvent.EndOfSpeech ->
                // ★ 只拉请求，**不关麦**。判停之后用户仍可能开口打断
                // （「等等，不是这个」），关掉麦克风就再也收不到了。
                VoiceTransition.Moved(state, VoiceState.THINKING, listOf(VoiceEffect.StartThinking))

            VoiceEvent.FirstToken -> ignored(state, "还没判停就没有请求，首 token 是伪造的")
            VoiceEvent.SpeechFinished -> ignored(state, "本轮还没出声，播完事件属于上一轮")
            // 已经在听，用户说话是本分，不是打断 —— 状态不变、不发任何效果
            VoiceEvent.UserStartedTalking -> ignored(state, "正在听，用户继续说话属于正常输入")
            VoiceEvent.EffectsSettled -> ignored(state, "没有待收敛的副作用")
            VoiceEvent.EmptyTurn -> ignored(state, "还没开始这一轮")
            is VoiceEvent.Failed ->
                // ASR 断流之类：还能继续听，不上抛、不中断通话
                ignored(state, "在听阶段出错，保持收听：" + event.reason)
        }

        // ── 在想：请求在途，可被打断 ────────────────────────────────
        VoiceState.THINKING -> when (event) {
            VoiceEvent.StartCall -> ignored(state, "已经在通话中")
            VoiceEvent.StopCall ->
                VoiceTransition.Moved(state, VoiceState.IDLE, CALL_END_EFFECTS)

            VoiceEvent.EndOfSpeech -> ignored(state, "已经判停过，VAD 抖动重复上报")
            VoiceEvent.FirstToken ->
                VoiceTransition.Moved(state, VoiceState.SPEAKING, listOf(VoiceEffect.StartPlayback))

            VoiceEvent.SpeechFinished -> ignored(state, "还没出声就播完 —— 乱序事件")
            VoiceEvent.UserStartedTalking ->
                // ★ THINKING 也算打断：用户抢话时停播无事可做，但取消在途请求
                // 正是省钱的时刻。发同一份 BARGE_IN_EFFECTS（宿主幂等处理停播）。
                VoiceTransition.Moved(state, VoiceState.INTERRUPTED, BARGE_IN_EFFECTS)
            VoiceEvent.EffectsSettled -> ignored(state, "没有待收敛的副作用")

            // ★ 防卡死出口：没有内容可播时必须收敛回听，否则永久转圈
            VoiceEvent.EmptyTurn ->
                VoiceTransition.Moved(
                    state,
                    VoiceState.LISTENING,
                    listOf(VoiceEffect.BeginListeningTurn),
                )

            is VoiceEvent.Failed ->
                VoiceTransition.Moved(
                    state,
                    VoiceState.LISTENING,
                    listOf(VoiceEffect.CancelInflight, VoiceEffect.BeginListeningTurn),
                )
        }

        // ── 在说 ────────────────────────────────────────────────────
        VoiceState.SPEAKING -> when (event) {
            VoiceEvent.StartCall -> ignored(state, "已经在通话中")
            VoiceEvent.StopCall ->
                VoiceTransition.Moved(state, VoiceState.IDLE, CALL_END_EFFECTS)

            // 已经在出声，此时来的判停属于「打断被触发那一帧」的 VAD 残响
            VoiceEvent.EndOfSpeech -> ignored(state, "出声中收到判停 —— 用户那句已由开口事件处理")
            VoiceEvent.FirstToken -> ignored(state, "已经在说，首 token 重复上报")
            VoiceEvent.SpeechFinished ->
                VoiceTransition.Moved(
                    state,
                    VoiceState.LISTENING,
                    listOf(VoiceEffect.BeginListeningTurn),
                )

            VoiceEvent.UserStartedTalking ->
                VoiceTransition.Moved(state, VoiceState.INTERRUPTED, BARGE_IN_EFFECTS)
            VoiceEvent.EffectsSettled -> ignored(state, "没有待收敛的副作用")
            VoiceEvent.EmptyTurn -> ignored(state, "已经在说，本轮不为空")
            is VoiceEvent.Failed ->
                // TTS 中途挂了：嘴停下，回听。不清队列的话下一句会在错误队列上接续
                VoiceTransition.Moved(
                    state,
                    VoiceState.LISTENING,
                    listOf(VoiceEffect.StopPlayback, VoiceEffect.BeginListeningTurn),
                )
        }

        // ── 打断中：只等收敛 ────────────────────────────────────────
        VoiceState.INTERRUPTED -> when (event) {
            VoiceEvent.StartCall -> ignored(state, "已经在通话中")
            VoiceEvent.StopCall ->
                // 打断还没收敛就挂断：直接把剩下的收拾干净
                VoiceTransition.Moved(state, VoiceState.IDLE, CALL_END_EFFECTS)

            VoiceEvent.EffectsSettled ->
                // ★ 收敛后必须 BeginListeningTurn：用户是在说话中途打断的，
                // 旧的 ASR 缓冲里存着半句旧问题，VAD 计数器也还停在「已开口」。
                // 不复位就会连环打断（详见 BeginListeningTurn 的注释）。
                VoiceTransition.Moved(
                    state,
                    VoiceState.LISTENING,
                    listOf(VoiceEffect.BeginListeningTurn),
                )

            VoiceEvent.UserStartedTalking -> ignored(state, "打断已在进行，重复上报")
            VoiceEvent.EndOfSpeech -> ignored(state, "打断中不判停 —— 那句话还没说完")
            VoiceEvent.FirstToken -> ignored(state, "打断中的首 token 属于已取消的请求")
            VoiceEvent.SpeechFinished -> ignored(state, "打断中的播完属于已停止的播放")

            // ★ 取消失败也必须收敛，否则永久卡在「麦克风开着但没人听」
            is VoiceEvent.Failed ->
                VoiceTransition.Moved(
                    state,
                    VoiceState.LISTENING,
                    listOf(VoiceEffect.BeginListeningTurn),
                )

            VoiceEvent.EmptyTurn -> ignored(state, "打断中没有本轮")
        }
    }

    private fun ignored(state: VoiceState, why: String): VoiceTransition =
        VoiceTransition.Ignored(state, why)
}
