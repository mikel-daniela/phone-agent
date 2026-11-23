package com.pocketagent.assistant

import com.pocketagent.mcp.McpTool
import com.pocketagent.mcp.ToolOutcome
import com.pocketagent.skilllogic.Skill
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `use_skill`：按名字取一条工作技能的做法。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要这个工具（而不是只把技能塞进提示词）
 * ═══════════════════════════════════════════════════════════════
 *
 * 系统提示词里只放**清单**（一行一条：名字 + 一句说明），因为 8 条技能
 * 的正文加起来近万字 —— 每轮都带，光费钱还是小事，真正的问题是它会把
 * 别的东西挤掉，而且技能的正文里全是"先确定受众、再排页序"这类**流程**，
 * 每轮都出现会让模型把闲聊也当成任务。
 *
 * 所以分成两段：
 * - **清单**常驻（`SkillPrompt.listSection`），让模型知道有哪些做法可用
 * - **正文**按需取（本工具），模型自己判断这一轮要不要用、用哪一条
 *
 * ⚠️ 这也是"用自然语言就能让角色调用技能"的落点：用户不需要打 `/`，
 *    他说「帮我做个汇报用的幻灯片」，模型在清单里看到「做演示文稿」，
 *    调一次本工具拿到规则，然后照着做。用户打 `/` 只是**跳过这一步**。
 *
 * ⚠️ 无副作用（只读），所以不需要权限档位、不占屏、不进安全闸。
 */
class SkillTool(
    private val skills: () -> List<Skill>,
) : McpTool {

    override val name: String = "use_skill"

    override val description: String =
        "取一条工作技能的具体做法（规则、顺序、验收标准）。可用名字从系统提示词的技能清单里挑，" +
            "和用户在输入框打 / 看到的是同一份。只读，不产生任何改动。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "技能名或 id，照抄清单里的即可，例如「做演示文稿」或 deck")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val raw = arguments["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val all = skills()
        if (all.isEmpty()) return ToolOutcome.Failed("现在还没有任何工作技能可用")
        if (raw.isEmpty()) {
            return ToolOutcome.Failed(
                "要给出技能名。可选：" + all.joinToString("、") { it.name },
            )
        }
        val hit = all.firstOrNull { it.id.equals(raw, ignoreCase = true) }
            ?: all.firstOrNull { it.name == raw }
            // 容错：模型可能把名字加长（「做一份演示文稿」）或只给半截
            ?: all.firstOrNull { it.name.contains(raw, ignoreCase = true) }
            ?: all.firstOrNull { raw.contains(it.name, ignoreCase = true) }
            ?: return ToolOutcome.Failed(
                "没有叫「" + raw + "」的技能。可选：" + all.joinToString("、") { it.name },
            )
        return ToolOutcome.Success(
            buildString {
                appendLine("## 这一轮按「" + hit.name + "」做")
                appendLine()
                appendLine("技能只管做法，不改变你说话的方式和称呼。")
                appendLine()
                append(hit.body.trim())
            },
        )
    }
}
