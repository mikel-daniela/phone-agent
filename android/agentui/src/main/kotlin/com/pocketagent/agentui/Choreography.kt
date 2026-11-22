package com.pocketagent.agentui

import kotlin.math.roundToInt

/**
 * **场景动效编排** —— 「一次交互里，谁在什么时候动、动多久、被打断时怎么办」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要它（以及为什么它不是 MotionRules 的重复）
 * ═══════════════════════════════════════════════════════════════
 *
 * [MotionRules] 回答的是**一个元素**怎么进场：多久、浮起几 dp、错峰多少。
 * 它解决"列表第 7 项什么时候出现"这个问题。
 *
 * 但它回答不了下面这类问题 —— 而这类问题才是让界面"像有东西在发生"的关键：
 *
 *   · 发送一条消息时，**输入框塌陷**和**气泡长出**谁先动？差多少毫秒？
 *   · 工具卡从"等你点头"变成"正在做"时，是**换一张卡**还是**同一张卡改姿态**？
 *   · 模型说话被用户打断时，波形是**淡出**还是**塌陷**？两者差在哪？
 *   · 从后台切回来，一个还在跑的任务，进度条是**从头播**还是**从断点续**？
 *
 * 这些都是**多元素、有先后、有因果**的问题 —— 它们属于"场景"，不属于"元素"。
 * 组件级动画各动各的，合起来就是"每个东西都在动，但没有一件事在发生"。
 *
 * ⇒ 本文件把**场景**变成一等公民：一个 [Scene] 是一组 [Step]，
 *   每个 Step 说清"谁、何时、多久、什么质量"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 五条规则（违反任何一条，界面的"高级感"立刻掉档）
 * ═══════════════════════════════════════════════════════════════
 *
 * **R1 一口气** —— 一个场景里，t=0 只允许**一个**元素起跑。
 *   其余元素跟随（[Step.atMs] > 0）。理由：人眼在 100ms 内只能锁定一个运动主体；
 *   两个同时起跑的东西会互相争夺注意力，观感是"乱"，而不是"丰富"。
 *   ⚠️ 这条是可测的：`steps.count { it.atMs == 0 } <= 1`。
 *
 * **R2 一块石头** —— 一个场景里最多**一个** [Mass.STONE] 元素在动。
 *   石头 = 底栏、抽屉、接管条、通话主视觉 —— 它们体积大、惯量大。
 *   两块石头同时动，屏幕会像"整块板子在晃"，让人轻微眩晕。
 *   ⚠️ 同样可测：`steps.count { it.mass == STONE } <= 1`。
 *
 * **R3 位移不用 Emphasized** —— `PaEasing.Emphasized` 是 (0.16, 1, 0.3, 1)，
 *   前 20% 走完 80% 距离。它给**淡入/颜色**极好，给**位移**是灾难：
 *   24dp 的位移会在 56ms 内"唰"地到位，然后原地磨 224ms —— 看起来像卡住。
 *   位移一律 [Curve.STANDARD]，淡入一律 [Curve.EMPHASIZED]。
 *
 * **R4 过冲 ≤ 3%** —— [Step.settle] 是"落定时的过冲幅度"，上限定在 0.03。
 *   理由：手机上任何超过 3% 的过冲都会读成"弹了一下"，那是玩具感的来源。
 *   真实世界里，一张卡片被放到桌上不会回弹 —— 它只是"压实"。
 *
 * **R5 可打断，且打断后不回起点** —— 每个场景声明 [OnInterrupt]：
 *   · [OnInterrupt.CONTINUE] 从**当前值**继续（发送中的输入框被新输入打断）
 *   · [OnInterrupt.FREEZE]   停在半路（接管时不该有任何东西还在往终点跑）
 *   · [OnInterrupt.CANCEL]   回到起点（卡片登场一半被撤销）
 *   ⚠️ 绝不能"回起点再重播"：那会让用户看到两次同样的动作，
 *      而第二次是**没有原因**的 —— 这是他开始觉得"这 app 有点卡"的起点。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三种"质量"（Mass）—— 本设计的物理骨架
 * ═══════════════════════════════════════════════════════════════
 *
 * | 质量 | 谁 | 时长 | 曲线 | 为什么 |
 * |---|---|---|---|---|
 * | FEATHER 羽毛 | 文字、徽章、光标、点 | 160–200ms | STANDARD | 轻的东西一碰就走，等它反而显得迟疑 |
 * | PAPER 纸 | 卡片、气泡、列表项 | 240–300ms | SETTLE(spring 0.8) | 纸有质量但没有惯性，落下即止 |
 * | STONE 石 | 底栏、抽屉、接管条、通话 | 320–420ms | STANDARD + 长尾 | 石头动得慢，动了就有"整块在移动"的压迫感 |
 *
 * ⚠️ 这三种质量**不是装饰**，它们编码了"这个东西有多重、能不能被随手推开"。
 *   把接管条（石）做成 160ms 淡入，用户会觉得"它是不是没真的停下来"——
 *   因为**轻=不重要**，而"已经停下"这件事必须显得重。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 与 :app 的分工（别在这里 import compose）
 * ═══════════════════════════════════════════════════════════════
 *
 * 本文件**不含任何动画执行代码** —— 它只产出"编排表"。
 * `:app` 的 `ui/design/Choreo.kt` 读这张表，把每个 Step 变成一个
 * `Animatable` / `animateFloatAsState`。
 *
 * 这样切分的好处与 [MotionRules] 完全一致：**规则可以离线测**。
 * "t=0 只有一个元素起跑""总时长不超过 640ms""省电模式下位移必须为 0"
 * 这些判断在真机上要一帧一帧看，在这里是一条 assert。
 */
object Choreography {

    // ═══════════════════════════════════════════════════════════
    //  基本类型
    // ═══════════════════════════════════════════════════════════

    /**
     * 元素的质量。决定曲线族与"允许多长"。
     *
     * ⚠️ 它与 [Step.durationMs] 是**两件事**：duration 由场景给（因为同一张卡
     *    在"登场"和"落定"时该用不同时长），Mass 只决定**曲线形状**与**约束**
     *    （R2：一个场景最多一块石头）。
     */
    enum class Mass {
        /** 羽毛：文字、徽章、光标、点阵。 */
        FEATHER,

        /** 纸：卡片、气泡、列表项。 */
        PAPER,

        /** 石：底栏、抽屉、接管条、通话主视觉。 */
        STONE,
    }

    /** 变化的维度。渲染层据此决定改哪个属性。 */
    enum class Axis {
        /** 透明度。 */
        ALPHA,

        /** 纵向位移（负 = 向上浮起）。 */
        Y,

        /** 横向位移（负 = 从右侧进入）。 */
        X,

        /** 缩放。 */
        SCALE,

        /** 高度/长度（顶部进度线、竖脊、波形条）。 */
        EXTENT,
    }

    /** 曲线族。`:app` 侧映射到 [PaEasing][com.pocketagent.ui.design] 的具体 CubicBezier。 */
    enum class Curve {
        /** (0.16, 1, 0.3, 1) —— 只给淡入与颜色。 */
        EMPHASIZED,

        /** (0.2, 0, 0, 1) —— 位移专用（R3）。 */
        STANDARD,

        /** (0.4, 0, 1, 1) —— 快速离场，比进场快。 */
        EXIT,

        /** spring(dampingRatio = 0.8, stiffness = MediumLow) —— 纸的落定。 */
        SETTLE,

        /** 匀速 —— 只给"持续流动"（进度线、波形），不给任何一次性动作。 */
        LINEAR,
    }

    /**
     * 场景里可被单独驱动的元素。
     *
     * ⚠️ 刻意**不做成通用字符串**：enum 让"这个名字打错了"变成编译错误，
     *    而不是一个永远不动的元素（那种 bug 在真机上极难发现 —— 它只是"没有动画"）。
     */
    enum class Target {
        // ── 冷启动 ──
        /** 开场白 / 英雄标题。 */
        HEADLINE,

        // ── 输入区 ──
        /** 整个输入条（高度会塌陷/回弹）。 */
        COMPOSER,

        /** 发送箭头。 */
        SEND_ICON,

        // ── 消息流 ──
        /** 用户气泡。 */
        USER_BUBBLE,

        /** 列表整体（滚动跟随）。 */
        MESSAGE_LIST,

        /** 等待首 token 的三点。 */
        THINK_DOTS,

        /** 正在流入的那一行。 */
        STREAM_LINE,

        /** 笔尖光标。 */
        PEN_TIP,

        /** 段落之间的留白（"呼吸"）。 */
        PARA_GAP,

        // ── 卡片 ──
        /** 卡片本体。 */
        CARD,

        /** 卡片左侧的竖脊（"手伸出来的痕迹"）。 */
        CARD_SPINE,

        /** 状态徽章。 */
        CARD_BADGE,

        /** 卡片正文区。 */
        CARD_BODY,

        /** 失败卡的出口按钮组。 */
        CARD_EXITS,

        /** 卡片顶部的进度线（"正在动手"）。 */
        CARD_PROGRESS,

        // ── 接管 ──
        /** 接管条。 */
        TAKEOVER_BAR,

        /** 接管条里的按钮组。 */
        TAKEOVER_ACTIONS,

        // ── 通话 ──
        /** 呼吸环。 */
        CALL_RING,

        /** 旋转粒子。 */
        CALL_DOTS,

        /** 波形条。 */
        CALL_BARS,

        /** 打断时的一次涟漪。 */
        CALL_RIPPLE,

        // ── 抽屉 ──
        /** 抽屉面板。 */
        DRAWER_PANEL,

        /** 抽屉里的单个会话项。 */
        DRAWER_ITEM,

        // ── 归位 ──
        /** 归位时的"续接"标记（不播动画，只把进度对回去）。 */
        RESUME_MARK,
    }

    /**
     * 场景。
     *
     * ⚠️ 名字用 [Beat] 而不是 `Scene`，是为了避免与 Compose 的
     *    `androidx.compose.animation.AnimatedContentScope` 等概念在阅读时混淆 ——
     *    这是"叙事的一拍"，不是"界面的一屏"。
     */
    enum class Beat {
        /** 冷启动 → 首屏。 */
        COLD_START,

        /** 用户按下发送。 */
        SEND,

        /** 已发出，等首 token。 */
        AWAIT_FIRST_TOKEN,

        /** 流式正文正在长出新的一行。**高频**：每个新行一次。 */
        STREAM_LINE,

        /** 卡片登场（预演卡 / 动作卡）。 */
        CARD_ENTER,

        /** 预演 → 执行（"等你点头" → "正在做"）。 */
        CARD_COMMIT,

        /** 执行 → 完成（"正在做" → "做完了"）。 */
        CARD_LAND,

        /** 执行 → 失败。 */
        CARD_FAIL,

        /** 用户接管，整个界面减速。 */
        TAKEOVER,

        /** 通话：听 → 想。 */
        CALL_LISTEN_TO_THINK,

        /** 通话：想 → 说。 */
        CALL_THINK_TO_SPEAK,

        /** 通话：说 → 听（用户打断）。**与上面两个方向不同，因为它必须"塌陷"。** */
        CALL_SPEAK_TO_LISTEN,

        /** 抽屉打开（当前页后退）。 */
        DRAWER_OPEN,

        /** 从后台回来，任务仍在跑 —— **续接，不重播**。 */
        RESUME_ATTACH,
    }

    /**
     * 被打断时怎么办（R5）。
     */
    enum class OnInterrupt {
        /** 回到起点。用于"这件事被撤销了"（卡片登场一半被取消）。 */
        CANCEL,

        /** 停在当前值。用于"世界该静下来"（接管、失败）。 */
        FREEZE,

        /** 从当前值继续到新终点。用于"同一件事还在继续"（输入框塌陷中被新输入打断）。 */
        CONTINUE,
    }

    /**
     * 一拍：某个元素在某个时刻开始的**一次**变化。
     *
     * @param atMs 相对场景起点的延迟。0 表示"它就是起跑者"，一个场景只能有一个（R1）。
     * @param durationMs 这一段持续多久。0 = 直接到终点（归位时用）。
     * @param fromAlpha 起始透明度（终点恒为 1，除非 [Axis.EXTENT] 的收拢段）。
     * @param riseDp 纵向起始偏移，单位 dp。负数 = 从下方浮起（更常见）。
     *   正数用于"向下顿一下"（失败时的撞墙感）。
     * @param slideDp 横向起始偏移，单位 dp。负数 = 从右侧滑入。
     * @param fromScale 起始缩放。1.0 = 不缩放。
     * @param settle 落定过冲幅度，0..[MAX_SETTLE]（R4）。
     * @param axis 主要变化的维度 —— 渲染层据此选属性，[curveFor] 据此选曲线。
     */
    data class Step(
        val target: Target,
        val atMs: Int,
        val durationMs: Int,
        val mass: Mass,
        val axis: Axis = Axis.Y,
        val fromAlpha: Float = 0f,
        val riseDp: Float = 0f,
        val slideDp: Float = 0f,
        val fromScale: Float = 1f,
        val settle: Float = 0f,
        /**
         * 这一段在视觉上属于哪个**主体**（默认就是 [target] 自己）。
         *
         * ★ 为什么要区分"图层"和"主体"：
         *   笔尖（PEN_TIP）和落下的那一行（STREAM_LINE）是**同一个动作的两个图层** ——
         *   笔尖在线头上，它们必须同时动，拆开就散架了。但它们是两个 [Target]，
         *   所以"一口气只能有一个元素起跑"（R1）若按 Target 计数就会误判。
         *
         *   判据是**主体**不是图层：三个呼吸环、九根波形条、笔尖+那一行，
         *   各自都是一个主体 —— 一个主体内部多少图层同时动都不算"争夺视线"。
         */
        val subject: Target = target,
        // ── 终点 ───────────────────────────────────────────────
        //
        // ★ 默认终点是"静止态"（不透明、位正、原大、满长）—— 也就是绝大多数
        //   进场动画的样子：从某个起点回到它本来就该在的地方。
        //
        // ★ 但**不是所有动作都是"归位"**：
        //    · 失败卡片的第一段是**撞墙**（0 → +1.5dp 向下），不是归位
        //    · 呼吸环收拢是**从满到核**（1 → 0.15），不是归位
        //   只有补上终点，这两件事才写得出来；否则只能靠 from 表达，
        //   而 from 表达"收拢"会让"起点"变成终点 —— 语义刚好反了。
        val toAlpha: Float = 1f,
        val toRiseDp: Float = 0f,
        val toSlideDp: Float = 0f,
        val toScale: Float = 1f,
        val toExtent: Float = 1f,
        /**
         * [Axis.EXTENT] 段的**起点**长度。
         *
         * 默认 0 —— 也就是"从无到有地长出来"（进度线、呼吸环、涟漪半径）。
         * 反向动作必须显式给 1：波形"掐断"是 1 → 0，如果从 0 起就变成了
         * "从地面升起来"，那是**相反**的语义。
         */
        val fromExtent: Float = 0f,
    ) {
        /** 这一段结束的时刻。 */
        val endMs: Int get() = atMs + durationMs

        /** 纵向位移的方向与幅度（正=向下）。撞墙段为正，归位段为负。 */
        val riseDeltaDp: Float get() = toRiseDp - riseDp

        /** 缩放的方向与幅度（负=收拢）。 */
        val scaleDelta: Float get() = toScale - fromScale
    }

    /**
     * 一个场景的完整编排。
     *
     * @param totalMs 最后一段结束的时刻。**这是场景的"承诺时长"** ——
     *   超过 [MAX_SCENE_MS] 的场景会被测试拒绝。
     * @param static 是否处于"完全不动"降级（系统关了动画）。此时 [steps] 为空。
     * @param speed 全局速度系数（[Beat.TAKEOVER] 用 0.35 让世界减速）。
     */
    data class Scene(
        val beat: Beat,
        val steps: List<Step>,
        val totalMs: Int,
        val interrupt: OnInterrupt,
        val static: Boolean = false,
        val speed: Float = 1f,
    ) {
        /** 某个元素在这一场里的所有拍（同一元素可以有多段，例如"顿一下"再"回位"）。 */
        fun stepsOf(target: Target): List<Step> = steps.filter { it.target == target }

        /** 某个元素的第一拍（多数调用方只关心这个）。 */
        fun first(target: Target): Step? = steps.firstOrNull { it.target == target }

        /** 有没有东西要动。 */
        val isEmpty: Boolean get() = steps.isEmpty()

        /** 这一场是否是"只对位、不动画"（归位）。 */
        val snapOnly: Boolean get() = steps.isNotEmpty() && steps.all { it.durationMs == 0 }
    }

    // ═══════════════════════════════════════════════════════════
    //  约束常量（测试拿它们当上限）
    // ═══════════════════════════════════════════════════════════

    /** 单个场景的时长上限。超过它，用户会感到"在等动画"。 */
    const val MAX_SCENE_MS: Int = 640

    /** 落定过冲上限（R4）。 */
    const val MAX_SETTLE: Float = 0.03f

    /** 接管时的减速系数 —— 世界变慢，但不停止（停止=卡死，变慢=让位）。 */
    const val TAKEOVER_SPEED: Float = 0.35f

    /** 抽屉里会话项的错峰步长与封顶。比 [MotionRules] 的 40ms 更紧：抽屉是"扫一眼"的场景。 */
    const val DRAWER_STAGGER_STEP_MS: Int = 30
    const val DRAWER_STAGGER_MAX_MS: Int = 180

    // ═══════════════════════════════════════════════════════════
    //  质量 → 曲线
    // ═══════════════════════════════════════════════════════════

    /**
     * 给定质量与变化维度，该用哪条曲线。
     *
     * 顺序很重要：
     *  1. **淡入永远 EMPHASIZED** —— 它是唯一"越快越自然"的维度（视觉残留会补足过程）。
     *  2. **EXTENT 用 LINEAR** —— 进度线/波形是"持续"，任何缓动都会让它读成"快好了"。
     *  3. 其余按质量：羽毛 STANDARD（不拖），纸 SETTLE（落下即止），石 STANDARD（长尾由 duration 给）。
     */
    /**
     * 给一段动画挑曲线。**质量不改曲线，只改时长与过冲。**
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 这里踩过一次：PAPER 原本走 SETTLE（EMPHASIZED_DECELERATE）
     * ═══════════════════════════════════════════════════════════
     *
     * 直觉是"纸比羽毛重，所以用一条更'沉'的曲线"。但 `(0.05, 0.7, 0.1, 1)`
     * 在**位移**上意味着：5.7% 的时间（260ms 的 16ms）就走完 48% 的距离。
     * 8dp 的落下因此变成"第一帧就到 4dp，剩下 20 帧慢慢蹭" ——
     * 正是 `Motion.kt` 里点名批评过的"唰一下到位再原地磨"。
     * 测试 `位移不会在一帧内跳太远` 抓到了它（一帧 3.85dp / 段总长 8dp）。
     *
     * ⇒ 铁律回收：**位移只走 STANDARD（0.2, 0, 0, 1）**。
     *    · 曲线管"形状"，全站只有两种形状：位移一种、淡入一种。
     *    · 质量（羽毛/纸/石头）体现在**时长**上（石头更久）和**过冲**上（[Step.settle]）。
     *    · 这比"给每档质量配一条曲线"更好解释，也更好调 ——
     *      调重量只动一个数字，不会顺手把"落点在哪"也改了。
     *
     * 唯一的例外是 SCALE：压实时那一点点回弹属于"形变"而不是"位移"，
     * 它要的是**停在原地**地收一下，所以走 SETTLE（更快的收尾）。
     */
    fun curveFor(mass: Mass, axis: Axis): Curve = when {
        axis == Axis.EXTENT -> Curve.LINEAR
        axis == Axis.ALPHA -> Curve.EMPHASIZED
        axis == Axis.SCALE -> if (mass == Mass.FEATHER) Curve.EMPHASIZED else Curve.SETTLE
        // X / Y：位移一律 STANDARD（见上）
        else -> Curve.STANDARD
    }

    /** 错峰延迟（带封顶）。 */
    fun stagger(atMs: Int, index: Int, stepMs: Int, maxMs: Int): Int {
        if (index <= 0) return atMs
        return atMs + (index * stepMs).coerceAtMost(maxMs)
    }

    // ═══════════════════════════════════════════════════════════
    //  场景表
    // ═══════════════════════════════════════════════════════════

    /**
     * 构造一个场景，并按当前设置降级。
     *
     * @param animationsOn 系统动画开关。false ⇒ **空场景**（duration 0，直接到位）。
     *   与 [MotionRules.plan] 同一条纪律：关掉动画之后还播 260ms，是另一种卡。
     * @param richMotion 是否允许位移/缩放。false ⇒ 只留淡入（省电）。
     *   ⚠️ 省电模式下**仍然保留**淡入：硬切是"粗糙"，淡入几乎不耗合成。
     * @param speed 速度系数。<1 变慢（接管），>1 变快（通话打断）。
     */
    fun scene(
        beat: Beat,
        animationsOn: Boolean = true,
        richMotion: Boolean = true,
        speed: Float = 1f,
    ): Scene {
        val s = speed.coerceIn(0.25f, 4f)

        if (!animationsOn) {
            return Scene(beat, emptyList(), totalMs = 0, interrupt = OnInterrupt.CANCEL, static = true)
        }

        val raw = rawSteps(beat).map { step ->
            step.copy(
                atMs = (step.atMs / s).roundToInt(),
                durationMs = (step.durationMs / s).roundToInt(),
            )
        }

        val steps = if (richMotion) {
            raw
        } else {
            // 省电：位移、缩放、过冲全部归零 —— 只留 alpha。
            raw.map { it.copy(riseDp = 0f, slideDp = 0f, fromScale = 1f, settle = 0f, axis = it.axis) }
        }

        return Scene(
            beat = beat,
            steps = steps,
            totalMs = steps.maxOfOrNull { it.endMs } ?: 0,
            interrupt = interruptOf(beat),
            static = false,
            speed = s,
        )
    }

    /** 每个场景被打断时的默认处理（R5）。 */
    fun interruptOf(beat: Beat): OnInterrupt = when (beat) {
        // 被撤销 → 回起点（用户明确"不要了"）
        Beat.CARD_ENTER -> OnInterrupt.CANCEL
        Beat.CARD_FAIL -> OnInterrupt.FREEZE

        // 世界该静下来
        Beat.TAKEOVER -> OnInterrupt.FREEZE
        Beat.RESUME_ATTACH -> OnInterrupt.FREEZE

        // 同一件事还在继续
        Beat.SEND -> OnInterrupt.CONTINUE
        Beat.AWAIT_FIRST_TOKEN -> OnInterrupt.CONTINUE
        Beat.STREAM_LINE -> OnInterrupt.CONTINUE
        Beat.CARD_COMMIT -> OnInterrupt.CONTINUE
        Beat.CARD_LAND -> OnInterrupt.CONTINUE

        // 其余按"继续"处理：取消一个已经开始的界面变化，比让它走完更让人困惑
        Beat.COLD_START,
        Beat.CALL_LISTEN_TO_THINK,
        Beat.CALL_THINK_TO_SPEAK,
        Beat.CALL_SPEAK_TO_LISTEN,
        Beat.DRAWER_OPEN,
        -> OnInterrupt.CONTINUE
    }

    /**
     * 场景的原始编排表。
     *
     * ⚠️ 一个场景里**绝不允许**出现两个 `atMs == 0`（R1）——
     *    这是本设计里最容易被后人"顺手加一个"破坏的约束，
     *    所以它被写进了测试（`ChoreographyTest`）。
     */
    private fun rawSteps(beat: Beat): List<Step> = when (beat) {

        // ══════════════════════════════════════════════════════
        //  冷启动：一句话在等你
        // ══════════════════════════════════════════════════════
        //
        // 用户点开 icon 到看见东西，系统给了约 300ms 的"启动画面"余量。
        // 这 300ms 里**底色已经在了**（极光背景在 onCreate 后的第一帧就在），
        // 所以观感上不是"从黑到亮"，而是"从空到有人"。
        //
        // ⇒ 开场白 120ms 起跑（羽毛，但它写得慢：520ms，因为**它在被读**），
        //    输入框 260ms 跟上（纸，280ms —— 它是要被碰的东西，得显得结实）。
        //    发送箭头最后 +160ms 出现：它是"最后一个可用的东西"，
        //    先出现会让人以为"已经可以按了"，而那时输入框还没站稳。
        Beat.COLD_START -> listOf(
            Step(
                target = Target.HEADLINE,
                atMs = 120,
                durationMs = 520,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
                riseDp = -12f,
            ),
            Step(
                target = Target.COMPOSER,
                atMs = 260,
                durationMs = 280,
                mass = Mass.PAPER,
                riseDp = 8f,
            ),
            Step(
                target = Target.SEND_ICON,
                atMs = 420,
                durationMs = 160,
                mass = Mass.FEATHER,
                axis = Axis.SCALE,
                fromScale = 0.90f,
                fromAlpha = 0.6f,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  发送：气泡从输入框里"长出来"
        // ══════════════════════════════════════════════════════
        //
        // ★ 因果：文字**离开**输入框，变成气泡。所以
        //     · 气泡先动（20ms）：它是"结果"
        //     · 输入框随后塌陷（40ms）：它是"容器复位"
        //   反过来（先清空输入框，再出现气泡）会有一帧"文字凭空消失"，
        //   那一帧是用户唯一可能怀疑"我是不是没发出去"的时刻。
        //
        // ⚠️ 气泡从 0.72 起 —— 不是 0。0 会是"从无到有"（像弹窗），
        //    0.72 + 从输入框方向浮起 8dp 才是"从那个地方长出来"。
        //    这与 `PaCircleRevealEffect`（从右下角圆形扩散）是同一件事的两种表达，
        //    能取到真实坐标时用圆形扩散，取不到时用这一段。
        Beat.SEND -> listOf(
            Step(
                target = Target.USER_BUBBLE,
                atMs = 20,
                durationMs = 280,
                mass = Mass.PAPER,
                fromScale = 0.72f,
                riseDp = 8f,
            ),
            Step(
                target = Target.COMPOSER,
                atMs = 40,
                durationMs = 260,
                mass = Mass.PAPER,
                axis = Axis.EXTENT,
            ),
            Step(
                target = Target.SEND_ICON,
                atMs = 0,
                durationMs = 120,
                mass = Mass.FEATHER,
                axis = Axis.SCALE,
                fromScale = 0.86f,
            ),
            Step(
                target = Target.MESSAGE_LIST,
                atMs = 60,
                durationMs = 300,
                mass = Mass.PAPER,
                axis = Axis.Y,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  等首 token：三个点的呼吸
        // ══════════════════════════════════════════════════════
        //
        // ⚠️ 这里**刻意不用 CircularProgressIndicator**：转圈=机械，
        //    而用户在等的是"一个人组织语言"，不是"一个任务在下载"。
        //
        // ⚠️ duration 只有 200ms 且只有一条：这是**高频**场景的邻居
        //    （每次发送都会经历），任何更长的编排都会在第十次对话时变成负担。
        Beat.AWAIT_FIRST_TOKEN -> listOf(
            Step(
                target = Target.THINK_DOTS,
                atMs = 0,
                durationMs = 200,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
                fromScale = 0.94f,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  落笔：新的一行出现
        // ══════════════════════════════════════════════════════
        //
        // ★★ 这是整个产品里**最频繁**的一次动画（一次回复几十次），
        //    所以它的设计目标不是"好看"，而是"不被看见"：
        //     · 只有 180ms（羽毛）
        //     · 只浮 6dp（比标准 8dp 更小 —— 高频动作的幅度必须收敛）
        //     · 只有一行在动，已稳的行**完全不动**（否则整段文字每来一片就重排，
        //       那是"文字在抖"，是廉价感最直接的来源）
        //
        // ★ 段落间距的"呼吸"（320ms，纸）：新段落不是"另起一行"，
        //   而是"上一段说完了，停一下"。这 8dp 的留白是唯一一处
        //   刻意让用户**感到停顿**的设计 —— 它对应模型真的在想下一段。
        Beat.STREAM_LINE -> listOf(
            Step(
                target = Target.STREAM_LINE,
                atMs = 0,
                durationMs = 180,
                mass = Mass.FEATHER,
                riseDp = 6f,
            ),
            Step(
                target = Target.PEN_TIP,
                // ★ 笔尖是那一行的图层，不是另一个元素：它必须和线同拍，
                //   晚 1ms 都会让"笔尖拖着线"读成"两个东西在追跑"。
                subject = Target.STREAM_LINE,
                atMs = 0,
                durationMs = 120,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
                fromAlpha = 0.55f,
            ),
            Step(
                target = Target.PARA_GAP,
                atMs = 40,
                durationMs = 320,
                mass = Mass.PAPER,
                axis = Axis.EXTENT,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  卡片登场：一只手伸出来
        // ══════════════════════════════════════════════════════
        //
        // ★ 母题：**竖脊先出现**（CARD_SPINE，左侧那条 2dp 的强调色线）。
        //    它在 180ms 内从 0 长到满高 —— 像一支笔先落下，再写字。
        //    卡片本体（纸，280ms）在 40ms 后跟上，把竖脊"包"进去。
        //
        // ★ 徽章最后（180ms 起）：用户要先读"做什么"，再读"什么状态"。
        //    徽章先出现会把视线拽到右上角，而那时正文还没画完。
        Beat.CARD_ENTER -> listOf(
            Step(
                target = Target.CARD_SPINE,
                atMs = 0,
                durationMs = 180,
                mass = Mass.FEATHER,
                axis = Axis.EXTENT,
            ),
            Step(
                target = Target.CARD,
                atMs = 40,
                durationMs = 280,
                mass = Mass.PAPER,
                riseDp = 12f,
            ),
            Step(
                target = Target.CARD_BODY,
                atMs = 120,
                durationMs = 240,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
            ),
            Step(
                target = Target.CARD_BADGE,
                atMs = 180,
                durationMs = 160,
                mass = Mass.FEATHER,
                axis = Axis.X,
                slideDp = -8f,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  预演 → 执行："蜷起来，准备用力"
        // ══════════════════════════════════════════════════════
        //
        // ★ 为什么是"收窄"而不是"长大"：
        //    用户刚点了「可以」，此刻卡片在**收缩注意力**（像握紧）。
        //    如果它反而放大，会读成"它在膨胀/在炫耀"，与"我要动手了"的语义相反。
        //
        // ★ 顶部进度线（EXTENT，LINEAR，480ms）：**不表示进度**。
        //    它的语义是"这只手正在动"。所以它是匀速的、不回收的，
        //    直到 [CARD_LAND] 才收回。用户永远不会因为它"卡在 60%"而焦虑 ——
        //    因为它从不显示百分比，也不该有"该到哪了"的预期。
        Beat.CARD_COMMIT -> listOf(
            Step(
                target = Target.CARD,
                atMs = 0,
                durationMs = 120,
                mass = Mass.PAPER,
                axis = Axis.SCALE,
                fromScale = 1.004f,
            ),
            Step(
                target = Target.CARD_BADGE,
                atMs = 60,
                durationMs = 160,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
            ),
            Step(
                target = Target.CARD_PROGRESS,
                atMs = 120,
                durationMs = 480,
                mass = Mass.STONE,
                axis = Axis.EXTENT,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  执行 → 完成："放下"
        // ══════════════════════════════════════════════════════
        //
        // ★ 顺序是**先收线，再盖章**：
        //     · 进度线 240ms 收回（EXIT 曲线 —— 离场比进场快，这是物理常识）
        //     · 徽章 160ms 从右滑入 + 3% 过冲（"啪"一下贴上去）
        //     · 卡片 260ms 压实（SETTLE spring）—— 高度少 2dp
        //   这三步合起来是"事情办完了，手收回去了"。
        //
        // ⚠️ 没有庆祝动画。一个替你操作手机的东西做完了事，
        //    该像同事把东西放回你桌上，而不是放烟花。
        Beat.CARD_LAND -> listOf(
            Step(
                target = Target.CARD_PROGRESS,
                atMs = 0,
                durationMs = 240,
                mass = Mass.FEATHER,
                axis = Axis.EXTENT,
            ),
            Step(
                target = Target.CARD_BADGE,
                atMs = 40,
                durationMs = 160,
                mass = Mass.PAPER,
                axis = Axis.X,
                slideDp = -8f,
                settle = MAX_SETTLE,
            ),
            Step(
                target = Target.CARD,
                atMs = 80,
                durationMs = 260,
                mass = Mass.PAPER,
                axis = Axis.SCALE,
                fromScale = 0.998f,
                settle = 0.02f,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  失败："撞了一下墙"
        // ══════════════════════════════════════════════════════
        //
        // ★ 失败有自己的物理：东西撞到东西 → 顿一下 → 弹回。
        //    · 0–40ms 向下 1.5dp（FEATHER，几乎没有缓动：撞击是瞬时的）
        //    · 40–280ms 回到 0（PAPER，SETTLE）—— 它"归位"了，但那件事没做成
        //
        // ★ 出口按钮**一个一个出现**（120ms 起，每条 +60ms，封顶 180）：
        //    失败时刻用户是懵的，一次给三个选项他会随便点一个。
        //    依次出现强迫视线逐条扫过 —— 这是"选项"该有的节奏。
        Beat.CARD_FAIL -> listOf(
            Step(
                target = Target.CARD,
                atMs = 0,
                durationMs = 40,
                mass = Mass.FEATHER,
                // ★ 0 → +1.5dp：它是"撞下去"的，终点在下方。
                //   写成 from = 1.5 会把语义反过来（那成了"从下面浮上来"）。
                toRiseDp = 1.5f,
            ),
            Step(
                target = Target.CARD,
                atMs = 40,
                durationMs = 240,
                mass = Mass.PAPER,
                // 从撞下去的位置（+1.5）回到 0 —— 起点必须接住上一段的终点。
                riseDp = 1.5f,
                // ★ 它已经在了：不透明必须从 1 起，否则归位会顺带"再淡入一次"，
                //   而重播淡入正好是这个设计最不想要的那件事（见 RESUME_ATTACH）。
                fromAlpha = 1f,
            ),
            Step(
                target = Target.CARD_BADGE,
                atMs = 80,
                durationMs = 160,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
            ),
            Step(
                target = Target.CARD_EXITS,
                atMs = 120,
                durationMs = 200,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
                riseDp = 4f,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  接管："世界慢下来"
        // ══════════════════════════════════════════════════════
        //
        // ★ 这是唯一一个**改变全局速度**的场景（speed = 0.35）。
        //    理由是心理的：用户按下「我来」的那一刻，他希望"这东西停下了"。
        //    如果界面上还有东西在正常速度地跑（哪怕是淡入），他会怀疑没停下。
        //    把所有后续动画拖慢到 1/3，是"它让位了"最直接的表达。
        //
        // ★ 接管条是**石头**（320ms，从底部 24dp 上来）：
        //    它必须显得重，因为"已经停下"这件事必须显得重。
        //    按钮组再晚 120ms —— 先看到"停了"，才看到"可以接着来"。
        Beat.TAKEOVER -> listOf(
            Step(
                target = Target.TAKEOVER_BAR,
                atMs = 0,
                durationMs = 320,
                mass = Mass.STONE,
                riseDp = 24f,
            ),
            Step(
                target = Target.TAKEOVER_ACTIONS,
                atMs = 120,
                durationMs = 200,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  通话：听 → 想
        // ══════════════════════════════════════════════════════
        //
        // ★★ 这是本设计里唯一"形变"而不是"替换"的地方。
        //    现状（`CallMotion`）是 `when (motionFor(phase))` —— 硬切：
        //    三圈呼吸环**瞬间消失**，五个粒子**瞬间出现**。
        //    用户会看到一次"闪"，而打电话时他在听，那一次闪像是"信号断了"。
        //
        // ⇒ 正确的事：环**收拢**成一个点（240ms），点**炸开**成粒子（180ms）。
        //    同一个东西，两种状态 —— 这正是"它在从听变成想"的视觉语法。
        //
        // ⚠️ 收拢用 fromScale 0.15（不是 0）：0 会让它"消失"，
        //    而它必须留下一个"核"，因为下一段要从那个核里长出来。
        Beat.CALL_LISTEN_TO_THINK -> listOf(
            Step(
                target = Target.CALL_RING,
                atMs = 0,
                durationMs = 240,
                mass = Mass.STONE,
                axis = Axis.SCALE,
                // ★ 1 → 0.15：收拢成核。终点是核，不是起点。
                toScale = 0.15f,
            ),
            Step(
                target = Target.CALL_DOTS,
                atMs = 240,
                durationMs = 180,
                mass = Mass.FEATHER,
                axis = Axis.SCALE,
                fromScale = 0.40f,
                settle = MAX_SETTLE,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  通话：想 → 说
        // ══════════════════════════════════════════════════════
        //
        // ★ 粒子**塌进中心**（180ms），然后波形从中间向两边**依次站起**
        //    （180ms 起，9 根，每根 +30ms，封顶 180）。
        //    这个方向的隐喻是"凝聚成声音"—— 从中心向外扩散的波纹是"回声"，
        //    而从中心站起来的竖条是"开口说话"。
        Beat.CALL_THINK_TO_SPEAK -> listOf(
            Step(
                target = Target.CALL_DOTS,
                atMs = 0,
                durationMs = 180,
                mass = Mass.FEATHER,
                axis = Axis.SCALE,
                // ★ 1 → 0.30：粒子塌进中心，随后波形从那个位置站起。
                toScale = 0.30f,
            ),
            Step(
                target = Target.CALL_BARS,
                atMs = 180,
                durationMs = 270,
                mass = Mass.STONE,
                axis = Axis.EXTENT,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  通话：说 → 听（用户打断）
        // ══════════════════════════════════════════════════════
        //
        // ★★ 打断必须是**塌陷**，不是淡出。
        //    用户按住说话的那一刻，声音是被"掐断"的 —— 物理上它没有
        //    渐渐变小，它停了。所以：
        //     · 波形 60ms 高度归零（比任何进场都快，接近瞬时）
        //     · 40ms 后一次 240ms 的涟漪外扩（"刚才那一下"的余韵）
        //     · 触感 reject()（见 `:app` 的 Haptics）
        //   淡出会让人以为"它还在说，只是在变小声"，那是最糟的误解 ——
        //   用户会继续等，而它已经停了。
        Beat.CALL_SPEAK_TO_LISTEN -> listOf(
            Step(
                target = Target.CALL_BARS,
                atMs = 0,
                durationMs = 60,
                mass = Mass.FEATHER,
                axis = Axis.EXTENT,
                // ★ 满长 → 0：高度直接归零。这是"掐断"，不是"变小声"。
                fromExtent = 1f,
                toExtent = 0f,
            ),
            Step(
                target = Target.CALL_RIPPLE,
                atMs = 40,
                durationMs = 240,
                mass = Mass.STONE,
                axis = Axis.EXTENT,
                // 半径 0 → 满：一次涟漪外扩，是"刚才那一下"的余韵。
            ),
            Step(
                target = Target.CALL_RING,
                atMs = 240,
                durationMs = 200,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  抽屉：当前页后退，会话项依次落座
        // ══════════════════════════════════════════════════════
        //
        // ★ 抽屉本身由 `ModalNavigationDrawer` 驱动（滑入 300ms 左右），
        //    这里只编排**它打开之后**的事：会话项依次淡入（30ms 步长，封顶 180）。
        //    这 180ms 的错峰是"这里有一份历史"的最小表达 ——
        //    全部同时出现会像一张静态截图。
        //
        // ⚠️ 当前页**不做**缩放（很多 app 会做 0.9）。理由：这个 app 的
        //    页面里已经有玻璃与极光两层半透明，再叠一层缩放会露出底下的
        //    渐变断层 —— 那比"没有景深"更难看。
        Beat.DRAWER_OPEN -> listOf(
            Step(
                target = Target.DRAWER_PANEL,
                atMs = 0,
                durationMs = 300,
                mass = Mass.STONE,
                axis = Axis.X,
                slideDp = -320f,
            ),
            Step(
                target = Target.DRAWER_ITEM,
                atMs = 80,
                durationMs = 200,
                mass = Mass.FEATHER,
                riseDp = 6f,
            ),
        )

        // ══════════════════════════════════════════════════════
        //  归位：续接，不重播
        // ══════════════════════════════════════════════════════
        //
        // ★★ 这是全部场景里唯一 duration = 0 的。
        //    从后台切回来时，一个还在跑的任务**不能重播**它的进场 ——
        //    重播等于告诉用户"它刚刚才开始"，而事实是"它一直在跑"。
        //    ⇒ 只做一次"对位"：把进度线的值直接设到它现在应该在的位置。
        //
        // ⚠️ 这也是为什么进度值必须存在 ViewModel 里而不是 composable 的
        //    `remember` 里 —— `remember` 会在进程被回收后丢失，
        //    而"任务还在跑"这件事活过了进程。
        Beat.RESUME_ATTACH -> listOf(
            Step(
                target = Target.RESUME_MARK,
                atMs = 0,
                durationMs = 0,
                mass = Mass.FEATHER,
                axis = Axis.ALPHA,
            ),
        )
    }
}
