package com.pocketagent.ui.tasks

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.IconGeometry
import com.pocketagent.agentui.ToolTrace
import com.pocketagent.ui.design.dp
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * 它干了什么 —— 一行一个动作的工具日志。
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ★ 为什么不再用 `PaCardStack`（叠卡）
 * ═══════════════════════════════════════════════════════════════════
 *
 * 叠卡把每一步画成一张"卡"，于是 5 步任务在屏幕上就是 5 张同款卡片
 * 压在一起 —— 它表达的是"这里有 N 张同等级的东西"，而工具调用
 * 根本不是同等级的：它们是一条**时间线**，前一步的产物是后一步的输入，
 * 而且其中某一步可能是失败或被拦（那一步才是用户要看的）。
 *
 * ⇒ 现在照 agent 类工具（Claude Code / Codex CLI）的通用做法：
 *   一条竖线导轨 + 一行一个动作，工具名用等宽字（它是个标识符，
 *   不是中文句子），结果说明用次要色跟在后面；状态用**圆点颜色**区分
 *   成功 / 失败 / 被拦，而不是三张颜色不同的卡片。
 *
 * ## 折叠策略
 *
 * 收起时只露**最后一条** + 摘要（`跑了 5 个动作 · 4 成功 / 1 失败`）。
 * 露最后一条是有理由的：用户最常问的是"它现在/刚才在做什么"。
 * 失败与被拦的行数写在摘要里，所以收起状态下也不会漏掉坏消息。
 *
 * ## 与运行中的关系
 *
 * 运行中 `trace` 还是空的（循环结束才回填），此时用 [runningLine]
 * 显示**当前这一步**，并在前面画一个呼吸的圆点。真正的历史在
 * 结束后一次性出现 —— 不做逐条追加的假动画。
 *
 * @param trace 循环给的原始行（`ToolTrace` 会拆成结构）
 * @param running 是否还在跑
 * @param runningLine 运行中那一步的说明（循环的 `onStep` 直给）
 */
@Composable
internal fun AgentActionLog(
    trace: List<String>,
    running: Boolean,
    modifier: Modifier = Modifier,
    runningLine: String = "",
) {
    val steps = remember(trace) { ToolTrace.parse(trace) }
    if (steps.isEmpty() && !running) return

    // 默认收起；步数少到一屏能看完时直接摊开 —— 让用户多点一次
    // 才能看到"它做了两件事"，那是倒退。
    var expanded by remember(steps.size) { mutableStateOf(steps.size <= ALWAYS_OPEN) }
    // 哪一行被单独点开看全文（长路径、长命令）。
    var openedIndex by remember(trace) { mutableStateOf(-1) }

    val mono = PaType.caption.copy(fontFamily = FontFamily.Monospace)

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 摘要行 ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .clickable(enabled = steps.size > ALWAYS_OPEN) { expanded = !expanded }
                .padding(vertical = PaSpace.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            PulseDot(active = running, tint = if (running) PaColor.Accent else tintOf(steps))
            Text(
                text = if (running) "正在做" else ToolTrace.summary(steps),
                style = PaType.label,
                color = PaColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (steps.size > ALWAYS_OPEN) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起步骤" else "展开步骤",
                    tint = PaColor.TextTertiary,
                    modifier = Modifier.size(IconGeometry.Size.INLINE.dp),
                )
            }
        }

        // ── 行 ────────────────────────────────────────────────────
        val shown: List<ToolTrace.Step> = if (expanded) steps else steps.takeLast(1)
        if (shown.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = PaSpace.xxs)) {
                shown.forEach { step ->
                    val index = steps.indexOf(step)
                    ToolStepRow(
                        step = step,
                        mono = mono,
                        opened = index == openedIndex,
                        onClick = { openedIndex = if (openedIndex == index) -1 else index },
                    )
                }
            }
        }

        // 运行中：当前这一步。它还没有"结局"，所以不画状态圆点。
        if (running && runningLine.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PaSpace.xxs),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(Modifier.width(RAIL_DOT))
                Spacer(Modifier.width(PaSpace.xs))
                Text(
                    text = "⎿ $runningLine",
                    style = mono,
                    color = PaColor.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 一行动作。
 *
 * 导轨用 1dp 竖线而不是每行一张卡的左边框：竖线在视觉上是**连续**的，
 * 卡片边框是断续的 —— 连续才像"同一件事的若干步"。
 */
@Composable
private fun ToolStepRow(
    step: ToolTrace.Step,
    mono: TextStyle,
    opened: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        // 导轨 + 状态点
        Box(
            modifier = Modifier
                .width(RAIL_DOT)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(PaColor.TextTertiary.copy(alpha = RAIL_ALPHA)),
            )
            Box(
                modifier = Modifier
                    .padding(top = RAIL_DOT_PAD)
                    .size(RAIL_DOT)
                    .clip(CircleShape)
                    .background(tintOf(step.outcome)),
            )
        }
        Spacer(Modifier.width(PaSpace.xs))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = PaSpace.xxs),
        ) {
            if (step.tool.isNotBlank()) {
                Text(
                    text = step.tool,
                    style = mono,
                    color = PaColor.AccentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = mono,
                    color = PaColor.TextTertiary,
                    maxLines = if (opened) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 呼吸的圆点 —— 只有"正在跑"才呼吸。停下来的圆点是静止的（事实已经定了）。 */
@Composable
private fun PulseDot(active: Boolean, tint: Color) {
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "toolDot")
        val value by transition.animateFloat(
            initialValue = PULSE_MIN,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "toolDotAlpha",
        )
        value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(SUMMARY_DOT)
            .alpha(alpha)
            .clip(CircleShape)
            .background(tint),
    )
}

/** 摘要那个点的颜色：有坏消息就先报坏的（用户要先知道出事了）。 */
@Composable
private fun tintOf(steps: List<ToolTrace.Step>): Color = when {
    steps.any { it.outcome == ToolTrace.Outcome.BLOCKED } -> PaColor.Warning
    steps.any { it.outcome == ToolTrace.Outcome.FAILED } -> PaColor.Danger
    steps.isEmpty() -> PaColor.TextTertiary
    else -> PaColor.Success
}

@Composable
private fun tintOf(outcome: ToolTrace.Outcome): Color = when (outcome) {
    ToolTrace.Outcome.DONE -> PaColor.Success
    ToolTrace.Outcome.FAILED -> PaColor.Danger
    ToolTrace.Outcome.BLOCKED -> PaColor.Warning
    ToolTrace.Outcome.UNKNOWN -> PaColor.TextTertiary
}

/** 步数不超过这个数就默认摊开 —— 两步还让人点一下，是倒退。 */
private const val ALWAYS_OPEN = 2

/** 摘要行那个圆点的直径。 */
private val SUMMARY_DOT = 7.dp

/** 导轨上那个点的直径。 */
private val RAIL_DOT = 9.dp

/** 导轨点距行顶的偏移（让它对齐第一行文字的中线）。 */
private val RAIL_DOT_PAD = 3.dp

/** 导轨的透明度 —— 它只是条引导线，不该比内容更显眼。 */
private const val RAIL_ALPHA = 0.25f

/** 呼吸的最低透明度。 */
private const val PULSE_MIN = 0.35f

/** 一次呼吸的时长。 */
private const val PULSE_MS = 900
