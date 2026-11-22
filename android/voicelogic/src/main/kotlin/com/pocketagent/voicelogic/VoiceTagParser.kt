package com.pocketagent.voicelogic

/**
 * 把一句话切成**要念的**和**怎么念的**。
 *
 * 输入是模型吐出来的原文，输出两份：一份给屏幕（标签已剥干净），
 * 一份给语音（标签带着它出现的位置）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 位置为什么必须保留
 * ═══════════════════════════════════════════════════════════════
 *
 * 「[叹气]算了，随你吧。」和「算了，[叹气]随你吧。」是两种人说的话。
 * 前者是已经放下，后者是还在犹豫。
 *
 * 所以不能只收一个标签列表 —— 那样两条不同的口气会被渲染成同一段音频。
 * [PlacedTag.at] 是标签在**剥离后文本**里的字符下标，支持内嵌标签的
 * 厂商靠它把 `[uv_break]` 插回原来的位置。
 */
data class VoiceParse(
    /** 剥干净、可以直接上屏、也可以直接念的文本。可能为空（整条只有标签）。 */
    val spoken: String,
    /** 按出现顺序排好的标签。渲染整体基调用它。 */
    val tags: List<VoiceTag>,
    /** 标签在原句里的位置，`at` 是 [spoken] 的字符下标。 */
    val placements: List<PlacedTag>,
) {
    /** 只有标签没有字。没有可念的东西 —— 调用方不要拿它去发 TTS。 */
    val isSilentOnly: Boolean get() = spoken.isBlank() && tags.isNotEmpty()

    /** 什么都没识别出来（也没有字）。空输入通常意味着上一次回复被清空了。 */
    val isEmpty: Boolean get() = spoken.isBlank() && tags.isEmpty()
}

/** 一个标签 + 它在剥离后文本里的位置。 */
data class PlacedTag(val tag: VoiceTag, val at: Int)

object VoiceTagParser {

    /**
     * 候选标签的外壳。
     *
     * ⚠️ 四种写法都要认，因为**中文模型自己挑**：
     *    带英文词的模型爱写 `[sigh]`，中文模型更爱写 `（叹气）`，
     *    被 Markdown 喂大的会写 `*叹气*`。只认一种等于对一半模型失效。
     *
     * ⚠️ 方括号放宽到 24 字，因为 `[throat_clear]` 有 14 个字符；
     *    圆括号收紧到 12 字 —— 中文正文里的括号（「（见附件）」）
     *    通常更短，收紧能把误伤压到最低。
     */
    private val CANDIDATE = Regex(
        """\[([^\[\]\n]{1,24})]|（([^（）\n]{1,12})）|\(([^()\n]{1,12})\)|\*([^*\n]{1,12})\*""",
    )

    /**
     * 整段解析。
     *
     * ⚠️ 不认识的括号内容**原样留下**。这一条是硬要求：
     *    「（他犹豫了一下）」是正文里的动作描写，不是声音标签；
     *    把它当标签吃掉，用户就再也看不到自己写的那句话了。
     *    判据只有一个 —— 词表里有没有。
     */
    fun split(raw: String): VoiceParse {
        if (raw.isEmpty()) return VoiceParse("", emptyList(), emptyList())

        val out = StringBuilder(raw.length)
        val tags = mutableListOf<VoiceTag>()
        val rawPlacements = mutableListOf<PlacedTag>()

        var cursor = 0
        for (match in CANDIDATE.findAll(raw)) {
            val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
            val tag = VoiceTag.of(inner) ?: continue

            appendSqueezed(out, raw, cursor, match.range.first)
            // ★ 位置记的是「剥掉标签、压完空白之后」的下标 —— 渲染器往这里插东西。
            rawPlacements += PlacedTag(tag, out.length)
            tags += tag
            cursor = match.range.last + 1
        }
        appendSqueezed(out, raw, cursor, raw.length)

        // 首尾空白统一去掉。只影响两端，所以标签位置只需要整体平移一次。
        val body = out.toString()
        val lead = body.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) body.length else it }
        val tail = body.indexOfLast { !it.isWhitespace() } + 1
        val cleaned = if (tail > lead) body.substring(lead, tail) else ""
        val placements = rawPlacements.map { PlacedTag(it.tag, (it.at - lead).coerceIn(0, cleaned.length)) }

        return VoiceParse(cleaned, tags, placements)
    }

    /** 只要文本（给气泡用）。 */
    fun strip(raw: String): String = split(raw).spoken

    /**
     * 把 `src[from until to]` 追加进 [out]，顺手压掉重复空白。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么不用正则做这件事
     * ═══════════════════════════════════════════════════════════════
     *
     * 因为位置。正则替换之后长度会变，标签的下标就得靠"再扫一遍算差值"
     * 找回来 —— 只要标签之间有两处压缩，差值法就会一路漂。
     *
     * 边追加边压，[out] 的长度在任何时刻都是**最终坐标系**里的长度，
     * 标签下标直接就是 `out.length`，没有第二步。
     *
     * ⚠️ 只压**重复**的空白，不碰单个空格 —— 英文句子里那个空格是有意义的。
     */
    private fun appendSqueezed(out: StringBuilder, src: String, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val ch = src[i]
            if (ch == ' ' || ch == '\t' || ch == '\u3000') {
                if (out.isNotEmpty() && out[out.length - 1].isSpaceLike()) {
                    i++
                    continue
                }
            } else if (ch == '\n') {
                // 连续三个以上换行压成两个 —— 剥掉「[叹气]\n」之后容易多出空行。
                var newlines = 0
                var k = out.length - 1
                while (k >= 0 && out[k] == '\n') {
                    newlines++
                    k--
                }
                if (newlines >= 2) {
                    i++
                    continue
                }
            }
            out.append(ch)
            i++
        }
    }

    private fun Char.isSpaceLike(): Boolean = this == ' ' || this == '\t' || this == '\u3000' || this == '\n'
}

/**
 * 流式输出时把标签挡在气泡外面。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要一个流式的
 * ═══════════════════════════════════════════════════════════════
 *
 * 模型是逐字吐的。`[叹气]` 会先到 `[`、`[叹`、`[叹气`、`[叹气]` 四步。
 * 如果每一块都直接上屏，用户会看到括号一个字一个字地冒出来再消失 ——
 * 那一瞬间的闪烁比没有标签更糟。
 *
 * ⇒ 规则：**看到可能是标签的开头就先扣着**，等它闭合（是标签，丢掉）
 *   或者超过最长长度（不是标签，放出来）。
 *
 * ⚠️ 扣着的代价是延迟。所以 [pendingMax] 定得很小（24，等于最长标签的
 *    长度），最坏情况是 24 个字符的延迟，而不是整句话等完。
 */
class StreamingVoiceTagFilter(private val pendingMax: Int = 24) {

    private val buffer = StringBuilder()
    private val found = mutableListOf<VoiceTag>()
    private val placed = mutableListOf<PlacedTag>()
    private var emitted = 0

    /**
     * 喂一段新到的文本，拿回**可以立刻上屏**的部分（可能为空串）。
     *
     * 不放心的开头会留在内部，等下一次 [feed] 或者 [flush]。
     */
    fun feed(chunk: CharSequence): String {
        buffer.append(chunk)
        val text = buffer.toString()

        // 找一个可能是标签开头、但还没闭合的位置，从这里往后全部扣住。
        val holdFrom = uncertainStart(text)
        val out = drain(text.substring(0, holdFrom))

        buffer.clear()
        buffer.append(text.substring(holdFrom))
        return out
    }

    /**
     * 收尾：把扣着的都放出来。
     *
     * ⚠️ 尾部没闭合的半截壳（`[叹` / `（小声`）**丢掉不显示**。
     *    它一定是模型输出被掐断留下的 —— 把半个括号放到屏幕上，
     *    用户看到的是一个她永远说不完的字。
     */
    fun flush(): String {
        val text = buffer.toString()
        val out = drain(text.substring(0, uncertainStart(text)))
        buffer.clear()
        return out
    }

    /** 到目前为止识别到的标签（含已经流过去的）。 */
    fun tags(): List<VoiceTag> = found.toList()

    fun placements(): List<PlacedTag> = placed.toList()

    /**
     * 从哪个下标开始是"不确定所以先扣着"。
     *
     * 逐个开壳符找它对应的闭壳符：
     *  · 找到了 → 这一段是确定的，接着往后扫；
     *  · 没找到 → 要么是真的标签还没吐完（扣住），
     *             要么已经长得不像标签了（放行，交给 [drain] 当普通文字）。
     */
    private fun uncertainStart(text: String): Int {
        var i = 0
        while (i < text.length) {
            val closer = CLOSERS[text[i]]
            if (closer == null) {
                i++
                continue
            }
            val closed = text.indexOf(closer, i + 1)
            if (closed < 0) {
                return if (text.length - i <= pendingMax) i else text.length
            }
            i = closed + 1
        }
        return text.length
    }

    /**
     * 把安全区里的标签剥掉。
     *
     * ⚠️ 这里**不调** [VoiceTagParser.split]：那个函数会压空白并 trim，
     *    而流式场景下首尾空白属于下一块，被吃掉就会把两句话粘成一个词。
     *    位置也必须在**不压缩**的坐标系里累计，否则 [placed] 全废。
     */
    private fun drain(safe: String): String {
        if (safe.isEmpty()) return ""
        val stripped = StringBuilder(safe.length)
        var cursor = 0
        for (match in TAG_SHELL.findAll(safe)) {
            val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
            val tag = VoiceTag.of(inner) ?: continue
            stripped.append(safe, cursor, match.range.first)
            found += tag
            placed += PlacedTag(tag, emitted + stripped.length)
            cursor = match.range.last + 1
        }
        stripped.append(safe, cursor, safe.length)
        emitted += stripped.length
        return stripped.toString()
    }

    private companion object {
        val CLOSERS = mapOf('[' to ']', '（' to '）', '(' to ')', '*' to '*')
        val TAG_SHELL = Regex(
            """\[([^\[\]\n]{1,24})]|（([^（）\n]{1,12})）|\(([^()\n]{1,12})\)|\*([^*\n]{1,12})\*""",
        )
    }
}
