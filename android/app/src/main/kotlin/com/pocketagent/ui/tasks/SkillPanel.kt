package com.pocketagent.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.skilllogic.Skill
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.glassEdge

/**
 * 输入框里打 `/` 时浮出的技能清单。
 *
 * ## 为什么和 `MentionPanel` 是两个 Composable 而不是一个带参数的
 *
 * 两者长得像，但**选完的结果不一样**：`@` 挑的是素材，插进文本的是显示名；
 * `/` 挑的是"这一轮按哪套规则做"，插进去之后还要能被
 * `SlashTrigger.resolve` 认出来。合成一个会让那个函数多一个 `kind` 参数，
 * 而它的每一处调用都要跟着判断 —— 拆开更省事。
 *
 * ## 高度
 *
 * 与 `@` 面板同一条教训（真机撞出来的）：**总高封顶**，否则组数一多就把
 * 消息列表压成零高。这里只有一个列表，封 [PANEL_MAX_HEIGHT]。
 */
private val PANEL_MAX_HEIGHT = 240.dp

@Composable
internal fun SkillPanel(
    skills: List<Skill>,
    onPick: (Skill) -> Unit,
    modifier: Modifier = Modifier,
    onImport: (() -> Unit)? = null,
) {
    if (skills.isEmpty()) return
    val shape = RoundedCornerShape(PaRadius.m)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaSpace.s, vertical = PaSpace.xs)
            .background(PaColor.GlassTintStrong, shape)
            .glassEdge(shape)
            .padding(vertical = PaSpace.xs),
    ) {
        Text(
            text = "技能 · 挑一条这轮照着做",
            style = PaType.caption,
            color = PaColor.TextTertiary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = PaSpace.s, vertical = PaSpace.xxs),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PANEL_MAX_HEIGHT - 28.dp),
        ) {
            items(skills, key = { it.id }) { skill ->
                SkillRow(skill = skill, onClick = { onPick(skill) })
            }
        }
        // ★ 导入入口放在**用技能的地方**，而不是只放设置里。
        //   真机反馈：用户在设置里翻不到「工作技能」，但一定会打 `/`。
        if (onImport != null) {
            Text(
                text = "＋ 导入自己的 .md",
                style = PaType.caption,
                color = PaColor.AccentText,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onImport)
                    .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
            )
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaSpace.s),
    ) {
        Text(
            text = "/",
            style = PaType.body,
            color = PaColor.AccentText,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = skill.name,
                style = PaType.body,
                color = PaColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skill.summary,
                style = PaType.caption,
                color = PaColor.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
