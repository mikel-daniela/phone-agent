package com.pocketagent.agentui

import kotlin.math.abs

/**
 * **编排的求值器** —— 把 [Choreography] 的"段落表"变成"某一毫秒时每个元素长什么样"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要有这一层（而不是直接在 Compose 里写动画）
 * ═══════════════════════════════════════════════════════════════
 *
 * 一份手感只该有**一个**真相。如果没有这一层：
 *
 *   · Compose 里是一份 `animateFloatAsState` 的时长
 *   · 设计文档里是一份手写的时序图
 *   · 给同事看的 HTML 演示里又是第三份 CSS
 *
 * 三份东西必然互相漂移 —— 而且漂移是**静默**的：没人会去比对。
 * 等到有人发现"演示里挺顺，真机有点拖"时，已经分不清是哪一份错了。
 *
 * ⇒ 把"某一毫秒该是什么姿态"写成**纯函数**，让三方都从它取值：
 *
 *   ```text
 *   编排表(Choreography) → 求值(Playback) → ┬→ Compose 的 graphicsLayer
 *                                          ├→ HTML 演示（同一个 JSON）
 *                                          └→ 单测（断言曲线、段连续性、边界）
 *   ```
 *
 * 这一层只做**数学**：插值、曲线求值、过冲叠加。它不认得任何业务概念，
 * 也不知道自己会被画在玻璃上还是画在 Canvas 里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不用 Compose 的 `Animatable` 逐个元素驱动
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个场景有 2–4 段，每段一个 `Animatable` 就是 2–4 个协程、4 次 frame callback、
 * 4 次 recomposition 触发源。而且它们**各自独立**：没有任何东西保证
 * "第 2 段正好在第 1 段结束时开始" —— 那是一句注释，不是一个约束。
 *
 * 这里反过来：**一个**时钟（0 → totalMs），其余全部由它算出来。
 *   · 只有一个动画在跑 ⇒ 只有一次 recomposition/帧
 *   · 段与段的关系由 [Choreography] 的 atMs 决定 ⇒ 可测
 *   · 想在 60ms 处停下来看，就是 `frameOf(scene, 60)` —— 不需要"暂停动画"
 */
object Playback {

    /**
     * 某一瞬间，一个元素**看起来**是什么样。
     *
     * 全部字段都直接对应 `Modifier.graphicsLayer {}` 的参数 ——
     * 因为那是唯一"只重绘不重排"的通道。任何需要改 `offset`/`size` 的表达
     * 都会触发布局，而布局是真机上唯一会掉帧的东西。
     */
    data class Pose(
        /** 不透明度。0 = 不在场。 */
        val alpha: Float = 1f,
        /** 横向位移（dp）。负 = 偏左。 */
        val dxDp: Float = 0f,
        /** 纵向位移（dp）。负 = 偏上（从下方浮起时起点为正）。 */
        val dyDp: Float = 0f,
        /** 缩放。1 = 原大。 */
        val scale: Float = 1f,
        /** "长度"维度 0..1（进度线走了多少、波形多高、涟漪半径多大）。 */
        val extent: Float = 1f,
    ) {
        /** 是否就是"静止态"（视觉上什么都没发生）。 */
        val isRest: Boolean
            get() = alpha == 1f && dxDp == 0f && dyDp == 0f && scale == 1f && extent == 1f

        companion object {
            /** 静止态：元素在它该在的地方，原大、不透明、满长。 */
            val REST: Pose = Pose()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  曲线
    // ═══════════════════════════════════════════════════════════

    /**
     * 项目里的五条曲线 → 三次贝塞尔控制点。
     *
     * ⚠️ 这里的 `(0.16, 1, 0.3, 1)` **不是 M3 的任何 token**
     *    （见 [MaterialMotion] 里那段"已核对"的翻车记录）。
     *    它是视觉调出来的 ease-out，用在淡入上很合适 —— 但它不该被说成"M3 强调曲线"。
     */
    fun curveOf(curve: Choreography.Curve): MaterialMotion.Curve = when (curve) {
        Choreography.Curve.EMPHASIZED -> MaterialMotion.Curve(0.16f, 1f, 0.3f, 1f)
        Choreography.Curve.STANDARD -> MaterialMotion.STANDARD
        Choreography.Curve.EXIT -> MaterialMotion.STANDARD_ACCELERATE
        // 落定用 M3 的 emphasizedDecelerate：慢起、快收 —— 像纸落到桌上。
        Choreography.Curve.SETTLE -> MaterialMotion.EMPHASIZED_DECELERATE
        Choreography.Curve.LINEAR -> MaterialMotion.Curve(0f, 0f, 1f, 1f)
    }

    /**
     * 求三次贝塞尔缓动在 `x` 处的值（即 CSS `cubic-bezier(...)` 的 y）。
     *
     * 做法是标准的两步：先用二分反解出参数 t（使 bezierX(t) = x），
     * 再把 t 代入 bezierY。**必须反解** —— 直接把 x 当 t 用是最常见的错误，
     * 它会让标准曲线在中段偏快约 8%，肉眼可见"冲了一下"。
     */
    fun solve(curve: MaterialMotion.Curve, x: Float): Float {
        val target = x.coerceIn(0f, 1f)
        if (target == 0f) return 0f
        if (target == 1f) return 1f
        // 24 次二分 ⇒ 区间宽度 6e-8，远小于一帧内任何可感知的差
        var lo = 0f
        var hi = 1f
        var mid = target
        repeat(24) {
            if (bezier(curve.x1, curve.x2, mid) < target) lo = mid else hi = mid
            mid = (lo + hi) * 0.5f
        }
        return bezier(curve.y1, curve.y2, mid)
    }

    /** 三次贝塞尔在参数 t 处的一维值（P0=0, P3=1 的三次多项式）。 */
    private fun bezier(c1: Float, c2: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t
    }

    // ═══════════════════════════════════════════════════════════
    //  求值
    // ═══════════════════════════════════════════════════════════

    /**
     * 一段的起点姿态（由 `from*` 决定）。
     *
     * ★ 零时长的段**没有起点**：它表达的是"直接对位"。所以这里返回 [endOf] ——
     *   否则会出现"起点 alpha=0、终点 alpha=1、时长 0"这种自相矛盾的段：
     *   [poseOf] 会返回终点，[startOf] 却宣称起点是全透明，
     *   两者比较（比如"相邻两段的终点就是下一段的起点"）就会凭空多出一个 1.0 的跳变。
     */
    fun startOf(step: Choreography.Step): Pose {
        if (step.durationMs <= 0) return endOf(step)
        return Pose(
            alpha = step.fromAlpha,
            dxDp = step.slideDp,
            dyDp = step.riseDp,
            scale = step.fromScale,
            extent = if (step.axis == Choreography.Axis.EXTENT) step.fromExtent else 1f,
        )
    }

    /** 一段的终点姿态（由 `to*` 决定）。 */
    fun endOf(step: Choreography.Step): Pose = Pose(
        alpha = step.toAlpha,
        dxDp = step.toSlideDp,
        dyDp = step.toRiseDp,
        scale = step.toScale,
        extent = if (step.axis == Choreography.Axis.EXTENT) step.toExtent else 1f,
    )

    /**
     * 某一段在 `elapsedMs`（相对**场景**起点）时的姿态。
     *
     * 三种情况：
     *  · 还没到 `atMs` → 起点姿态（多数时候 `alpha = 0`，即"还不在场"）
     *  · 已过 `endMs`   → 终点姿态
     *  · 中间           → 按曲线插值，并叠加 [Choreography.Step.settle] 的过冲
     */
    fun poseOf(step: Choreography.Step, elapsedMs: Int): Pose {
        val start = startOf(step)
        val end = endOf(step)

        // ★ 零时长的段**直接**是终点：它表达的是"对位"，不是"过渡"。
        //   必须排在 atMs 判断之前 —— 否则 atMs 那一刻会先返回起点、
        //   下一帧才跳到终点，那就是一次闪（而它不是闪，它根本不该动）。
        if (step.durationMs <= 0) return end
        if (elapsedMs <= step.atMs) return start
        if (elapsedMs >= step.endMs) return end

        val local = (elapsedMs - step.atMs).toFloat() / step.durationMs.toFloat()
        val eased = solve(curveOf(Choreography.curveFor(step.mass, step.axis)), local)

        // ★ 过冲只属于**位置与缩放**。
        //   透明度冲到 1.01 再回来是看不见的（会被裁成 1，等于"提前一点到"）；
        //   extent 同理。把它们一起过冲会让人误以为"淡入先快后慢"，
        //   而那只是把 RMS 的过冲模型错用在了不承受力的属性上。
        val placed = withSettle(eased, step.settle)

        fun mix(from: Float, to: Float, t: Float, clamp01: Boolean = false): Float {
            val v = from + (to - from) * t
            return if (clamp01) v.coerceIn(0f, 1f) else v
        }

        return Pose(
            alpha = mix(start.alpha, end.alpha, eased, clamp01 = true),
            dxDp = mix(start.dxDp, end.dxDp, placed),
            dyDp = mix(start.dyDp, end.dyDp, placed),
            scale = mix(start.scale, end.scale, placed),
            extent = mix(start.extent, end.extent, eased, clamp01 = true),
        )
    }

    /**
     * 过冲：**先冲到 `1 + settle`，再用最后 25% 的时间回落**。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么不是"在一个正弦上叠加"
     * ═══════════════════════════════════════════════════════════
     *
     * 第一版写的是 `progress = eased + settle * sin(π * eased)` —— 数学上很漂亮
     * （两端都为 0，峰值在中间），但它**永远不会越过终点**：
     * 在 eased = 0.9 时，sin = 0.31，加起来 0.9 + 0.0093 = 0.909。
     * 而"过冲"的定义是**超过 1**。所以那条曲线只会让元素走得快一点，
     * 一点也没弹 —— 测试「过冲是相对量」正是被这个抓出来的（峰值 0.0）。
     *
     * ⇒ 真正的过冲在时间轴上是"到了，还往前一点，再回来"：
     *    · 前 75% 的时间走完 103% 的路（[ARRIVAL_AT]）
     *    · 后 25% 的时间从 103% 回落 100%
     *   这就是弹簧在临界阻尼附近的样子，而且它**保证**落点精确 = 1。
     */
    private fun withSettle(eased: Float, settle: Float): Float {
        if (settle <= 0f) return eased
        val up = (eased / ARRIVAL_AT).coerceIn(0f, 1f)
        val down = ((eased - ARRIVAL_AT) / (1f - ARRIVAL_AT)).coerceIn(0f, 1f)
        return (1f + settle) * up - settle * down
    }

    /** 走完（含过冲到顶）的时刻占整段的比例。 */
    private const val ARRIVAL_AT: Float = 0.75f

    /**
     * **真机热路径用这个**：单个元素在 `elapsedMs` 时的姿态，零分配。
     *
     * 一个元素在同一场景里可以有多段（`CARD_FAIL` 的撞墙 + 归位就是两段）。
     * 选段的规则与 [frameOf] 完全一致：取"第一个还没结束的段"，
     * 它之前的时间归它（起点），之后的时间也归它（终点）——
     * 因为跨段处有"上一段终点 == 下一段起点"的不变量，取哪个都不会跳。
     */
    fun poseOf(
        scene: Choreography.Scene,
        target: Choreography.Target,
        elapsedMs: Int,
    ): Pose {
        var running: Choreography.Step? = null
        var last: Choreography.Step? = null
        var found = false

        for (s in scene.steps) {
            if (s.target != target) continue
            found = true
            if (last == null || s.atMs > last.atMs) last = s
            if (s.endMs > elapsedMs && (running == null || s.atMs < running.atMs)) running = s
        }
        if (!found) return Pose.REST

        val seg: Choreography.Step = running ?: last ?: return Pose.REST
        return if (elapsedMs <= seg.atMs) startOf(seg) else poseOf(seg, elapsedMs)
    }

    /**
     * 整个场景在 `elapsedMs` 时每个元素的姿态。
     *
     * ⚠️ 这个函数**会分配一个 Map + 每个元素一个 Pose**。它适合给演示、
     *    调试和时间轴可视化用；真机热路径请对单个元素调 [poseOf]（零 Map 分配）。
     */
    fun frameOf(scene: Choreography.Scene, elapsedMs: Int): Map<Choreography.Target, Pose> {
        val out = LinkedHashMap<Choreography.Target, Pose>()
        scene.steps.groupBy { it.target }.forEach { (target, steps) ->
            val ordered = steps.sortedBy { it.atMs }
            val running = ordered.firstOrNull { elapsedMs < it.endMs }
            out[target] = when {
                running == null -> poseOf(ordered.last(), ordered.last().endMs)
                elapsedMs <= running.atMs -> startOf(running)
                else -> poseOf(running, elapsedMs)
            }
        }
        return out
    }

    /**
     * 以固定间隔采样整条时间轴 —— 给演示与"把曲线画出来"用。
     *
     * @return `(相对毫秒, 该时刻的姿态表)`，含首尾两端。
     */
    fun sample(
        scene: Choreography.Scene,
        stepMs: Int = 16,
    ): List<Pair<Int, Map<Choreography.Target, Pose>>> {
        require(stepMs > 0) { "采样间隔必须为正" }
        val out = ArrayList<Pair<Int, Map<Choreography.Target, Pose>>>()
        var t = 0
        while (t < scene.totalMs) {
            out.add(t to frameOf(scene, t))
            t += stepMs
        }
        out.add(scene.totalMs to frameOf(scene, scene.totalMs))
        return out
    }

    // ═══════════════════════════════════════════════════════════
    //  演示数据（HTML 演示与真机用**同一份**数字）
    // ═══════════════════════════════════════════════════════════

    /**
     * 把一个场景采样成 JSON。
     *
     * ★ 这不是"为了演示另写一套" —— 它是**同一份编排**的另一种序列化。
     *   演示里看到的每一帧都是 `frameOf` 的输出，和真机上
     *   `graphicsLayer` 收到的数字一模一样。演示好看而真机难看的情况，
     *   只可能来自"绘制方式的差别"，不可能来自"时序的差别"。
     */
    fun toJson(scene: Choreography.Scene, stepMs: Int = 16): String {
        val frames = sample(scene, stepMs)
        val targets = frames.firstOrNull()?.second?.keys?.toList() ?: emptyList()
        val sb = StringBuilder()
        sb.append("{\"beat\":\"").append(scene.beat.name).append("\",")
        sb.append("\"totalMs\":").append(scene.totalMs).append(',')
        sb.append("\"stepMs\":").append(stepMs).append(',')
        sb.append("\"static\":").append(scene.static).append(',')
        sb.append("\"targets\":[")
        targets.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append('"').append(t.name).append('"')
        }
        sb.append("],\"frames\":[")
        frames.forEachIndexed { fi, (t, poses) ->
            if (fi > 0) sb.append(',')
            sb.append('[').append(t)
            targets.forEach { target ->
                val p = poses[target] ?: Pose.REST
                sb.append(",[").append(f(p.alpha)).append(',').append(f(p.dxDp)).append(',')
                    .append(f(p.dyDp)).append(',').append(f(p.scale)).append(',')
                    .append(f(p.extent)).append(']')
            }
            sb.append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 四位小数的定点输出 —— 够精确，又不会让 JSON 变成一屏浮点尾巴。 */
    private fun f(v: Float): String {
        if (v == 0f) return "0"
        if (v == 1f) return "1"
        val scaled = Math.round(v * 10_000f).toFloat() / 10_000f
        return scaled.toString()
    }
}
