package com.pocketagent.keylogic

/**
 * 一个 API Key 的「指纹」描述。
 *
 * ★ 这张表**不是事实来源**。它只是字符串匹配：厂商随时改前缀、新厂商随时出现，
 *   所以它必然**永远滞后于现实**。
 *
 *   因此识别结论的用途被严格限制为两件事：
 *     1. 用户粘贴 key 时，说一句「这看起来是 XX 的」——帮他确认自己没贴错
 *     2. 多个 provider 都能用时，预选一个候选
 *
 *   ⚠️ **不得**用于「拒绝保存」：一个没被收录的 key 不等于无效的 key。
 *   拿一张过时的表去拦用户，等于把我们自己跟不上时代变成用户的错，
 *   而且用户的第一反应是「这软件不支持这家」，然后卸载。
 *   （同一个坑见 keymgmt/CredentialMapping.kt 里 UNREACHABLE 与 INVALID
 *    必须分开的那段说明。）
 */
enum class KeyStrength {
    /**
     * 厂商独占前缀：看到一个基本就能定一家。例：`brz_`、`sk-ant-api03-`、`gsk_`。
     */
    SIGNATURE,

    /**
     * 多家共用的前缀，只能定一个**家族**。
     *
     * 例：`sk-` 后面跟一串 —— OpenAI、DeepSeek、Moonshot、智谱、
     * 硅基流动、DashScope、MiniMax 全都长这样，因为大家都实现了同一套
     * OpenAI 兼容协议。仅凭 key 本身**不可能**区分，只能结合用户填的 baseUrl。
     */
    FAMILY,

    /**
     * 只有长度和字符集，命中只说明「像 key」，**不说明是谁**。
     *
     * 例：裸 32 位十六进制 —— Azure OpenAI、Mistral、各种自建网关都用。
     * 这一档最容易骗人，所以 UI 上只能给「可能是」，不能给「就是」。
     */
    WEAK,
}

/** 这个 key 是干什么用的。用于决定「它能不能填到对话模型那一栏」。 */
enum class KeyKind(val displayName: String) {
    LLM("对话模型"),
    TTS("语音合成"),
    BOTH("对话与语音"),
    UNKNOWN("用途未知"),
}

/**
 * 一条指纹。
 *
 * @param pattern 必须整体匹配（正则自带 `^`/`$`）—— 用 `matches()` 而不是 `contains()`，
 *   否则一个 500 字符的 Base64 只要中间碰巧出现 `sk-` 就会被认成 OpenAI key。
 * @param authHeader 这个厂商要求的认证头写法。有的厂商**不认** `Authorization: Bearer`
 *   （例如走 `xi-api-key`），填错会把「配置问题」伪装成「key 无效」。
 * @param note 给开发者看的备注（不展示给用户）。
 */
data class ApiKeyFormat(
    val id: String,
    val displayName: String,
    val vendor: String,
    val kind: KeyKind,
    val pattern: Regex,
    val strength: KeyStrength,
    val providerId: String? = null,
    val authHeader: String? = null,
    val note: String = "",
) {
    /** 整体匹配。 */
    fun matches(rawKey: String): Boolean = pattern.matches(rawKey)
}

/**
 * 识别结论。
 *
 * ★ 四态而不是「对有/错」两态，是因为「不认识」和「不像 key」是两件完全不同的事：
 *   · [Unknown] = 我们没见过这种格式，但用户可能真的在用一家新厂商 → **放行**
 *   · [Rejected] = 这东西连 key 都不是（空的、带空格、贴了个网址）→ **拦下**
 *   把两者混成一个 false，会让新厂商的用户永远存不进去。
 */
sealed interface KeyRecognition {
    /** 唯一命中，且同族里已经挑出最具体的那条。 */
    data class Identified(val keyFormat: ApiKeyFormat) : KeyRecognition

    /** 只定到家族，具体哪家取决于用户填的 baseUrl。列表已按确定度排好序。 */
    data class Ambiguous(val formats: List<ApiKeyFormat>) : KeyRecognition

    /** 没收录，但看着像 key。这不是错误。 */
    data object Unknown : KeyRecognition

    /** 看着根本不像 key。 */
    data class Rejected(val reason: RejectReason) : KeyRecognition
}

/**
 * 输入本身的问题（与厂商表无关）。
 *
 * @param blocksSave 是否应该拦下「保存」。★ 只有**与厂商表无关**的理由才配拦：
 *   长度判定归 keymgmt 的 `CredentialInput.validateKey`（那里 MIN_KEY_LENGTH = 8），
 *   两处各写一套长度规则必然漂移，最后用户看到两个互相矛盾的提示。
 */
enum class RejectReason(val message: String, val blocksSave: Boolean) {
    EMPTY("还没填", blocksSave = true),
    TOO_SHORT("太短了，不像任何厂商发出来的 key", blocksSave = false),
    TOO_LONG("太长了，可能是把整段配置一起粘了进来", blocksSave = true),
    HAS_WHITESPACE("中间有空格或换行", blocksSave = true),
    NON_ASCII("含中文或全角字符", blocksSave = true),
    LOOKS_LIKE_URL("这看起来是一个网址，不是 key", blocksSave = true),
    LOOKS_LIKE_EMAIL("这看起来是一个邮箱，不是 key", blocksSave = true),
}
