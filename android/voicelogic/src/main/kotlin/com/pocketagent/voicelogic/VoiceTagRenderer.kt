package com.pocketagent.voicelogic

/**
 * 把一个 [VoiceTag] 列表渲染成厂商真能吃进去的东西。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要有降级链，而不是"能发就发、不能发就算了"
 * ═══════════════════════════════════════════════════════════════
 *
 * 同一句「[叹气]算了」，在不同厂商面前有四种命运：
 *
 *  1. 模型懂人话（GPT-4o-audio、Qwen-Omni 一类）
 *     → 旁挂一段说明："用疲惫的语气，中间叹一口气"
 *  2. 认 SSML（Azure、部分开源）
 *     → `<speak>算了<break time="900ms"/>随你吧</speak>`
 *  3. 只认自家魔数（CosyVoice 的 `[uv_break]`）
 *     → 插 token
 *  4. 什么都不认（`tts-1` 这种）
 *     → 只剩纯文本
 *
 * 如果每一档都写成 `if (cap.xx) {...}` 散在各处，第 4 档就会**安静地**
 * 把标签丢掉 —— 用户听到的是平的声音，还以为是模型不行。
 * 这正是项目里反复出现的「安静地少做一件事」。
 *
 * ⇒ 三个约束：
 *  · 档位由 [TtsRenderMode] 表达，判定只发生在 [chooseMode] 一处；
 *  · 丢掉的标签必须进 [VoiceRender.droppedTags]，让上层能说出来；
 *  · 超长必须报 [VoiceRender.overflow]，不能让厂商那边报 400 再回来猜。
 */
enum class TtsRenderMode(val label: String) {
    /** 旁挂一段自然语言说明，正文保持纯净。能力最强，也最通用。 */
    INSTRUCTIONS("说话方式说明"),

    /** 正文变成 SSML。韵律精确，但要求厂商真的解析。 */
    SSML("SSML 韵律标记"),

    /** 正文里插厂商自家的魔数 token。 */
    INLINE_TAG("内嵌标签"),

    /** 什么都做不了，只能靠语速音高这两个数值参数表达。 */
    PLAIN("纯文本"),
}

/**
 * 渲染器需要的厂商能力。
 *
 * ⚠️ 刻意不复用 `:provider:api` 的 `TtsCapability` —— 那个枚举是**对外契约**
 *    的一部分，每加一种表达方式都要动接口层。这里的字段只是"渲染器关心什么"，
 *    由 provider 侧做一次映射即可，将来加能力不会推着契约一起改。
 */
data class VoiceCapabilities(
    /** 支不支持一段独立的自然语言说话方式说明。 */
    val instructions: Boolean = false,
    /** 正文按 SSML 解析。 */
    val ssml: Boolean = false,
    /** 认得哪些内嵌 token（填 [VoiceTag.inline] 的值）。 */
    val inlineTokens: Set<String> = emptySet(),
    /** 单次请求的字符上限。null = 厂商没公开，交给它自己拒。 */
    val maxCharacters: Int? = null,
) {
    /** 一件表达方式都不支持（`tts-1` 这类）。 */
    val isPlainOnly: Boolean
        get() = !instructions && !ssml && inlineTokens.isEmpty()
}

/**
 * 渲染结果。
 *
 * ⚠️ [droppedTags] 和 [overflow] 不是调试信息，是**要显示给用户的东西**。
 *    前者说"你的这句叹气，这个音色表达不出来"，后者说"太长了，发不出去"。
 */
data class VoiceRender(
    /** 最终送给 TTS 的正文（可能是纯文本、SSML 或带 token 的文本）。 */
    val text: String,
    /** 旁挂的说话方式说明。只有 [TtsRenderMode.INSTRUCTIONS] 档才有。 */
    val instructions: String?,
    val mode: TtsRenderMode,
    /** 有标签但表达不出来、被降级丢掉的。空列表 = 全部表达出来了。 */
    val droppedTags: List<VoiceTag>,
    /** 厂商的字符上限。null = 未公开。 */
    val limit: Int?,
    /** 超出上限多少字符。0 = 没超。 */
    val overflow: Int,
) {
    val withinLimit: Boolean get() = overflow == 0

    /** 至少丢了一个标签 —— 上层应当明确告诉用户，而不是让他听出差别去猜。 */
    val isDegraded: Boolean get() = droppedTags.isNotEmpty()

    /** 完整可观测的一句话，给日志和测试用。 */
    fun logLine(): String = buildString {
        append(mode.label)
        append("｜丢 ")
        append(droppedTags.size)
        append(" 个")
        if (!withinLimit) {
            append("｜超长 ")
            append(overflow)
            append(" 字")
        }
    }
}

object VoiceTagRenderer {

    /**
     * 按能力选档。
     *
     * ★ 优先级是固定的：说明 > SSML > 内嵌 > 纯文本。
     *   理由不是"说明更好听"，而是**说明能表达标签之间的关系**
     *   （"说完这句停一下再叹气"），SSML 只能表达单点，内嵌 token 只认白名单。
     */
    fun chooseMode(capabilities: VoiceCapabilities): TtsRenderMode = when {
        capabilities.instructions -> TtsRenderMode.INSTRUCTIONS
        capabilities.ssml -> TtsRenderMode.SSML
        capabilities.inlineTokens.isNotEmpty() -> TtsRenderMode.INLINE_TAG
        else -> TtsRenderMode.PLAIN
    }

    /**
     * 渲染。
     *
     * @param spoken 已剥离标签的纯净文本（[VoiceParse.spoken]）
     * @param tags 整条回复的标签（决定整体基调）
     * @param placements 标签的位置（内嵌模式要插回去）
     */
    fun render(
        spoken: String,
        tags: List<VoiceTag>,
        placements: List<PlacedTag>,
        capabilities: VoiceCapabilities,
    ): VoiceRender {
        val mode = chooseMode(capabilities)
        val dropped = mutableListOf<VoiceTag>()

        val text: String
        val instructions: String?
        when (mode) {
            TtsRenderMode.INSTRUCTIONS -> {
                text = spoken
                instructions = VoiceInstructions.build(tags)
            }

            TtsRenderMode.SSML -> {
                text = buildSsml(spoken, placements, dropped)
                instructions = null
            }

            TtsRenderMode.INLINE_TAG -> {
                text = buildInline(spoken, placements, capabilities.inlineTokens, dropped)
                instructions = null
            }

            TtsRenderMode.PLAIN -> {
                // 一个都表达不出来 —— 但整条回复的情绪至少还能靠语速音高近似，
                // 那部分由 SpeechRequest 的 rate/pitch 承担，这里如实记账。
                dropped += tags
                text = spoken
                instructions = null
            }
        }

        // ★ 这里量的是**渲染之后**的长度：SSML 的 <break> 有 19 个字符，
        //   4096 的额度会被吃掉不少。按原文量会漏判，等厂商报 400 才发现。
        val limit = capabilities.maxCharacters
        val overflow = if (limit == null) 0 else (text.length - limit).coerceAtLeast(0)

        // ★ 只给了标签、没给位置的，一样表达不出来（不知道该插在哪个字后面）。
        //   不在这里补一笔，上层就会以为"全部表达成功了"。
        val placedTags = placements.mapTo(HashSet()) { it.tag }
        dropped += tags.filter { it !in placedTags }

        return VoiceRender(text, instructions, mode, dropped.distinct(), limit, overflow)
    }

    /**
     * SSML 版本。
     *
     * ⚠️ 文本必须转义。用户真的会发 `1 < 2`，也真的会发 `&`，
     *    不转义就是整个请求 400 —— 而且错误信息只会说"malformed SSML"。
     *    转义顺序也必须先 `&` 再尖括号，否则 `&lt;` 会被二次转义成 `&amp;lt;`。
     */
    private fun buildSsml(
        spoken: String,
        placements: List<PlacedTag>,
        dropped: MutableList<VoiceTag>,
    ): String {
        val builder = StringBuilder(spoken.length + 48)
        var cursor = 0
        for (placed in placements.sortedBy { it.at }) {
            val at = placed.at.coerceIn(cursor, spoken.length)
            builder.append(escape(spoken.substring(cursor, at)))
            val markup = placed.tag.ssml
            if (markup == null) {
                dropped += placed.tag
            } else {
                builder.append(markup)
            }
            cursor = at
        }
        builder.append(escape(spoken.substring(cursor)))
        return "<speak>" + builder + "</speak>"
    }

    /**
     * 内嵌 token 版本。
     *
     * ★ 从后往前插 —— 从前往后插会让后面每个位置都往前错，
     *   标签就落到别的字中间去了。
     */
    private fun buildInline(
        spoken: String,
        placements: List<PlacedTag>,
        tokens: Set<String>,
        dropped: MutableList<VoiceTag>,
    ): String {
        val builder = StringBuilder(spoken)
        for (placed in placements.sortedByDescending { it.at }) {
            val token = placed.tag.inline
            if (token == null || token !in tokens) {
                dropped += placed.tag
                continue
            }
            builder.insert(placed.at.coerceIn(0, builder.length), token)
        }
        return builder.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * 按厂商上限切成多段。
     *
     * ⚠️ 切点只能落在**句子边界**。硬按字符数切会把一个词劈成两半 ——
     *    前半段末尾是一个断掉的音节，听起来像卡了。所以：
     *      · 先按句末标点/换行切句子；
     *      · 单句本身超长（用户发了一大段没标点的话）才退化成硬切。
     */
    fun splitByLimit(text: String, limit: Int): List<String> {
        require(limit > 0) { "上限必须是正数，拿到的是 $limit" }
        if (text.length <= limit) return if (text.isEmpty()) emptyList() else listOf(text)

        val pieces = mutableListOf<String>()
        val sentence = StringBuilder()
        for (ch in text) {
            sentence.append(ch)
            // 句末标点后面才敢断句；换行也是天然的停顿点。
            val isBoundary = ch in SENTENCE_END || ch == '\n'
            if (isBoundary && sentence.length >= limit / 2) {
                pieces += sentence.toString()
                sentence.clear()
            }
        }
        if (sentence.isNotEmpty()) pieces += sentence.toString()

        // 合并到接近上限但不超 —— 每句单独发包会多付一次首包的延迟。
        val merged = mutableListOf<String>()
        var current = StringBuilder()
        for (piece in pieces) {
            if (piece.length > limit) {
                if (current.isNotEmpty()) {
                    merged += current.toString()
                    current = StringBuilder()
                }
                var offset = 0
                while (offset < piece.length) {
                    val end = (offset + limit).coerceAtMost(piece.length)
                    merged += piece.substring(offset, end)
                    offset = end
                }
                continue
            }
            if (current.length + piece.length > limit) {
                merged += current.toString()
                current = StringBuilder()
            }
            current.append(piece)
        }
        if (current.isNotEmpty()) merged += current.toString()
        return merged.filter { it.isNotEmpty() }
    }

    private val SENTENCE_END = setOf('。', '！', '？', '；', '…', '.', '!', '?', ';')
}

/**
 * 给"懂人话"的语音模型写说话方式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是英文
 * ═══════════════════════════════════════════════════════════════
 *
 * 这批模型的指令跟随训练集以英文为主（OpenAI 自己的文档示例就是
 * `Speak in a cheerful and positive tone.`）。用中文写"用温柔的语气"
 * 它们也懂，但**稳定性明显差一档** —— 同一句话有时照做有时忽略。
 *
 * ⇒ 指令用英文，被念的正文一个字都不动。两件事分开。
 *
 * ⚠️ 最后一句 `Do not read any bracketed words aloud.` 不是装饰：
 *    模型见过太多带方括号的文本，会把它们当内容念出来。
 */
object VoiceInstructions {

    fun build(tags: List<VoiceTag>): String? {
        if (tags.isEmpty()) return null
        val lines = mutableListOf<String>()

        // 整体情绪只取一个 —— 一条回复里的情绪会变，但**整条的调门必须一开始就定**。
        // 否则最后一句的悲伤会拖累前面所有本该轻快的句子。
        VoiceTag.dominantTone(tags)?.let { lines += "- overall emotion: ${english(it)}" }

        val byKind = tags.groupBy { it.kind }
        byKind[VoiceTagKind.TONE]?.let { tones ->
            lines += "- tone: " + tones.distinct().joinToString(", ") { english(it) }
        }
        byKind[VoiceTagKind.VOCAL]?.let { vocals ->
            lines += "- voice quality: " + vocals.distinct().joinToString(", ") { english(it) }
        }
        byKind[VoiceTagKind.RATE]?.let { rates ->
            lines += "- pace: " + rates.distinct().joinToString(", ") { english(it) }
        }

        // 呼吸和副语言要说**几次** —— "sigh somewhere" 会被均匀摊开，
        // 而真实的人是在某个具体的字后面才叹那口气。
        val audible = tags.filter {
            it.kind == VoiceTagKind.BREATH || it.kind == VoiceTagKind.PARALINGUISTIC
        }
        if (audible.isNotEmpty()) {
            val counts = audible.groupingBy { it }.eachCount()
            lines += "- audible events at the marked spots: " +
                counts.entries.joinToString(", ") { "${english(it.key)} x${it.value}" }
        }

        val pauses = tags.count { it.kind == VoiceTagKind.PAUSE }
        if (pauses > 0) lines += "- $pauses short pause(s), in the places marked in the text"

        if (lines.isEmpty()) return null
        return buildString {
            appendLine("Speak the text below with these vocal directions:")
            lines.forEach { appendLine(it) }
            appendLine("Keep the wording exactly as written. Do not read any bracketed words aloud.")
        }.trim()
    }

    /**
     * 中文标签 → 英文指令。
     *
     * ⚠️ 用 token 而不是 label：label 是给人看的中文（「撒娇」），
     *    送进指令里模型可能理解成"扮演撒娇"而不是"这样发声"。
     */
    internal fun english(tag: VoiceTag): String = when (tag) {
        VoiceTag.HAPPY -> "cheerful"
        VoiceTag.SAD -> "sad"
        VoiceTag.ANGRY -> "angry, holding back"
        VoiceTag.SURPRISED -> "surprised"
        VoiceTag.SHY -> "shy, a little flustered"
        VoiceTag.CALM -> "calm"
        VoiceTag.TIRED -> "tired"
        VoiceTag.TENDER -> "tender"
        VoiceTag.COLD -> "cold and flat"
        VoiceTag.URGENT -> "urgent"
        VoiceTag.LAZY -> "lazy, drawn out"
        VoiceTag.COQUETTISH -> "playfully coy"
        VoiceTag.SERIOUS -> "serious"
        VoiceTag.TEASING -> "teasing, half-smiling"
        VoiceTag.WHISPER -> "whispered"
        VoiceTag.BREATHY -> "breathy"
        VoiceTag.SHOUT -> "raised voice"
        VoiceTag.HUM -> "humming softly"
        VoiceTag.TREMBLING -> "voice trembling"
        VoiceTag.MUFFLED -> "muffled"
        VoiceTag.INHALE -> "a quick intake of breath"
        VoiceTag.EXHALE -> "a slow exhale"
        VoiceTag.SIGH -> "a sigh"
        VoiceTag.PANT -> "panting"
        VoiceTag.HOLD_BREATH -> "holding breath"
        VoiceTag.LAUGH -> "a short laugh"
        VoiceTag.GIGGLE -> "a small giggle"
        VoiceTag.SOB -> "a sob caught in the throat"
        VoiceTag.COUGH -> "a cough"
        VoiceTag.THROAT_CLEAR -> "clearing the throat"
        VoiceTag.TSK -> "a soft tut"
        VoiceTag.PAUSE -> "a brief pause"
        VoiceTag.LONG_PAUSE -> "a long pause"
        VoiceTag.FASTER -> "faster"
        VoiceTag.SLOWER -> "slower"
    }
}
