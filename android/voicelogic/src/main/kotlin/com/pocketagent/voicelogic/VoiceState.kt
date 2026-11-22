package com.pocketagent.voicelogic

/**
 * 语音会话状态 —— 架构文档 §4.5 那个状态机的节点。
 *
 * ```
 * IDLE ──开始──> LISTENING ──VAD 判停──> THINKING ──首 token──> SPEAKING
 *   ↑                ↑                                          │
 *   │                └────────── 用户开口（BargeIn）─────────────┘
 *   │                                 ↓
 *   └──结束通话──────────── INTERRUPTED ──清空队列+取消在途──> LISTENING
 * ```
 *
 * ## 为什么「可打断」必须长在状态里，而不是事后加个按钮
 *
 * 打断要做三件事：停播、取消在途请求、清空 TTS 队列。这三件**都是**副作用，
 * 而状态机是它们的唯一签发处。如果状态里没有 [INTERRUPTED]，就只有两种结局：
 *
 * · 状态机不认识打断 → 打断逻辑散落在 UI 与播放器里 → 漏掉「取消在途请求」
 *   → 助理嘴停了，但 LLM 的 token 还在往外烧（本项目 AgentBudget 记的就是这类账）；
 * · 或者有人用 `if (说话中) 停播` 兜住最显眼的一件，另外两件继续静默漏掉。
 *
 * 所以打断是**第一版就要在状态表里占一行**的横切关注点，和 AgentBudget 同理。
 *
 * ## 每个状态都显式回答两个问题
 *
 * 1. 麦克风要不要开着（[wantsMicrophone]）—— 关掉就再也收不到打断信号；
 * 2. 助理是否占据话语权（[holdsTurn]）—— 决定「用户开口」是打断还是本分。
 *
 * ⚠️ 这两个属性用枚举属性而非散落的 `if (state == SPEAKING || ...)`：
 * 后者每加一个状态就要去改判据，忘改就是静默误判，而漏写枚举参数会编译失败。
 */
enum class VoiceState(
    /** 给人看的名字，日志与调试面板共用；不参与任何判定 */
    val displayName: String,
    /**
     * 该状态下麦克风是否必须保持开启。
     *
     * ⚠️ [THINKING] 也必须是 true —— 用户等回答时想插话（「算了」「换个说法」）
     * 是最常见的打断时机。这里的麦克风一关，[VoiceEvent.UserStartedTalking]
     * 就永远不会到来，打断功能等于不存在，而且没有任何报错。
     */
    val wantsMicrophone: Boolean,
    /**
     * 助理是否占据话语权。true 时用户开口 = 打断；false 时用户开口是正常输入。
     *
     * [THINKING] 为 true：还没出声，但请求已经在途，用户有权把它掐掉。
     */
    val holdsTurn: Boolean,
) {
    /** 空闲：没在通话，不采集、不播放 */
    IDLE(displayName = "空闲", wantsMicrophone = false, holdsTurn = false),

    /**
     * 在听：用户正在说、或刚说完还没判停。
     *
     * 此时用户继续说属于本分，不是打断 —— 见 [VoiceState.holdsTurn]。
     */
    LISTENING(displayName = "在听", wantsMicrophone = true, holdsTurn = false),

    /** 在想：ASR 已判停，等 LLM 首 token。请求在途，可被打断 */
    THINKING(displayName = "在想", wantsMicrophone = true, holdsTurn = true),

    /** 在说：TTS 出声中。可被打断 */
    SPEAKING(displayName = "在说", wantsMicrophone = true, holdsTurn = true),

    /**
     * 打断中：过渡态，只等 [VoiceEvent.EffectsSettled]。
     *
     * ★ 这个状态**不承载业务**，它的唯一作用是让「打断"发生了一半"」这件事
     * 在状态表里可见 —— 停播已经生效、但取消请求还没回来的那一小段时间，
     * 是一个真实存在、且必须被收敛掉的状态。没有它，这段就只能藏在
     * BargeInController 的协程里，没人看得见。
     *
     * ⚠️ 收敛出口有两条：[VoiceEvent.EffectsSettled]（正常）与
     * [VoiceEvent.Failed]（取消失败/超时）。**只留前者会卡死** ——
     * 取消在途请求失败时若不收敛回 [LISTENING]，整通通话就永久停在
     * 「麦克风开着、但没人听」的状态，而日志里只会有一条取消超时。
     */
    INTERRUPTED(displayName = "打断中", wantsMicrophone = true, holdsTurn = false),
    ;

    /** 是否处于通话中（[IDLE] 之外都算） */
    val inCall: Boolean
        get() = this != IDLE

    /** 该状态下是否正在出声 —— 决定 [VoiceEffect.StopPlayback] 有没有实际内容 */
    val isAudible: Boolean
        get() = this == SPEAKING
}

/**
 * 语音会话可接受的事件。
 *
 * 与 [VoiceState] 分离的理由同 `overlaylogic`：迁移规则是纯逻辑，可以离线穷举；
 * 而「事件从哪来」（VAD 回调、TTS 流、网络回调）依赖 Android 与网络，测不了。
 *
 * ⚠️ **乱序与过期事件是常态，不是异常**。用户打断时，上一轮 TTS 的
 * [SpeechFinished] 可能几百毫秒后才到；VAD 的 [EndOfSpeech] 也可能在
 * [THINKING] 期间重复上报（抖动）。所以每个事件在每种状态下都必须有
 * **明确的**归宿，而不是「按常理不会发生」就当它不发生。
 */
sealed interface VoiceEvent {
    /** 用户开始通话 */
    data object StartCall : VoiceEvent

    /** 用户挂断（或系统强制结束，如来电抢占焦点） */
    data object StopCall : VoiceEvent

    /** VAD 判定用户说完了 */
    data object EndOfSpeech : VoiceEvent

    /** LLM 吐出首个 token —— 可以去拿 TTS 首包了 */
    data object FirstToken : VoiceEvent

    /** TTS 播完（播放队列已空且音频输出结束） */
    data object SpeechFinished : VoiceEvent

    /**
     * VAD 判定用户开口了。
     *
     * ★ 注意这里的语义是**中性的**：「用户开始说话」。
     * 它是不是「打断」，由当前状态决定（[VoiceState.holdsTurn]）——
     * VAD 不该知道通话状态，状态机也不该知道 dBFS 阈值。分层在这里。
     */
    data object UserStartedTalking : VoiceEvent

    /** 打断产生的副作用（停播/取消/清队列）都已处理完 */
    data object EffectsSettled : VoiceEvent

    /**
     * 本轮没有可说的内容就结束了（LLM 返回空、或只剩被剥掉的标签）。
     *
     * ★ 这是个防卡死出口：没有它，[THINKING] 会等到一个永远不来的
     * [FirstToken]，表现为「助理一直转圈、麦克风开着、但什么都不发生」。
     */
    data object EmptyTurn : VoiceEvent

    /** 任一环节出错（ASR 断流、LLM 报错、TTS 404…）。[reason] 只用于日志与提示 */
    data class Failed(val reason: String) : VoiceEvent
}

/**
 * 需要宿主（Android 层）执行的副作用。
 *
 * 纯逻辑层只声明「要做什么」，不实际去做 —— 这样每条副作用都能在离线测试里
 * 被断言，而不是靠「真机上应该会响」。
 *
 * ⚠️ **所有副作用都必须幂等**：同一份效果可能因为重入被执行两次
 * （例如 [StopPlayback] 在没出声的状态下被调用）。幂等由宿主保证，
 * 逻辑层不为此加状态判断 —— 那会把「该不该停」的判定扩散到两处。
 */
sealed interface VoiceEffect {
    /** 打开麦克风并启动 ASR 流 */
    data object StartCapture : VoiceEffect

    /** 关闭麦克风、停止 ASR */
    data object StopCapture : VoiceEffect

    /**
     * 开始新的一轮「听」：清空上一轮的 ASR 缓冲、复位 VAD 的计数器。
     *
     * ⚠️ 不复位 VAD 的后果很具体：打断瞬间 `voicedFrames` 还在计数，
     * 用户接着说的第一句话会被立刻判成「又一次开口」，形成连环打断。
     */
    data object BeginListeningTurn : VoiceEffect

    /** 把 ASR 的最终文本交给 LLM（拉起请求） */
    data object StartThinking : VoiceEffect

    /** 接上 TTS 流并开始出声 */
    data object StartPlayback : VoiceEffect

    /** 立即停止出声（打断三件事之一） */
    data object StopPlayback : VoiceEffect

    /**
     * 取消在途的 LLM 请求（打断三件事之二）。
     *
     * ★ 这一条是三件事里**最容易漏**的：停播有肉眼可见的效果，清队列有队列长度，
     * 而「请求还在跑」完全没有症状 —— 除了账单和发热。
     */
    data object CancelInflight : VoiceEffect

    /** 清空待播的 TTS 队列（打断三件事之三） */
    data object ClearTtsQueue : VoiceEffect
}

/**
 * 一次事件处理的结论。
 *
 * 用自定义类型而非 `Result<T>`：迁移被拒绝是**正常业务情形**
 * （过期事件、重复事件），不是异常 —— 用 `Result` 会诱导调用方写 try/catch，
 * 而真正需要的是「把这个 reason 记进日志/上报」。
 *
 * ⚠️ [Ignored] 必须带 [Ignored.reason]：静默丢弃事件是本项目的头号 bug 形态。
 * 有了 reason，「为什么这次开口没打断」才能在日志里被回答，而不是靠猜。
 */
sealed interface VoiceTransition {
    /** 迁移的起点。事件被忽略时也有值，方便调用方统一记录 */
    val from: VoiceState

    /** 迁移成功 */
    data class Moved(
        override val from: VoiceState,
        val to: VoiceState,
        val effects: List<VoiceEffect>,
    ) : VoiceTransition

    /** 当前状态下该事件无意义，状态不变 */
    data class Ignored(
        override val from: VoiceState,
        val reason: String,
    ) : VoiceTransition
}
