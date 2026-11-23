package com.pocketagent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketagent.skilllogic.Skill
import com.pocketagent.skilllogic.SkillSource
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaListDivider
import com.pocketagent.ui.design.PaListRow
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **工作技能** —— 清单、导入、删除。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一页解决什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话（2026-11-23）：
 * > "在内置上一些 skill，例如工作上的 skill，还有支持一波自定义 skill 导入，
 * >  用户在工作模式里输入 / 就能看见相关的 skill 列表"
 *
 * 三件事分开落点：
 * - **看到有哪些**：内置 8 条 + 导入的那些，都在这一页；工作台打 `/` 看到的是同一份
 * - **导入**：任意 `.md`，格式是 front matter（`name` / `description` / 可选 `triggers`）
 *   + 正文。正文就是"怎么做"的规则，会原样进模型的提示词
 * - **删**：只删得掉导入的。内置那 8 条是**产品的一部分**（`/` 面板的默认体验），
 *   不给删 —— 想不用它，不点就行
 *
 * ⚠️ 为什么这一页不持有 `AppContainer`：
 *   与 `MentionPanel` / `SkillPanel` 同一条纪律 —— 界面层只收数据与回调，
 *   这样预览与离线测试都不需要起整个应用。读写都通过 `import` / `remove` 两个
 *   suspend 回调，由外壳接容器。
 */
@Composable
fun SkillsScreen(
    skills: List<Skill>,
    import: suspend (text: String, origin: String) -> String,
    remove: suspend (id: String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    // 导入结果就地显示，不弹 toast —— toast 在分身/后台会被吞掉，而"导进去了没有"
    // 是这一页唯一需要用户确认的事。
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                message = "读不到这个文件。可能是它不在允许读取的位置，或者不是文本。"
                busy = false
                return@launch
            }
            // 文件名只用来做"来源"标注，解析全靠文件内容。
            val origin = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull().orEmpty()
            message = runCatching { import(text, origin) }
                .getOrElse { "导入时出了意外：${it.message ?: it::class.simpleName}" }
            busy = false
        }
    }

    val builtin = remember(skills) { skills.filter { it.source == SkillSource.BUILTIN } }
    val imported = remember(skills) { skills.filter { it.source == SkillSource.IMPORTED } }

    PaScreen(title = "工作技能", onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PaSpace.m)
                .padding(bottom = PaSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(PaSpace.s),
        ) {
            Spacer(Modifier.height(PaSpace.s))

            Text(
                "工作模式里，打 / 能看到这份清单；直接说要做的事也行 —— " +
                    "它认出对口的技能就会照着做。技能只管做法，不改变她说话的方式。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )

            Spacer(Modifier.height(PaSpace.xs))

            PaButton(
                text = if (busy) "处理中…" else "从文件导入 .md",
                onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) },
                enabled = !busy,
                fillWidth = true,
            )

            message?.let { line ->
                Text(line, style = PaType.caption, color = PaColor.TextSecondary)
            }

            Spacer(Modifier.height(PaSpace.s))

            Text("内置（${builtin.size}）", style = PaType.label, color = PaColor.TextTertiary)
            if (builtin.isEmpty()) {
                Text("（没有内置技能）", style = PaType.caption, color = PaColor.TextTertiary)
            } else {
                builtin.forEachIndexed { index, skill ->
                    PaListRow(
                        icon = Icons.Default.Psychology,
                        title = skill.name,
                        subtitle = skill.summary,
                    )
                    if (index != builtin.lastIndex) PaListDivider()
                }
            }

            Spacer(Modifier.height(PaSpace.m))

            Text("我导入的（${imported.size}）", style = PaType.label, color = PaColor.TextTertiary)
            if (imported.isEmpty()) {
                Text(
                    "还没有导入。任意 .md 都行，开头写上 name 与 description 就会被认出来。",
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            } else {
                imported.forEachIndexed { index, skill ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            PaListRow(
                                icon = Icons.Default.Psychology,
                                title = skill.name,
                                subtitle = skill.summary,
                            )
                        }
                        PaButton(
                            text = "删除",
                            onClick = {
                                if (busy) return@PaButton
                                scope.launch {
                                    busy = true
                                    val ok = runCatching { remove(skill.id) }.getOrDefault(false)
                                    message = if (ok) {
                                        "已删掉「${skill.name}」。"
                                    } else {
                                        "没删掉 —— 它可能已经不在列表里了。"
                                    }
                                    busy = false
                                }
                            },
                            style = PaButtonStyle.Text,
                            enabled = !busy,
                        )
                    }
                    if (index != imported.lastIndex) PaListDivider()
                }
            }
        }
    }
}
