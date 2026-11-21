package com.pocketagent.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.CallMorph
import com.pocketagent.agentui.Choreography
import com.pocketagent.agentui.VoiceCall
import com.pocketagent.ui.design.ChoreoClock
import com.pocketagent.ui.design.choreo
import com.pocketagent.ui.design.rememberChoreo
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * **通话动效** —— 三种相位给三种**形状不同**的动画，切换时**形变**而不是替换。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要形变
 * ═══════════════════════════════════════════════════════════════
 *
 * 打电话时用户的注意力不在屏幕上，他偶尔瞥一眼只为了回答"现在轮到谁了"。
 * 如果三态之间是硬切（环瞬间消失、粒子瞬间出现），那一眼看到的是一次**闪** ——
 * 在通话语境里，"闪一下"读起来是"信号断了"。
 *
 * 所以交接按 `:agentui` 的编排走（那里有 38 条测试按毫秒采样，包括
 * 「通话状态切换必须形变而不是硬切」）：
 *
 *  · 听 → 想：环**收拢成核**（240ms，到 0.15 而不是 0）→ 核**炸开成粒子**（180ms）
 *  · 想 → 说：粒子**塌进中心**（180ms，到 0.30）→ 竖条**从中心站起**（270ms）
 *  · 说 → 听：竖条**掐断**（60ms 高度归零）→ 40ms 后一次涟漪 → 环淡入（200ms）
 *
 * ⚠️ 数字一个都不许写在这里。渲染层只负责把编排算好的姿态塞进图层，
 *    否则「收拢到 0.15 还是 0.12」就永远无法被测试验证了。
 *
 * ⚠️ 三种形状各自是**无限循环**（呼吸/旋转/起伏）——那部分是"活着"的感觉，
 *    与相位交接无关，所以留在本文件；交接只叠一层缩放/透明度。
 */
@Composable
fun CallMotion(
    phase: VoiceCall.Phase,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFB9D4FF),
    /** 说话时的音量 0..1（驱动波形起伏）。没有音量数据时传固定值。 */
    volume: Float = 0.5f,
) {
    val motion = VoiceCall.motionFor(phase)

    // 屏幕上"该显示什么"（到达态）与"正在离场什么"（交接期的旧形状）。
    var shown by remember { mutableStateOf(motion) }
    var leaving by remember { mutableStateOf<VoiceCall.Motion?>(null) }
    var beat by remember { mutableStateOf<Choreography.Beat?>(null) }

    LaunchedEffect(motion) {
        if (motion == shown) return@LaunchedEffect
        val crossing = CallMorph.beatBetween(shown, motion)
        if (crossing == null) {
            // 没有编排可走（例如刚进页面 IDLE → LISTENING）：直接换，不留旧形状。
            shown = motion
            leaving = null
            beat = null
        } else {
            leaving = shown
            shown = motion
            beat = crossing
        }
    }

    val morphing = leaving != null && beat != null
    val clock = rememberChoreo(
        beat = beat ?: Choreography.Beat.CALL_LISTEN_TO_THINK,
        play = morphing,
    )

    // 编排播完就把旧形状撤掉 —— 否则它会永远停在收拢后的那个小核上。
    LaunchedEffect(beat) {
        val current = beat ?: return@LaunchedEffect
        val scene = clock.scene
        if (scene.totalMs > 0) delay(scene.totalMs.toLong() + 16L)
        leaving = null
        beat = null
    }

    val arrivingTarget = CallMorph.targetOf(shown)
    val leavingTarget = leaving?.let { CallMorph.targetOf(it) }

    Box(modifier = modifier.fillMaxSize()) {
        leavingTarget?.let { target ->
            if (target != arrivingTarget) {
                Shape(
                    target = target,
                    modifier = if (morphing) Modifier.choreo(clock, target) else Modifier,
                    accent = accent,
                    volume = volume,
                    clock = clock.takeIf { morphing },
                )
            }
        }
        arrivingTarget?.let { target ->
            Shape(
                target = target,
                modifier = if (morphing) Modifier.choreo(clock, target) else Modifier,
                accent = accent,
                volume = volume,
                clock = clock.takeIf { morphing },
            )
        }
    }
}

@Composable
private fun Shape(
    target: Choreography.Target,
    modifier: Modifier,
    accent: Color,
    volume: Float,
    clock: ChoreoClock?,
) {
    when (target) {
        Choreography.Target.CALL_RING -> BreathingRing(modifier, accent)
        Choreography.Target.CALL_DOTS -> SpinningDots(modifier, accent)
        Choreography.Target.CALL_BARS -> Waveform(modifier, accent, volume, clock)
        Choreography.Target.CALL_RIPPLE -> Ripple(modifier, accent, clock)
        else -> Unit
    }
}

/**
 * **听** —— 从中心向外的呼吸环。
 *
 * ★ 为什么用"外扩"而不是"内缩"：
 *   外扩读起来是"在收音/在接收"，内缩像是"在收拢/在结束"。
 *
 * ⚠️ 三圈错开相位（各差 1/3 周期）：一圈单独呼吸看起来像心跳，
 *   三圈错开才是"持续在听"的感觉。
 */
@Composable
private fun BreathingRing(modifier: Modifier, accent: Color) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // ⚠️ 2.4 秒：比心跳慢、比呼吸快一点 —— 电话等待时的自然节奏。
            animation = tween(2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breathingT",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension * 0.42f

        for (i in 0 until 3) {
            // 每圈错开 1/3 周期
            val phaseT = (t + i / 3f) % 1f
            // 半径从 0.15 长到 1.0
            val r = maxR * (0.15f + 0.85f * phaseT)
            // 越往外越淡：这是"扩散出去消失"的视觉依据
            val alpha = (1f - phaseT) * 0.45f
            drawCircle(
                color = accent.copy(alpha = alpha),
                radius = r,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/**
 * **想** —— 缓慢旋转的粒子。
 *
 * ★ 旋转速度刻意很慢（3.6 秒一圈）：它要表达"在思考"，
 *   转太快会显得焦躁（像是"卡住了在重试"）。
 *
 * ⚠️ 用粒子而不是"三个点跳动"：
 *   跳动的点暗示"进度条式"的机械感，而旋转粒子更像"脑子在转"。
 */
@Composable
private fun SpinningDots(modifier: Modifier, accent: Color) {
    val transition = rememberInfiniteTransition(label = "spinning")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinAngle",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val orbit = size.minDimension * 0.28f
        val dotR = 5.dp.toPx()

        // 五个粒子，越大越亮（尾巴在后面）
        for (i in 0 until 5) {
            val a = Math.toRadians((angle + i * 24f).toDouble())
            val p = Offset(
                center.x + cos(a).toFloat() * orbit,
                center.y + sin(a).toFloat() * orbit,
            )
            // 第一个最亮，往后递减 —— 形成"拖尾"
            val alpha = 0.9f - i * 0.16f
            drawCircle(
                color = accent.copy(alpha = alpha.coerceAtLeast(0.12f)),
                radius = dotR * (1f - i * 0.12f),
                center = p,
            )
        }
    }
}

/**
 * **说** —— 随音量起伏的波形。
 *
 * ★ 竖条波形（而不是圆环）：声音的直觉就是"起伏"，而竖条最像
 *   音频电平表 —— 用户一眼就知道"它在出声"。
 *
 * ⚠️ 用 `volume` 驱动幅度：没有真实音量数据时它是个固定值，
 *   波形仍然会动（靠时间），只是不会随说话强弱变化。
 *   有真实数据时接进来即可 —— 这个参数就是为那个留的。
 *
 * ⚠️ 交接时的高度由编排的 `EXTENT` 给（「说 → 听」是 60ms 内高度归零：
 *   那是**掐断**，不是变小声）。所以这里读时钟，而不是自己写个 0.2 秒的缩。
 */
@Composable
private fun Waveform(
    modifier: Modifier,
    accent: Color,
    volume: Float,
    clock: ChoreoClock?,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveT",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // 在绘制阶段读时钟：只会让这一层重绘，不会触发重组。
        val extent = clock?.poseOf(Choreography.Target.CALL_BARS)?.extent?.coerceIn(0f, 1f) ?: 1f
        if (extent <= 0.001f) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f
        val barCount = 9
        val spacing = size.minDimension * 0.055f
        val maxH = size.minDimension * 0.22f * extent
        val barW = 3.5.dp.toPx()

        for (i in 0 until barCount) {
            // 中间的条高，两边低（钟形包络）
            val norm = (i - (barCount - 1) / 2f) / ((barCount - 1) / 2f)
            val envelope = 1f - norm * norm * 0.85f
            // 每根条自己的相位（错开，形成流动感）
            val wave = 0.55f + 0.45f * sin(t + i * 0.7f)
            val h = maxH * envelope * wave * volume.coerceIn(0.05f, 1f)

            val x = cx + (i - (barCount - 1) / 2f) * spacing
            drawLine(
                color = accent.copy(alpha = 0.85f),
                start = Offset(x, cy - h / 2f),
                end = Offset(x, cy + h / 2f),
                strokeWidth = barW,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/**
 * **打断的余韵** —— 一圈往外散开的涟漪。
 *
 * ★ 它只在「说 → 听」的交接里出现（见 `CALL_SPEAK_TO_LISTEN`）：
 *   声音是被**掐断**的，那一下需要一个视觉回声，否则屏幕看起来像"卡住了"。
 *
 * ⚠️ 半径与透明度都来自编排的 `EXTENT`，这里不写任何时长。
 */
@Composable
private fun Ripple(modifier: Modifier, accent: Color, clock: ChoreoClock?) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val extent = clock?.poseOf(Choreography.Target.CALL_RIPPLE)?.extent?.coerceIn(0f, 1f) ?: 0f
        if (extent <= 0.001f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = accent.copy(alpha = (1f - extent) * 0.55f),
            radius = size.minDimension * 0.42f * extent,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
