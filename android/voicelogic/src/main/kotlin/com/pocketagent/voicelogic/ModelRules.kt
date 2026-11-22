package com.pocketagent.voicelogic

/**
 * **这条模型规则是从哪知道的。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要把"来源"也当成数据
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户 11-20 要的是「当 api 可以填入的时候让软件自动获取这个模型的
 * 使用规则，包括……支持的最大长度」。
 *
 * 但接口不总是回答。`tts-1` 的文档写死 4096，某个中转站可能只回了
 * 一个 `{"data":[]}` —— 这时候"上限是 4096"和"我们猜 2000"在行为上
 * 都是能用，**但对排障的意义完全不同**：
 *
 *  · 有点来源 → 段切得刚好，出现 400 说明接口说了谎，去查那一端；
 *  · 没来源 → 段切得保守，出现 400 说明只是我们不知道，改个配置就行。
 *
 * 把来源丢掉，这两种情况在日志里长得一模一样，用户只能瞎试。
 */
enum class RuleSource(val label: String, internal val rank: Int) {
    /** 真的问过接口，它回答了。最可信。 */
    PROBED("接口探测", 3),

    /** 上一次同一模型请求成功时观察到的。实测证据，比文档更可信。 */
    STORED("上次成功记录", 2),

    /** 代码里写死的已知厂商规则。文档说的，可能过时。 */
    BUILTIN("内置已知规则", 1),

    /** 什么都不知道时的兜底。见 [ModelRulesResolver.CONSERVATIVE_MAX_CHARACTERS]。 */
    CONSERVATIVE("保守默认", 0),
}

/** 一条规则里的哪一项。[ModelRules.origins] 用它记来源。 */
enum class RuleField(val label: String) {
    MAX_CHARACTERS("单次字符上限"),
    INSTRUCTIONS("说话方式说明"),
    SSML("SSML"),
    INLINE_TOKENS("内嵌标签"),
}

/**
 * 一条**不完整**的线索：只填自己知道的那几项，其余留 null。
 *
 * ⚠️ 用 null 表示"这条线索没提"，而不是用 false / 空集 ——
 *    否则"探测到了但明确不支持"和"根本没探测到"会被混成一件事，
 *    合并的时候后者会把前者的结论覆盖掉。
 */
data class RuleHint(
    val source: RuleSource,
    val maxCharacters: Int? = null,
    val instructions: Boolean? = null,
    val ssml: Boolean? = null,
    val inlineTokens: Set<String>? = null,
)

/**
 * 合并完成的一套规则。
 *
 * @param origins 每一项最终取自哪条线索。缺项表示没人提过，用了保守默认。
 */
data class ModelRules(
    val modelId: String,
    val maxCharacters: Int,
    val capabilities: VoiceCapabilities,
    val origins: Map<RuleField, RuleSource>,
) {
    /** 能不能表达声音标签。一件都不行的话，标签只会在文本里白加。 */
    val canExpressTags: Boolean
        get() = capabilities.instructions || capabilities.ssml || capabilities.inlineTokens.isNotEmpty()

    /** 给日志/设置页看的一句话。 */
    fun describe(): String = buildString {
        append(modelId)
        append("｜上限 ")
        append(maxCharacters)
        append("（")
        append(origins[RuleField.MAX_CHARACTERS]?.label ?: RuleSource.CONSERVATIVE.label)
        append("）")
        if (!canExpressTags) append("｜不支持声音标签")
    }
}

object ModelRulesResolver {

    /**
     * **什么都不知道时按多少字切。**
     *
     * ⚠️ 这里刻意给一个数，而不是留 null（"不切，让它自己拒"）。
     *    理由是这个默认值只在**出错**时才会被用到，两种错法的代价不一样：
     *      · 给 2000 → 多分几段，每段多一次首包延迟，声音完全正常；
     *      · 留 null → 超长的那一次直接 400，用户听到的是**没有声音**。
     *    前者是"慢一点"，后者是"哑了"。
     *
     * ⚠️ 也不给 4096：那是 OpenAI 的数值，中转站和国内厂商普遍更小
     *    （不少是 1024 / 2000）。按 4096 切，在这些厂商那里等于没切。
     */
    const val CONSERVATIVE_MAX_CHARACTERS = 2000

    /**
     * 按来源优先级逐项取第一个说过的值。
     *
     * ★ **逐项**而不是整条替换：真实情况经常是"接口告诉了我有哪些音色，
     *   但没说上限"。整条替换会为了拿到音色而丢掉已经知道的上限。
     */
    fun resolve(modelId: String, hints: List<RuleHint>): ModelRules {
        val ordered = hints.sortedByDescending { it.source.rank }

        val maxCharactersHint = ordered.firstOrNull { it.maxCharacters != null }
        val instructionsHint = ordered.firstOrNull { it.instructions != null }
        val ssmlHint = ordered.firstOrNull { it.ssml != null }
        val tokensHint = ordered.firstOrNull { it.inlineTokens != null }

        val origins = buildMap {
            maxCharactersHint?.let { put(RuleField.MAX_CHARACTERS, it.source) }
            instructionsHint?.let { put(RuleField.INSTRUCTIONS, it.source) }
            ssmlHint?.let { put(RuleField.SSML, it.source) }
            tokensHint?.let { put(RuleField.INLINE_TOKENS, it.source) }
        }

        val capabilities = VoiceCapabilities(
            instructions = instructionsHint?.instructions ?: false,
            ssml = ssmlHint?.ssml ?: false,
            inlineTokens = tokensHint?.inlineTokens ?: emptySet(),
            maxCharacters = maxCharactersHint?.maxCharacters ?: CONSERVATIVE_MAX_CHARACTERS,
        )

        return ModelRules(
            modelId = modelId,
            maxCharacters = capabilities.maxCharacters ?: CONSERVATIVE_MAX_CHARACTERS,
            capabilities = capabilities,
            origins = origins,
        )
    }

    /**
     * 把一次成功的请求记下来，供下次用。
     *
     * ⚠️ 只有**发送成功**时才记上限，而且要取"发出去的那一段的长度"，
     *    不是"我们以为的上限" —— 后者会把猜错的值记成实测值，
     *    从此错误变成"已验证"，再也不会被修正。
     */
    fun recordSuccess(modelId: String, sentCharacters: Int, previous: ModelRules?): RuleHint =
        RuleHint(
            source = RuleSource.STORED,
            maxCharacters = maxOf(sentCharacters, previous?.maxCharacters ?: 0).coerceAtLeast(1),
        )
}
