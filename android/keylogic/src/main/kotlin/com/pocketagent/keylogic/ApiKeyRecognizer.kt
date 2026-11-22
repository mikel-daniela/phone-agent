package com.pocketagent.keylogic

/**
 * 把用户粘贴的一串字符识别成「像是哪家的 API Key」。
 *
 * ★★ 这个类最重要的一条约束（改动前请先读）：
 *
 *   **识别结论只能用于提示与预选，绝不能用于拒绝保存。**
 *
 *   理由：格式库是**枚举**，而现实是**开放**的。任何人今天就可以开一家新厂商、
 *   发一种新前缀的 key，我们的表里没有 —— 那时用户粘贴的是完全正确的 key。
 *   如果我们拿这张表当门禁，用户会看到「不支持的 key 格式」，然后卸载软件。
 *
 *   ⇒ 只有**与厂商表无关**的理由才配拦下保存（空、含空格、像网址……），
 *     见 [RejectReason.blocksSave]。
 *
 * ★ 为什么「不认识」要单独成一个状态而不是当成错误：见 [KeyRecognition]。
 *
 * ⚠️ 判错的代价（为什么这个类必须有确定性测试）：
 *   · 认错一家 → 用户照着提示去改一个本来就对的配置，改完还是连不上，
 *     最后他会认为是自己的问题
 *   · 匹配顺序写错 → `sk-ant-api03-…` 被泛 `sk-` 抢先认成「OpenAI 兼容」，
 *     于是「结果取决于列表里谁先写」—— 后加一条规则就可能悄悄改变旧结论，
 *     而这种改动没有任何测试会红
 *   ⇒ 所以档内排序有明确的、可测的键（[literalPrefixLength]），不靠列表顺序。
 */
object ApiKeyRecognizer {

    /**
     * 短于这个长度不像任何厂商的 key。
     *
     * ⚠️ 与 keymgmt 的 `CredentialInput.MIN_KEY_LENGTH`（同样是 8）保持一致，
     * 但**不由本类拦下保存** —— 两处各写一套长度规则必然漂移，
     * 最后用户会看到两个互相矛盾的提示。长度判定只归那边。
     */
    const val MIN_KEY_LENGTH = 8

    /**
     * 长于这个长度一定不是 key（多半是把整段配置文件/JSON 一起粘进来了）。
     *
     * 512 是刻意给宽的：已知最长的 key（OpenAI 项目级、JWT）也远不到这个数。
     */
    const val MAX_KEY_LENGTH = 512

    /** 匹配顺序：确定性从高到低。命中一档就不再往下看。 */
    private val STRENGTH_ORDER = listOf(
        KeyStrength.SIGNATURE,
        KeyStrength.FAMILY,
        KeyStrength.WEAK,
    )

    /**
     * 识别一个 key。
     *
     * 输入会先 `trim()` —— 粘贴带尾随换行是常态，那不是用户的错，不该报错。
     */
    fun recognize(raw: String): KeyRecognition {
        val key = raw.trim()

        // ── 第一段：与厂商表无关的输入问题 ──────────────────────────────
        if (key.isEmpty()) return KeyRecognition.Rejected(RejectReason.EMPTY)

        // 空白放在长度之前：`sk-abc def` 只是断成两截，说「中间有空格」比说
        // 「太短」准得多 —— 而且它指出的是**用户能自己修**的那件事。
        if (key.any { it.isWhitespace() }) {
            return KeyRecognition.Rejected(RejectReason.HAS_WHITESPACE)
        }

        if (key.any { it.code < 33 || it.code > 126 }) {
            return KeyRecognition.Rejected(RejectReason.NON_ASCII)
        }

        if (key.length > MAX_KEY_LENGTH) {
            return KeyRecognition.Rejected(RejectReason.TOO_LONG)
        }

        if (looksLikeUrl(key)) return KeyRecognition.Rejected(RejectReason.LOOKS_LIKE_URL)
        if (looksLikeEmail(key)) return KeyRecognition.Rejected(RejectReason.LOOKS_LIKE_EMAIL)

        if (key.length < MIN_KEY_LENGTH) {
            return KeyRecognition.Rejected(RejectReason.TOO_SHORT)
        }

        // ── 第二段：分档匹配，从确定性最高的档开始 ──────────────────────
        for (strength in STRENGTH_ORDER) {
            val hits = ApiKeyCatalog.byStrength(strength).filter { it.matches(key) }
            if (hits.isEmpty()) continue

            val ranked = rank(hits)

            // ★ 签名级：前缀是某家独占的，一条就够定死了。
            // ★ 家族级 / 弱级：即使只命中一条，那条本身也代表「一片厂商」
            //   （openai-compat 这一条就覆盖十几家），所以仍然是 Ambiguous。
            //   用「命中条数」当确定性判据会在这里说错话。
            return if (strength == KeyStrength.SIGNATURE) {
                KeyRecognition.Identified(ranked.first())
            } else {
                KeyRecognition.Ambiguous(ranked)
            }
        }

        return KeyRecognition.Unknown
    }

    /**
     * 档内排序：字面前缀越长越具体，越靠前。
     *
     * ★ 为什么不用「正则总长度」当具体度：那只是巧合 —— 有人为了可读性把正则
     *   拆成常量、加个注释、放宽字符类，长度就变了，结论也跟着变，
     *   而这种改动看起来完全是「无害整理」。字面前缀长度是**语义**上的具体度。
     *
     * 兜底用 id 字典序，保证同样的输入永远得到同样的顺序（可测）。
     */
    fun rank(formats: List<ApiKeyFormat>): List<ApiKeyFormat> = formats.sortedWith(
        compareByDescending<ApiKeyFormat> { literalPrefixLength(it.pattern.pattern) }
            .thenByDescending { it.pattern.pattern.length }
            .thenBy { it.id },
    )

    /**
     * 数一个正则开头那段**字面**字符有多长（去掉 `^`）。
     *
     * `^sk-ant-api03-[…]` → 13、`^sk-ant-[…]` → 9、`^sk-[…]` → 3。
     * 遇到第一个元字符就停 —— 从那里往后就不再有「独有信息」了。
     *
     * 例：`^brz_[A-Za-z0-9_-]{12,}$` → 4（`brz_`）。
     */
    fun literalPrefixLength(pattern: String): Int {
        val body = pattern.removePrefix("^")
        var count = 0
        for (ch in body) {
            // 遇到正则元字符就停：从这里开始是「一类字符」，不再指向具体厂商。
            if (ch in "\\[](){}|*+?.\$") break
            count++
        }
        return count
    }

    /**
     * 给用户看的一句话。
     *
     * ⚠️ [KeyRecognition.Unknown] 的文案必须明确说「可以照常保存」——
     * 否则用户看到「未识别」会以为存不了，去改一个本来正确的 key。
     */
    fun hint(recognition: KeyRecognition): String = when (recognition) {
        is KeyRecognition.Identified ->
            "这看起来是 " + recognition.keyFormat.displayName + " 的 key（" +
                recognition.keyFormat.kind.displayName + "）"

        is KeyRecognition.Ambiguous -> {
            val names = recognition.formats.joinToString("、") { it.displayName }
            "这像是 " + names + " 的 key，具体是哪一家要看你填的 Base URL"
        }

        KeyRecognition.Unknown ->
            "没见过这种格式。可以照常保存 —— 格式库只做提示，不做校验。"

        is KeyRecognition.Rejected -> recognition.reason.message
    }

    /** 是否该拦下「保存」。★ 除输入本身的问题外，一律不拦。 */
    fun shouldBlockSave(recognition: KeyRecognition): Boolean =
        recognition is KeyRecognition.Rejected && recognition.reason.blocksSave

    /**
     * 打进日志或显示在界面上的掩码：前 [head] 位 + 省略号 + 后 [tail] 位。
     *
     * 太短（去掉头尾就没了）返回 null —— 那时候遮了也等于没遮，
     * 不如让调用方自己决定显示什么。
     */
    fun maskForDisplay(raw: String, head: Int = 6, tail: Int = 4): String? {
        val key = raw.trim()
        if (head < 0 || tail < 0) return null
        if (key.length <= head + tail + 1) return null
        return key.take(head) + "…" + key.takeLast(tail)
    }

    /** 粘贴网址是最常见的误操作（用户会把 baseUrl 粘到 key 那一栏）。 */
    private fun looksLikeUrl(key: String): Boolean =
        key.contains("://") || key.startsWith("www.", ignoreCase = true)

    private fun looksLikeEmail(key: String): Boolean = EMAIL.matches(key)

    private val EMAIL = Regex("^[^@]{1,64}@[^@]{1,255}\\.[A-Za-z]{2,}\$")
}
