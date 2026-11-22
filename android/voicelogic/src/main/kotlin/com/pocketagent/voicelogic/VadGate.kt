package com.pocketagent.voicelogic

/**
 * VAD 门控的配置。
 *
 * ## 双阈值的理由（这不是过度设计）
 *
 * 单阈值在能量贴近阈值时会疯狂翻转：一帧 -36、下一帧 -34、再下一帧 -36……
 * 每个翻转都报一次「开口/说完」，于是一句话被切成 8 段，助理反复插嘴。
 * 双阈值（进入用 [speechOnDbfs]、退出用 [speechOffDbfs]）制造一段滞后区，
 * 滞后区内**不改变判断**，翻转频率立刻降到正常水平。
 *
 * ## 为什么「开口保持」比「判停静音」短
 *
 * · [speechStartHoldMs] = 120ms（6 帧）：短一点，因为打断的迟滞是用户能直接感到的
 *   （「我都开口了它还在说」）。一帧 20ms × 6 已经足够滤掉咳嗽、键盘、杯子的碰撞声。
 * · [endSilenceMs] = 300ms：长一点，因为用户在两句话之间必然有自然停顿
 *   （换气、思考）。这个值短了会把一句话切成两半，前半句被当成完整问题送去 LLM，
 *   得到答非所问的回答。
 *
 * ⚠️ **前提**：进来的信号必须是**已经过回声消除（AEC）的**。
 * 助理出声时麦克风会收到自己的声音，没做 AEC 的话 VAD 会把
 * 「助理自己在说话」判成「用户开口」，每一次回答都自我打断、永远说不完一句。
 * AEC 由 `:voice` 层（AudioRecord 的 AEC/NS 源或平台回声参考）负责，
 * 本模块不做补偿 —— 在这里加「出声时提高阈值」只会把问题藏起来，
 * 表现为「戴耳机时能打断、外放时不能」这种只在真机上才发现的怪相。
 */
data class VadConfig(
    /** 一帧音频的时长。16kHz / 16bit / 320 采样 = 20ms，是 AudioRecord 的常用块长 */
    val frameMs: Int = 20,

    /** 进入「有话」的门限（dBFS）。高于它开始累计持续时长 */
    val speechOnDbfs: Float = -35f,

    /** 退出「有话」的门限（dBFS）。必须低于 [speechOnDbfs]，否则没有滞后区 */
    val speechOffDbfs: Float = -45f,

    /** 连续有声多少毫秒才算「用户开口」。滤掉咳嗽/敲击等瞬时噪声 */
    val speechStartHoldMs: Int = 120,

    /** 连续静音多少毫秒才算「说完了」 */
    val endSilenceMs: Int = 300,
) {
    init {
        require(frameMs > 0) { "帧长必须为正，收到 " + frameMs }
        require(speechOffDbfs < speechOnDbfs) {
            // ★ 写反了（或写成相等）不会报错，只会让门控在边界疯狂翻转 ——
            // 症状是「一句话被切成十几段」，排查时几乎不会想到是配置写反
            "退出阈值必须低于进入阈值，否则不存在滞后区（on=" + speechOnDbfs +
                " off=" + speechOffDbfs + "）"
        }
        require(speechStartHoldMs >= 0) { "开口保持时长不能为负，收到 " + speechStartHoldMs }
        require(endSilenceMs > 0) { "判停静音必须为正，收到 " + endSilenceMs }
    }

    /**
     * 开口需要连续多少帧。
     *
     * 向上取整并且**至少 1 帧**：`speechStartHoldMs = 0` 若算出 0 帧，
     * 意味着「一帧有声就触发」—— 那等于没有保持窗口，第一声噪声就会打断。
     */
    val speechStartHoldFrames: Int = ceilDiv(speechStartHoldMs, frameMs).coerceAtLeast(1)

    /** 判停需要连续多少帧静音。同样至少 1 帧 */
    val endSilenceFrames: Int = ceilDiv(endSilenceMs, frameMs).coerceAtLeast(1)

    private companion object {
        /** 向上取整：宁可多等一帧，也不要提前触发（提前触发会误打断/误判停） */
        fun ceilDiv(value: Int, unit: Int): Int = (value + unit - 1) / unit
    }
}

/** VAD 对单帧的结论。帧级信号只有这三种，判「是不是打断」不在这里 —— 见 [VoiceEvent.UserStartedTalking] */
enum class VadSignal {
    /** 无变化 */
    NONE,

    /** 用户开始说话了（已持续够久）*/
    SPEECH_STARTED,

    /** 用户说完了（已静音够久）*/
    SPEECH_ENDED,
}

/**
 * 本地 VAD 门控：把逐帧能量变成「开口 / 说完」两个事件。
 *
 * 纯逻辑、无协程、无 Android 依赖 —— 喂帧就能测。
 *
 * ## 它刻意不知道通话状态
 *
 * [VadSignal.SPEECH_STARTED] 在 `LISTENING` 下是「用户开始提问」，
 * 在 `SPEAKING` 下是「用户打断」。这个区分由 [VoiceSessionMachine] 依据
 * [VoiceState.holdsTurn] 完成。若让 VAD 自己判断（例如传一个 `isSpeaking` 进来），
 * 判定规则就会散在两处，任何一处改动都可能让「打断」与「正常说话」错位。
 *
 * ## 一个容易被忽略的性质：**[VadSignal.SPEECH_STARTED] 只会报一次**
 *
 * 进入「有声」状态后，即使用户持续说话 10 秒，也只返回一次 SPEECH_STARTED，
 * 其余帧返回 [VadSignal.NONE]。否则上层每 20ms 收到一次「开口」，
 * 会把打断重复触发几十次。要等 [VadSignal.SPEECH_ENDED] 之后才会重新武装。
 *
 * ## -Inf 与 NaN 必须分开处理
 *
 * · `-Inf`（PCM 全 0 算出的 log10）= **真的静音**，正常累计静音帧，该判停就判停；
 * · `NaN`（信号不可信）= **既不是有声也不是静音**，一帧都不推进计数器。
 *
 * ⚠️ 不特殊处理 NaN 的后果比"误判说完"更隐蔽：NaN 与任何数比较都是 false，
 * 于是它在「够响吗」这一问上被判"不够响"（未激活时无害），在「够安静吗」这一问上
 * 被判**"不够安静"** —— 已激活状态下会走进 else 分支把 [silentFrames] 清零。
 * 结果不是把静音当噪声，而是**用户说完了永远等不到判停**：麦克风一直开着、
 * 状态一直停在「在听」，通话看起来"还在等用户说话"。
 * 这个症状会被当成阈值调得太高，几乎没人会想到根源是 NaN。
 */
class VadGate(val config: VadConfig = VadConfig()) {

    /** 当前是否处于「用户正在说话」的状态。宿主可读，用于 UI 提示 */
    var isSpeechActive: Boolean = false
        private set

    /** 连续有声帧数（未达阈值前累积） */
    private var voicedFrames: Int = 0

    /** 连续静音帧数（已激活后累积） */
    private var silentFrames: Int = 0

    /**
     * 送入一帧能量（dBFS）。
     *
     * @return 本帧是否跨过了一个边界
     */
    fun onFrame(rmsDbfs: Float): VadSignal {
        // ★ NaN 什么都不推进：见类注释。用 `isNaN()` 而不是 `<= 0` 之类的比较，
        //   因为 NaN 与任何数比较都是 false，漏判会让 NaN 静默走进静音分支
        if (rmsDbfs.isNaN()) return VadSignal.NONE

        return if (!isSpeechActive) {
            if (rmsDbfs > config.speechOnDbfs) {
                voicedFrames += 1
                if (voicedFrames >= config.speechStartHoldFrames) {
                    isSpeechActive = true
                    voicedFrames = 0
                    silentFrames = 0
                    VadSignal.SPEECH_STARTED
                } else {
                    VadSignal.NONE
                }
            } else {
                // 未达门限：**清零**而不是衰减。保持窗口要求「连续」，
                // 中间夹一帧安静就该重来，否则 10 帧里散落 6 帧有声也算开口
                voicedFrames = 0
                VadSignal.NONE
            }
        } else {
            if (rmsDbfs < config.speechOffDbfs) {
                silentFrames += 1
                if (silentFrames >= config.endSilenceFrames) {
                    isSpeechActive = false
                    voicedFrames = 0
                    silentFrames = 0
                    VadSignal.SPEECH_ENDED
                } else {
                    VadSignal.NONE
                }
            } else {
                // 落在滞后区（[speechOffDbfs] ≤ x ≤ [speechOnDbfs]）或仍很响：
                // 视为「还在说」，把静音计数清掉
                silentFrames = 0
                VadSignal.NONE
            }
        }
    }

    /**
     * 复位到初始状态。
     *
     * 打断后必须调用（由 [VoiceEffect.BeginListeningTurn] 触发）：
     * 打断那一瞬间 `voicedFrames` 可能正停在 4/6，不复位的话用户接着说
     * 两个词就又触发一次 SPEECH_STARTED —— 连环打断，助理一句完整的话都说不完。
     */
    fun reset() {
        isSpeechActive = false
        voicedFrames = 0
        silentFrames = 0
    }

    /** 只读快照，进日志用。别用它做判定 —— 判定只看 [onFrame] 的返回值 */
    fun snapshot(): VadSnapshot = VadSnapshot(
        isSpeechActive = isSpeechActive,
        voicedFrames = voicedFrames,
        silentFrames = silentFrames,
    )
}

/** VAD 内部计数的只读快照（诊断/日志用） */
data class VadSnapshot(
    val isSpeechActive: Boolean,
    val voicedFrames: Int,
    val silentFrames: Int,
)
