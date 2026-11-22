package com.pocketagent.voicelogic

/**
 * **声音标签** —— 让她说话时有口气。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 用户 11-20 的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * > "文本模型在向语音的 api 模型发送请求时要加入特定的提示词，
 * >  夹杂着情绪化，语气化，发声方式，呼吸等等等等的声音标签提示词"
 *
 * 这句话要解决的是一个很具体的故障：**同样的文字，念出来像客服。**
 *
 * 她说「好呀。」—— 文字上看不出问题，语音里如果平铺直叙地念，
 * 那三个字就没有任何信息量可言。人说话时「好呀」可以是开心、
 * 可以是敷衍、可以是叹气，区别全在口气里，而口气不在字面上。
 *
 * ⇒ 所以标签不是装饰，它是**把字面上没有的那一半信息补回去**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 标签只能从词表里选
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里的每一个词都对应一个**具体可执行的发声动作**（[VoiceTag.instruction]）。
 * 让模型自由发挥"温柔而略带忧伤"这种措辞，得到的是没有任何厂商能执行的东西 ——
 * 语义上很美，工程上是零。
 *
 * ⇒ 词表是封闭的。模型只能挑，不能造。造出来的会被 [VoiceTagParser] 当普通文字。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 标签一律**不进聊天气泡**
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户在屏幕上看到的是句子，听到的是句子加口气。`[叹气]` 三个字
 * 出现在气泡里会立刻把她变成一个在念标注的东西 —— 那正是本项目
 * 花最多力气消灭的「不像人」。所以解析出来的标签给语音用，
 * 文本那一份必须已经剥干净（见 [VoiceTagParser.split]）。
 */
enum class VoiceTagKind(val label: String) {
    /** 她此刻的情绪。决定整体的调门，所以排在词表最前 —— 同一条消息里有多个标签时，情绪那个定基调。 */
    EMOTION("情绪"),

    /** 对谁、用什么态度说。相同情绪可以是温柔也可以是冷淡。 */
    TONE("语气"),

    /** 声音怎么出来：气声、耳语、喊、颤。 */
    VOCAL("发声"),

    /** 呼吸。最容易被忽略、却最能把「念稿」变回「说话」的一类。 */
    BREATH("呼吸"),

    /** 非语言的声音：笑、哭、咳、清嗓。 */
    PARALINGUISTIC("副语言"),

    /** 停顿。把一句话切成两口气。 */
    PAUSE("停顿"),

    /** 快慢。 */
    RATE("语速"),
}

/**
 * 一个可执行的声音标签。
 *
 * @property token 标准写法（小写 ASCII）。也是 [VoiceTagParser] 认出英文标签的唯一依据
 * @property aliases 中文别名。中文模型自己就会写「叹气」而不是 `[sigh]`
 * @property label 给人看的中文名
 * @property instruction 交给支持指令参数的厂商（如 `gpt-4o-mini-tts` 的 instructions）时用的一句话。
 *           写法是**动作**不是形容词 —— 「把气先从鼻子里放掉再开口」能执行，
 *           「悲伤一点」不能
 * @property ssml 能被 SSML 直接表达时的片段（只有停顿与语速有）。其余标签 SSML 表达不了，
 *           写一个近似的 `<prosody>` 只会让朗读变怪
 * @property inline 支持文本内嵌标签的厂商（如 CosyVoice2）用的写法
 */
enum class VoiceTag(
    val token: String,
    val kind: VoiceTagKind,
    val label: String,
    val aliases: List<String>,
    val instruction: String,
    val ssml: String? = null,
    val inline: String? = null,
) {
    // ── 情绪 ──────────────────────────────────────────────────
    HAPPY(
        "happy", VoiceTagKind.EMOTION, "开心",
        listOf("高兴", "愉快", "雀跃"),
        "声音往上抬，尾音轻快，不压着",
    ),
    SAD(
        "sad", VoiceTagKind.EMOTION, "难过",
        listOf("伤心", "低落", "失落"),
        "声音沉下去，句尾往下掉，中间留一点犹豫",
    ),
    ANGRY(
        "angry", VoiceTagKind.EMOTION, "生气",
        listOf("恼火", "不悦", "恼"),
        "字咬得实一点，气从胸口顶上来说",
    ),
    SURPRISED(
        "surprised", VoiceTagKind.EMOTION, "惊讶",
        listOf("吃惊", "意外"),
        "第一声稍微抬高，然后立刻收回来",
    ),
    SHY(
        "shy", VoiceTagKind.EMOTION, "害羞",
        listOf("不好意思", "羞涩"),
        "声音收小，语速慢下来，有点含糊",
    ),
    CALM(
        "calm", VoiceTagKind.EMOTION, "平静",
        listOf("平淡", "冷静"),
        "不加起伏，一句话一个调子说下去",
    ),
    TIRED(
        "tired", VoiceTagKind.EMOTION, "疲惫",
        listOf("累", "倦"),
        "气息短，句子收得快，像是说着就松掉了",
    ),

    // ── 语气 ──────────────────────────────────────────────────
    TENDER(
        "tender", VoiceTagKind.TONE, "温柔",
        listOf("柔和", "轻柔"),
        "声音放软，音量偏小，字与字之间不着急",
    ),
    COLD(
        "cold", VoiceTagKind.TONE, "冷淡",
        listOf("冷漠", "疏离"),
        "音量和语速都平，句尾收干，不留温度",
    ),
    URGENT(
        "urgent", VoiceTagKind.TONE, "急促",
        listOf("着急", "连忙"),
        "语速往前赶，句间几乎不停",
    ),
    LAZY(
        "lazy", VoiceTagKind.TONE, "慵懒",
        listOf("懒散", "没精神"),
        "把字拖着说，句尾松掉，像刚醒",
    ),
    COQUETTISH(
        "coquettish", VoiceTagKind.TONE, "撒娇",
        listOf("娇", "耍赖"),
        "尾音往上带一点，字黏在一起",
    ),
    SERIOUS(
        "serious", VoiceTagKind.TONE, "严肃",
        listOf("认真", "正色"),
        "每个字都咬清楚，节奏稳，不拖",
    ),
    TEASING(
        "teasing", VoiceTagKind.TONE, "打趣",
        listOf("调侃", "逗"),
        "尾音翘一下，像是自己也知道这句不算数",
    ),

    // ── 发声方式 ──────────────────────────────────────────────
    WHISPER(
        "whisper", VoiceTagKind.VOCAL, "耳语",
        listOf("小声", "低语", "悄悄说"),
        "几乎不送气，声音贴着唇边出来",
    ),
    BREATHY(
        "breathy", VoiceTagKind.VOCAL, "气声",
        listOf("嘘声", "气流"),
        "大部分是气，声音只带一点点",
    ),
    SHOUT(
        "shout", VoiceTagKind.VOCAL, "喊",
        listOf("大声", "嚷"),
        "声音从胸口推出来，音量拉满",
    ),
    HUM(
        "hum", VoiceTagKind.VOCAL, "哼唱",
        listOf("哼", "哼歌"),
        "用鼻音哼出来，不咬字",
    ),
    TREMBLING(
        "trembling", VoiceTagKind.VOCAL, "颤声",
        listOf("发抖", "颤"),
        "声音在抖，字头都不稳",
    ),
    MUFFLED(
        "muffled", VoiceTagKind.VOCAL, "含糊",
        listOf("含糊", "嘟囔"),
        "字不清，像嘴里还含着东西",
    ),

    // ── 呼吸 ──────────────────────────────────────────────────
    INHALE(
        "inhale", VoiceTagKind.BREATH, "吸气",
        listOf("吸一口气", "倒吸气"),
        "先说一个吸气，再开口",
    ),
    EXHALE(
        "exhale", VoiceTagKind.BREATH, "呼气",
        listOf("吐气", "呼一口气"),
        "话说完之后把气放掉，留一点声音",
    ),
    SIGH(
        "sigh", VoiceTagKind.BREATH, "叹气",
        listOf("叹气", "叹一口气", "叹了口气"),
        "先长长地呼一口气，再说话，句子会因此沉下来",
    ),
    PANT(
        "pant", VoiceTagKind.BREATH, "喘息",
        listOf("喘", "上气不接下气"),
        "气息断，一句话要分几次才说完",
    ),
    HOLD_BREATH(
        "hold_breath", VoiceTagKind.BREATH, "屏息",
        listOf("屏住呼吸", "憋气"),
        "停住气息，声音绷着不敢放",
    ),

    // ── 副语言 ────────────────────────────────────────────────
    LAUGH(
        "laugh", VoiceTagKind.PARALINGUISTIC, "笑",
        listOf("笑", "大笑", "笑出声"),
        "先笑出来，再接着说",
    ),
    GIGGLE(
        "giggle", VoiceTagKind.PARALINGUISTIC, "轻笑",
        listOf("偷笑", "噗嗤"),
        "很轻地笑一下，几乎只是鼻音",
    ),
    SOB(
        "sob", VoiceTagKind.PARALINGUISTIC, "哭腔",
        listOf("哭", "哽咽"),
        "声音带着哭的抖，字被气息冲散",
    ),
    COUGH(
        "cough", VoiceTagKind.PARALINGUISTIC, "咳",
        listOf("咳嗽", "干咳"),
        "咳一下，再说话",
    ),
    THROAT_CLEAR(
        "throat_clear", VoiceTagKind.PARALINGUISTIC, "清嗓",
        listOf("清嗓子", "咳一声"),
        "先清一下嗓子再开口",
    ),
    TSK(
        "tsk", VoiceTagKind.PARALINGUISTIC, "咂嘴",
        listOf("啧", "啧一声"),
        "一个短促的啧，然后说话",
    ),

    // ── 停顿与语速 ────────────────────────────────────────────
    PAUSE(
        "pause", VoiceTagKind.PAUSE, "停顿",
        listOf("停顿", "停一下", "顿", "短停", "短暂停顿"),
        "在这里停半拍再说下去",
        ssml = "<break time=\"350ms\"/>",
        inline = "[uv_break]",
    ),
    LONG_PAUSE(
        "long_pause", VoiceTagKind.PAUSE, "长停",
        listOf("长停", "久停", "停久一点", "长长的停顿"),
        "在这里停一拍多",
        ssml = "<break time=\"900ms\"/>",
        inline = "[uv_break][uv_break]",
    ),
    FASTER(
        "faster", VoiceTagKind.RATE, "快些",
        listOf("说快", "加快"),
        "从这里开始语速往前赶",
        ssml = null,
    ),
    SLOWER(
        "slower", VoiceTagKind.RATE, "慢些",
        listOf("说慢", "放慢"),
        "从这里开始语速降下来",
        ssml = null,
    ),
    ;

    companion object {
        /** 标准写法 + 中文名 + 中文别名 → 标签。查表用，构建一次。 */
        val byAlias: Map<String, VoiceTag> = buildMap {
            VoiceTag.entries.forEach { tag ->
                put(tag.token, tag)
                // ★ 中文名本身也要能反查。模型写 `[轻笑]` 的频率远高于写
                //   `[giggle]` 或别名 `[偷笑]` —— 只认 token 和别名，等于
                //   把最自然的那种写法漏掉。
                put(tag.label, tag)
                tag.aliases.forEach { put(it, tag) }
            }
        }

        fun of(tokenOrAlias: String): VoiceTag? = byAlias[tokenOrAlias.trim().lowercase()]

        /**
         * 这条消息的整体基调：**第一个情绪标签**说了算。
         *
         * ⚠️ 不取"最后一个"、不取"出现最多的"。一条回复里情绪是会变的
         *    （先说笑再沉下来），而语音的整体调门必须一开始就定 ——
         *    让最后一句的悲伤决定整条的语气，前面那句开心就念错了。
         *    句内的变化由标签自身的位置表达，不需要基调再算一次。
         */
        fun dominantTone(tags: List<VoiceTag>): VoiceTag? =
            tags.firstOrNull { it.kind == VoiceTagKind.EMOTION }
    }
}
