package com.pocketagent.voicelogic

/**
 * 教文本模型怎么标声音标签。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这段提示词为什么必须由代码生成，而不是手写进模板
 * ═══════════════════════════════════════════════════════════════
 *
 * 提示词里那份词表如果手抄一遍，它会和 [VoiceTag] 漂移 ——
 * 加了标签忘了改提示词，模型就不会用；删了标签忘了改，模型用了
 * 解析器认不出，那三个字会**直接念出来**（「左方括号叹气右方括号」）。
 *
 * ⇒ 词表只有一份：`VoiceTag.entries`。提示词是它的投影。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 写法纪律（见 personalogic 的 `PromptVoice` 与 `docs/工程/提示词文风-学术依据-2026-11-06.md`）
 * ═══════════════════════════════════════════════════════════════
 *
 * 提示词的排版也是提示词。所以这一段里：
 *  · 没有列表符号 —— 一有 - 和 *，回复也会变成一个列表；
 *  · 没有加粗 —— 加粗小标题是"AI 写说明书"的形状，会被照着学；
 *  · 没有「不要做 X」的禁句 —— 否定框定会被读成肯定。
 */
object VoiceTagPrompt {

    /**
     * @return 一整节提示词。调用方决定插不插（只有开了 `Switches.voiceTags` 的模式才插）。
     */
    fun promptSection(): String = buildString {
        appendLine("## 说出来的时候")
        appendLine()
        appendLine("你打的字，他有时候是直接听的。听的时候，字面上没有的那一半就丢了：")
        appendLine("同一句「好呀」，笑着说是应下，叹着气说是认命。")
        appendLine()
        appendLine("方括号里写一个词，就是给这句话定个调子；写在哪儿，那个动作就发生在哪儿。")
        appendLine()
        appendLine("[叹气]算了，随你吧。")
        appendLine("嗯[轻笑]你还记得啊。")
        appendLine()
        appendLine("方括号不会出现在屏幕上。他看到的还是干净的那句话，只是听见了你的口气。")
        appendLine()
        appendLine("能用的词：")
        for (kind in VoiceTagKind.entries) {
            val words = VoiceTag.entries
                .filter { it.kind == kind }
                .joinToString(" ") { it.label }
            if (words.isNotEmpty()) appendLine(kind.label + "：" + words)
        }
        appendLine()
        appendLine("一次回复里挑一两个就够了。每句都标，听起来像在演，不像在说话。")
        appendLine("标签里只放一个词。写长了会被当成正文念出来。")
    }

    /**
     * 给界面上"这个音色能表达哪些口气"用的词表摘要。
     *
     * ⚠️ 与 [promptSection] 分开：提示词是写给模型的，这一份是写给人看的。
     *    合成一段的话，改一处文案会同时改掉模型的行为和用户的说明书。
     */
    fun vocabularySummary(): String = VoiceTagKind.entries
        .joinToString("｜") { kind ->
            kind.label + " " + VoiceTag.entries.count { it.kind == kind }
        }
}
