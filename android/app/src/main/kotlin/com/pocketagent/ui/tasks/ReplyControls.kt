package com.pocketagent.ui.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * 回复形态的三段式开关 + 语音播报的小喇叭。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-23：用户的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * > "聊天页面的那个用来控制显示文字和语音的滑条，改成一个三段式开关，
 * >  放在工作模式、在场模式的同等高度那里，也就是最上方，
 * >  再在那附近加上一个小喇叭，用户可以自行打开或关闭角色的语音播报，
 * >  这样快些，就不需要去设置里打开或者关闭了"
 *
 * ⚠️ 为什么从滑条改成三段：滑条有三个落点却只有一个可拖的把手，
 *    用户得先拖再确认自己停在哪一档 —— 中间那一档（都有）恰恰是最常用的，
 *    却是滑条上最不好瞄准的位置。三段式是**直接点**，没有瞄准成本。
 *
 * ⚠️ 为什么喇叭放在同一行：这两件事是同一个问题的两半 ——
 *    "这一轮怎么呈现"（文字 / 都有 / 语音）和"要不要念出来"。
 *    分到两处，用户会先切到"语音"再发现没开朗读，多一步。
 *    现在切到"语音"而喇叭是关的，两个东西挨着，一眼就能看出问题在哪。
 *
 * ⚠️ 位置：它排在 `PaScreen` 内容的第一行，紧贴顶栏 —— 语义上属于**页面外壳**
 *    而不属于消息列表。放进 `PaScreen.actions` 会挤掉标题：
 *    工作台的标题是"某某的工作台"，五颗图标之后已经没有位置再放三段开关。
 */

/** 三段的文字。顺序与 [ReplyViewMode.ordered] 一致，靠 `indexOf` 对齐。 */
private val REPLY_MODE_LABELS = listOf("文字", "都有", "语音")

private fun labelOf(mode: ReplyViewMode): String =
    REPLY_MODE_LABELS.getOrElse(ReplyViewMode.indexOf(mode)) { "都有" }

@Composable
internal fun ReplyControls(
    requested: ReplyViewMode,
    canSpeak: Boolean,
    ttsEnabled: Boolean,
    onPick: (ReplyViewMode) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaSpace.m, vertical = PaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(pill)
                .background(PaColor.GlassTintStrong)
                .padding(2.dp),
        ) {
            for (mode in ReplyViewMode.ordered) {
                val selected = mode == requested
                // ⚠️ 没有语音能力时"语音"那一档不可点：点了会得到一条空消息，
                //    用户会以为消息丢了（与 `effectiveReplyMode` 的回退同一个理由）。
                val enabled = canSpeak || mode != ReplyViewMode.VOICE_ONLY
                Text(
                    text = labelOf(mode),
                    style = PaType.caption,
                    color = when {
                        !enabled -> PaColor.TextDisabled
                        selected -> PaColor.AccentText
                        else -> PaColor.TextTertiary
                    },
                    modifier = Modifier
                        .clip(pill)
                        .background(if (selected) PaColor.AccentSoft else Color.Transparent)
                        .clickable(enabled = enabled) { onPick(mode) }
                        .padding(horizontal = PaSpace.s, vertical = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        val speakerTint = when {
            !canSpeak -> PaColor.TextDisabled
            ttsEnabled -> PaColor.Accent
            else -> PaColor.TextTertiary
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = canSpeak) { onToggleTts(!ttsEnabled) }
                .padding(PaSpace.xs)
                .semantics {
                    contentDescription = if (ttsEnabled) "关掉语音播报" else "打开语音播报"
                },
        ) {
            SpeakerGlyph(on = ttsEnabled, tint = speakerTint)
        }
    }
}

/** 喇叭的线宽与边长（`material-icons-core` 里没有 VolumeUp/VolumeOff 这种图标）。 */
private val GLYPH_SIZE = 18.dp

/**
 * 小喇叭：一个梯形喇叭身，开着时右侧两道声波弧，关掉时改画一道叉。
 *
 * ⚠️ 不用 `Icons.Default.VolumeUp` —— 那个只在 material-icons-extended 里，
 *    为一个图标多拉几 MB 的依赖不值；自己画也更贴合这里的 1dp 线宽。
 */
@Composable
private fun SpeakerGlyph(on: Boolean, tint: Color, size: Dp = GLYPH_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.10f
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.34f)
            lineTo(w * 0.28f, h * 0.34f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.50f, h * 0.86f)
            lineTo(w * 0.28f, h * 0.66f)
            lineTo(w * 0.08f, h * 0.66f)
            close()
        }
        drawPath(body, tint)
        if (on) {
            drawArc(
                color = tint,
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(w * 0.46f, h * 0.24f),
                size = Size(w * 0.30f, h * 0.52f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = tint,
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(w * 0.46f, h * 0.04f),
                size = Size(w * 0.52f, h * 0.92f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        } else {
            drawLine(
                color = tint,
                start = Offset(w * 0.62f, h * 0.26f),
                end = Offset(w * 0.94f, h * 0.74f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(w * 0.94f, h * 0.26f),
                end = Offset(w * 0.62f, h * 0.74f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
