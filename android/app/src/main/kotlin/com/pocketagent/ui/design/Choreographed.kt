package com.pocketagent.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.Choreography
import com.pocketagent.agentui.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 场景编排的**渲染层**。故意写得很薄 —— 这里只做三件事：
 * 驱动一个时钟、把 [Playback] 算好的姿态塞进 `graphicsLayer`、必要时裁一段长度。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不在这里写任何"什么时候动、动多久、从哪来"
 * ═══════════════════════════════════════════════════════════════
 *
 * 这套东西的全部规则在 `:agentui` 的 [Choreography]（编排表）与 [Playback]（求值器）里，
 * 它们是没有 Compose、没有 Android 依赖的纯 Kotlin，因此可以被 63 条单元测试
 * 按毫秒逐个采样验证。一旦把"淡入 120ms 还是 180ms"写进 Compose，
 * 它就再也跑不进测试了 —— 只能靠肉眼在真机上看，而肉眼看不出 3.8dp 的瞬移。
 *
 * 所以：**这里出现任何数字（除了 0f / 1f 这种恒等值）都是 bug**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么只有一个时钟
 * ═══════════════════════════════════════════════════════════════
 *
 * 每个元素各自 `animateFloatAsState` 是最容易写的写法，但它带来三个问题：
 *  1. N 个 Animatable = N 个协程 = N 份 `FrameNanos` 订阅，落帧时间互不相同 ⇒ 相位漂移。
 *     编排的全部意义就是"三拍之间差 140ms"，漂移 16ms 就毁掉节奏。
 *  2. 打断没法做。用户点「停下」时，你要么逐个 cancel（顺序不可控），
 *     要么让它们各自跑完（卡片已经该收场了还在淡入）。
 *  3. 状态跃迁（DRAFT → RUNNING → DONE）之间没有"主体"概念，
 *     卡片会在旧动画没结束时叠上新动画，看起来像重影。
 *
 * 一个 `Animatable<Float>` 从 0 跑到 1，所有元素从**同一个** `elapsedMs` 查表取姿态：
 * 相位严格同步、打断只有一处、换场景只换表。
 */

/**
 * 一次场景播放的句柄。
 *
 * ⚠️ 这个类**不是**状态机，它是"表 + 时钟"的组合。场景结束它不会自己消失，
 * 也不会自己重播 —— 重播由 `rememberChoreo(beat)` 的 beat 变化触发。
 */
@Stable
class ChoreoClock internal constructor(
    val beat: Choreography.Beat,
    val scene: Choreography.Scene,
    private val clock: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
) {

    /** 0..1 的归一化进度（给需要自己算点东西的地方用）。 */
    val fraction: Float get() = clock.value

    /** 当前毫秒数。场景已结束时会停在 [Choreography.Scene.totalMs]。 */
    val elapsedMs: Int
        get() = if (scene.totalMs <= 0) 0 else (clock.value * scene.totalMs).roundToInt()

    /** 这个场景管不管这个元素。不管的话，调用方不该给它挂 [choreo]（省一个图层）。 */
    fun holds(target: Choreography.Target): Boolean =
        scene.steps.any { it.target == target }

    /**
     * 取这个元素此刻的姿态。
     *
     * 读的是 `Animatable.value` —— 在 `graphicsLayer` 的 deferred lambda 里调用，
     * 只会让**图层**失效重绘，不会触发重组、不会重新 measure。
     */
    fun poseOf(target: Choreography.Target): Playback.Pose =
        Playback.poseOf(scene, target, elapsedMs)

    /** 停在此刻（用户点了「停下」但还没决定怎么办：世界该静下来，不是倒回去）。 */
    fun freeze() {
        scope.launch { clock.stop() }
    }

    /**
     * 倒回起点。用于「用户明确说不要」—— 动作必须**撤回**而不是停住，
     * 因为停住的卡片会看起来像"还没决定"，而用户已经决定了。
     */
    fun retreat() {
        val remain = (scene.totalMs * clock.value).roundToInt().coerceAtLeast(1)
        // 撤回比进场快：留一个已发生的动作占着屏幕，比没发生过更让人不安。
        val fast = (remain * Choreography.TAKEOVER_SPEED).roundToInt().coerceIn(1, remain)
        scope.launch { clock.animateTo(0f, tween(fast, easing = LinearEasing)) }
    }

    /** 直接跳到终点（降级、或"其实已经完成了"的对位）。 */
    fun settle() {
        scope.launch { clock.snapTo(1f) }
    }
}

/**
 * 记住并播放一个场景。
 *
 * @param play false ⇒ 只建表、不播（元素直接停在终点，用于"我不需要进场"的复用场景）。
 *
 * 动画开关与省电模式从 [LocalMotionPreference] 实时读 —— 用户改系统设置后
 * 下一次进场就会跟着变（已经在播的这次不打断，打断比不对齐更唐突）。
 */
@Composable
fun rememberChoreo(
    beat: Choreography.Beat,
    play: Boolean = true,
): ChoreoClock {
    val motion = LocalMotionPreference.current
    val scene = remember(beat, motion.animationsOn, motion.richMotion) {
        Choreography.scene(beat, motion.animationsOn, motion.richMotion)
    }
    val clock = remember(scene) {
        Animatable(if (scene.static) 1f else 0f)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(scene, play) {
        if (scene.static || !play) {
            clock.snapTo(1f)
            return@LaunchedEffect
        }
        clock.snapTo(0f)
        // 线性地推进**时间本身**。曲线在 Playback 里按元素各自施加 ——
        // 时钟若也用缓动，等于把曲线叠了两遍（前段会快得离谱）。
        clock.animateTo(1f, tween(durationMillis = scene.totalMs, easing = LinearEasing))
    }

    // scope 不参与 key：它整个组合周期都不变
    return remember(scene, clock) { ChoreoClock(beat, scene, clock, scope) }
}

/**
 * 让这个元素跟着场景动。
 *
 * 只写 `alpha / translation / scale` 四项，**绝不修改尺寸与位置**：
 * 布局在动画期间必须稳定，否则一次进场会触发 N 次 measure，
 * 长列表里就是肉眼可见的掉帧。
 */
fun Modifier.choreo(clock: ChoreoClock, target: Choreography.Target): Modifier {
    if (!clock.holds(target)) return this
    return this.graphicsLayer {
        val pose = clock.poseOf(target)
        alpha = pose.alpha
        // dp → px 需要 Density；GraphicsLayerScope 本身实现了 Density，直接用。
        translationX = pose.dxDp.dp.toPx()
        translationY = pose.dyDp.dp.toPx()
        scaleX = pose.scale
        scaleY = pose.scale
    }
}

/**
 * 长度型（EXTENT）元素的姿态：从左边按比例揭开。
 *
 * 用于卡片顶部那条"这只手正在动"的进度线、竖脊。
 * 它**不表示进度**（没有人知道该到 60% 还是 80%），只表示"在动" ——
 * 所以曲线是匀速的，见 `Choreography.curveFor(EXTENT) = LINEAR`。
 */
fun Modifier.choreoExtent(clock: ChoreoClock, target: Choreography.Target): Modifier {
    if (!clock.holds(target)) return this
    return this.drawWithContent {
        val extent = clock.poseOf(target).extent.coerceIn(0f, 1f)
        if (extent <= 0f) return@drawWithContent
        clipRect(right = size.width * extent) {
            this@drawWithContent.drawContent()
        }
    }
}
