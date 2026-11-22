package com.pocketagent.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.MentionIndex
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.glassEdge

/**
 * `@` 面板 —— 打字打出一个 `@` 时，在输入框上方浮出可提及的东西。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它长得像一个"临时清单"而不是一个弹层
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户此刻**在打字**，面板只是辅助。所以：
 *
 * · 不遮住输入框（贴在输入框上方，跟着 `imePadding` 走）；
 * · 不抢焦点（点一下就走，键盘不收起）；
 * · 最多显示四组 × 每组几条，不做滚动嵌套。
 *
 * ⚠️ 分组顺序**就是界面的顺序**（见 [MentionIndex.Group]），
 *    不按命中数量动态重排 —— 每次敲字都换位置，手指会点错。
 */
@Composable
internal fun MentionPanel(
    grouped: Map<MentionIndex.Group, List<MentionIndex.Item>>,
    onPick: (MentionIndex.Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (grouped.isEmpty()) return
    val shape = RoundedCornerShape(PaRadius.m)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaSpace.screenH)
            .padding(bottom = PaSpace.xxs)
            .clip(shape)
            .background(PaColor.GlassTintStrong)
            .glassEdge(shape)
            .padding(vertical = PaSpace.xxs),
    ) {
        for ((group, items) in grouped) {
            Text(
                text = labelOf(group),
                style = PaType.caption,
                color = PaColor.TextTertiary,
                modifier = Modifier.padding(
                    start = PaSpace.xs,
                    end = PaSpace.xs,
                    top = PaSpace.xxs,
                    bottom = 2.dp,
                ),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 168.dp)) {
                items(items, key = { it.key }) { item ->
                    MentionRow(item = item, onClick = { onPick(item) })
                }
            }
        }
    }
}

@Composable
private fun MentionRow(item: MentionIndex.Item, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PaSpace.xs, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
    ) {
        Text(
            text = item.label,
            style = PaType.body,
            color = PaColor.TextPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // key 只在**与 label 不同**时显示 —— 相同就是重复两遍，白占地方
        if (item.key != item.label) {
            Text(
                text = item.key.take(18),
                style = PaType.caption,
                color = PaColor.TextTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 分组标题。 */
internal fun labelOf(group: MentionIndex.Group): String = when (group) {
    MentionIndex.Group.CAPABILITY -> "能做的事"
    MentionIndex.Group.MEMORY -> "记得的事"
    MentionIndex.Group.FILE -> "文件"
    MentionIndex.Group.APP -> "应用"
}
