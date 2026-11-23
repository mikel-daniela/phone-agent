package com.pocketagent.skilllogic

/**
 * `SKILL.md` 的解析器。
 *
 * ## 格式
 *
 * ```markdown
 * ---
 * name: 做演示文稿
 * description: 对外汇报、季度总结这类要"讲给人听"的场合
 * triggers: ppt, 幻灯片, 演示, 汇报
 * ---
 * 正文：先定一句话主张，再排页……
 * ```
 *
 * ★ front matter 的键名与 DSH / Claude 的 skill 约定**完全一致**（`name` + `description`），
 *   目的是让用户能把自己在别处写好的 skill 直接拖进来用 —— 而不是学我们一套私有格式。
 *   额外支持的键只有 `triggers`（自然语言命中词）与 `title`（`name` 的别名）。
 *
 * ## 为什么解析要"宽容但不猜"
 *
 * 用户导入的文件来自各种地方：Windows 记事本存的（带 BOM、CRLF）、
 * 从网页复制的（首尾空白）、手打的（把 `---` 打成 `--`）。
 * 这些都能修，就修 —— 所以 strip BOM、统一换行、忽略空行。
 * 但**缺 `description` 不修**：没有"什么时候用"的技能，模型不知道该不该用，
 * 让它进库只会变成一条永远不命中的死数据。这时返回 [Result.Bad] 并说清缺什么。
 */
object SkillDoc {

    /** 解析结果。**只用 sealed 而不是抛异常** —— 导入界面要展示原因，不是崩。 */
    sealed interface Result {
        data class Ok(val skill: Skill) : Result

        data class Bad(val reason: String) : Result
    }

    /**
     * 解析一份 `SKILL.md`。
     *
     * @param text 文件原文。
     * @param origin 来源文件名（`我的技能.md`）。用于生成 id 的兜底与 UI 展示。
     */
    fun parse(text: String, origin: String = ""): Result {
        val normalized = text
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val stripped = normalized.trim()
        if (stripped.isEmpty()) return Result.Bad("这个文件是空的。")

        val lines = stripped.split('\n')
        if (lines.first().trim() != "---") {
            return Result.Bad(
                "第一行必须是三个短横线 `---`，下面是 name / description，再一行 `---`，" +
                    "然后才是正文。现在第一行是「${lines.first().take(24)}」。",
            )
        }
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return Result.Bad("找不到结束的 `---`：front matter 没有闭合，整份文件都被当成头部了。")

        val head = lines.subList(1, close)
        val body = lines.subList(close + 1, lines.size).joinToString("\n").trim()

        val fields = mutableMapOf<String, String>()
        for (raw in head) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim().trim('"', '\'').trim()
            if (key.isNotEmpty() && value.isNotEmpty()) fields[key] = value
        }

        val name = (fields["name"] ?: fields["title"] ?: "").trim()
        if (name.isEmpty()) {
            return Result.Bad("front matter 里缺 `name`：得给这个技能起个名字（中文可以）。")
        }
        val summary = (fields["description"] ?: fields["summary"] ?: "").trim()
        if (summary.isEmpty()) {
            return Result.Bad(
                "front matter 里缺 `description`：用一句话写清「什么时候该用它」，" +
                    "这句话会被模型看到，用来决定要不要用。",
            )
        }
        if (body.isEmpty()) {
            return Result.Bad("front matter 之后没有正文：技能值钱的是正文里「该怎么做」的那些规则。")
        }

        val id = slugifySkillId(name, fallback = origin.substringBeforeLast('.'))
        val triggers = parseList(fields["triggers"] ?: fields["tags"] ?: fields["keywords"] ?: "")
        val clampedSummary = summary.take(Skill.MAX_SUMMARY_CHARS)
        val clampedBody = clampBody(body)

        return Result.Ok(
            Skill(
                id = id,
                name = name.take(Skill.MAX_NAME_CHARS),
                summary = clampedSummary,
                body = clampedBody,
                triggers = triggers,
                origin = origin,
                source = SkillSource.IMPORTED,
            ),
        )
    }

    /**
     * 正文截断。
     *
     * ★ 截断处**留一句说明**而不是硬切：模型看到"（正文太长，后面被截掉了）"
     *   就会知道规则不完整，而不是把半句话当成完整规则去执行。
     */
    fun clampBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.length <= Skill.MAX_BODY_CHARS) return trimmed
        val cut = trimmed.take(Skill.MAX_BODY_CHARS - 24)
        val safe = cut.substringBeforeLast('\n').ifEmpty { cut }
        return safe + "\n\n（这份技能正文太长，后面被截掉了）"
    }

    /** `a, b, c` / `[a, b]` / `a、b` 三种写法都认。 */
    fun parseList(raw: String): List<String> =
        raw.trim()
            .removePrefix("[").removeSuffix("]")
            .split(',', '，', '、', ';', '；')
            .map { it.trim().trim('"', '\'').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(24)
}
