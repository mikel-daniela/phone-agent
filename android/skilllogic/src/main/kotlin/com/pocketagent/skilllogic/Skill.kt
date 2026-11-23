package com.pocketagent.skilllogic

/**
 * 一条技能从哪来。
 *
 * ★ 这不是"分类"，它决定**能不能删**：内置的删不掉（删了下次启动又回来），
 *   导入的可以删。UI 上两者长得一样，但长按菜单不一样。
 */
enum class SkillSource {
    /** 随应用一起发布的工作技能。 */
    BUILTIN,

    /** 用户自己导入的 `SKILL.md`。 */
    IMPORTED,
}

/**
 * 一条工作技能。
 *
 * ## 为什么是"文档"而不是"提示词模板"
 *
 * 模板是"填空"，技能是"怎么做这件事"。区别在于：模板只能替换变量，
 * 而技能要能教模型**先想什么、按什么顺序写、什么算合格**。
 * 所以 [body] 是一段可以任意长的说明文，而不是带 `{占位符}` 的句子。
 *
 * ## 为什么正文要限长
 *
 * 技能正文进的是**系统提示词**，而系统提示词里还有人格、记忆、世界书、
 * 身体状态。一个人把 `SKILL.md` 写成两万字，就会把人格挤掉 ——
 * 那不是"更强的工作能力"，那是"角色突然不像角色了"。
 * 上限见 [MAX_BODY_CHARS]，超出部分**截断并留一句说明**（见 `SkillDoc`）。
 *
 * @property id 稳定标识。小写字母、数字与连字符，形如 `ppt-deck`。
 *   它是 `/` 面板的检索键、也是去重的键 —— **改了 id 就是另一条技能**。
 * @property name 用户看到的名字（可以是中文）。
 * @property summary 一句话：什么时候该用它。**进提示词的清单**，所以必须短。
 * @property body 正文指导。进提示词，是这个技能真正值钱的部分。
 * @property triggers 自然语言命中的关键词。判定见 [SkillMatch]。
 * @property origin 导入来源（文件名）。内置技能为空串。
 * @property source 见 [SkillSource]。
 */
data class Skill(
    val id: String,
    val name: String,
    val summary: String,
    val body: String,
    val triggers: List<String> = emptyList(),
    val origin: String = "",
    val source: SkillSource = SkillSource.BUILTIN,
) {
    init {
        require(ID_PATTERN.matches(id)) {
            "技能 id 不合法：$id（只允许小写字母、数字、连字符，2–48 字符，且以字母开头）"
        }
        require(name.isNotBlank()) { "技能名不能为空（id=$id）" }
        require(summary.isNotBlank()) { "技能摘要不能为空（id=$id）" }
        require(body.isNotBlank()) { "技能正文不能为空（id=$id）" }
    }

    /** 清单里的一行。`- 做演示文稿：对外汇报、季度总结这类要"讲给人听"的场合。 */
    fun listLine(): String = "- $name：$summary"

    companion object {
        /** 与 `ToolSpec.id` 同一套纪律：小写、可 grep、能当文件名。 */
        val ID_PATTERN = Regex("^[a-z][a-z0-9-]{1,47}$")

        /**
         * 正文上限（字符）。
         *
         * ★ 3000 是个折中：够写清"先定一句话主张 → 每页一个意思 → 标题写结论"
         *   这类真正有用的规则，又不会把别的段落挤出去。
         *   超过就截断 —— **不拒绝导入**，因为拒绝会让人以为自己写错了格式。
         */
        const val MAX_BODY_CHARS = 3000

        /** 摘要上限。清单最多列 12 条，一条太长就变成一屏字。 */
        const val MAX_SUMMARY_CHARS = 60

        /** 名字上限。`/` 面板一行放得下的长度。 */
        const val MAX_NAME_CHARS = 20
    }
}

/**
 * 把任意字符串变成合法的技能 id。
 *
 * ## 为什么需要它
 *
 * 用户导入的 `SKILL.md` 里 `name` 很可能是中文（「做演示文稿」），
 * 而 id 必须是 ASCII —— 它要出现在 `/做演示文稿` 这样的文本里、
 * 要能当 DataStore 的键、要能在日志里 grep。
 *
 * 规则：ASCII 字母数字保留并转小写，其余字符折叠成一个 `-`，
 * 去掉首尾的 `-`。结果为空（整份中文）时退回 [fallback]，
 * 再不行就 `skill`。**绝不抛异常** —— 导入流程必须能给出"为什么不行"，
 * 而不是崩在构造里。
 */
fun slugifySkillId(raw: String, fallback: String = "skill"): String {
    val sb = StringBuilder()
    var lastDash = false
    for (ch in raw.trim()) {
        val keep = ch in 'a'..'z' || ch in '0'..'9'
        if (keep) {
            sb.append(ch)
            lastDash = false
        } else if (ch in 'A'..'Z') {
            sb.append(ch.lowercaseChar())
            lastDash = false
        } else if (!lastDash && sb.isNotEmpty()) {
            sb.append('-')
            lastDash = true
        }
    }
    var out = sb.toString().trim('-')
    if (out.isEmpty() || out.first() !in 'a'..'z') {
        out = slugifyAscii(fallback)
        // ★ 名字与文件名都是中文时（「我的技能.md」），两次哈希都得不到 ASCII。
        //   这时用**原文的稳定哈希**收尾，而不是统统叫 `skill` ——
        //   否则导入第二条中文技能就会覆盖第一条（id 相同 = 同一条）。
        //   `String.hashCode` 在同一 JVM 版本上对同一字符串恒定，
        //   而这里只要求"同一份文件反复导入得到同一个 id"，不要求跨语言一致。
        if (out.isEmpty() || out.first() !in 'a'..'z') {
            out = "skill-" + Integer.toHexString(raw.hashCode()).trimStart('-')
        }
    }
    return out.take(48).trim('-').ifEmpty { "skill" }
}

private fun slugifyAscii(raw: String): String {
    val sb = StringBuilder()
    var lastDash = false
    for (ch in raw.trim().lowercase()) {
        if (ch in 'a'..'z' || ch in '0'..'9') {
            sb.append(ch)
            lastDash = false
        } else if (!lastDash && sb.isNotEmpty()) {
            sb.append('-')
            lastDash = true
        }
    }
    return sb.toString().trim('-')
}
