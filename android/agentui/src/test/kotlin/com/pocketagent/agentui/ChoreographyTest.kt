package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **场景编排**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的东西，全都是"能跑但手感坏了"的类型
 * ═══════════════════════════════════════════════════════════════
 *
 * 编排写错了**不会报错**。它只会让用户觉得"这 App 有点乱"或"有点慢"——
 * 而那种感觉极难归因到某一行。所以每一条不变量都要用数字钉死：
 *
 *  ① 一口气 —— 两个元素同时起跑 ⇒ 争夺视线 ⇒ "乱"
 *  ② 一块石头 —— 两块大体积元素同时动 ⇒ "整块板子在晃" ⇒ 轻微眩晕
 *  ③ 时长上限 —— 超过 640ms ⇒ 用户在等动画，而不是在看结果
 *  ④ 过冲上限 —— 超过 3% ⇒ 读成"弹了一下" ⇒ 玩具感
 *  ⑤ 归位不重播 —— 重播 ⇒ "它刚刚才开始" ⇒ 与事实相反
 *  ⑥ 打断要快 —— 淡出 ⇒ "它还在说，只是变小声" ⇒ 最糟的误解
 *
 * ⚠️ 这些测试**故意**写成"遍历所有 Beat"：以后新增一个场景，
 *    只要它违反了哪一条，这里就会红 —— 而不是等三个月后有人抱怨"有点卡"。
 */
class ChoreographyTest {

    private val beats: List<Choreography.Beat> = Choreography.Beat.values().toList()

    // ═══════════════════════════════════════════════════════════
    //  完整性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每个场景都有编排`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            assertFalse("$beat 没有任何一段编排", scene.isEmpty)
        }
    }

    @Test
    fun `每个场景都声明了打断语义`() {
        beats.forEach { beat ->
            assertNotNull("$beat 没有声明打断语义", Choreography.interruptOf(beat))
        }
        // 两头的极端必须钉死
        assertEquals(
            "接管时世界该静下来，不能还在往终点跑",
            Choreography.OnInterrupt.FREEZE,
            Choreography.interruptOf(Choreography.Beat.TAKEOVER),
        )
        assertEquals(
            "卡片登场被撤销时该回起点",
            Choreography.OnInterrupt.CANCEL,
            Choreography.interruptOf(Choreography.Beat.CARD_ENTER),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ① 一口气
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一个场景只能有一个主体在零点起跑`() {
        beats.forEach { beat ->
            val runners = Choreography.scene(beat).steps
                .filter { it.atMs == 0 }
                .map { it.subject }
                .distinct()
            assertTrue(
                "$beat 有 ${runners.size} 个主体同时起跑（$runners）—— " +
                    "人眼在 100ms 内只能锁定一个运动主体，多出来的那个会让画面读成『乱』",
                runners.size <= 1,
            )
        }
    }

    @Test
    fun `同一个主体的图层必须同拍`() {
        // 笔尖和"落下的那一行"是同一个动作的两个图层 —— 分开就散架。
        // 这条测试钉的是"subject 声明了就必须真的同拍"，而不是用它来绕过 R1。
        val scene = Choreography.scene(Choreography.Beat.STREAM_LINE)
        val line = scene.first(Choreography.Target.STREAM_LINE)!!
        val tip = scene.first(Choreography.Target.PEN_TIP)!!
        assertEquals("图层必须同拍", line.atMs, tip.atMs)
        assertEquals("图层的 subject 必须指向同一个主体", line.subject, tip.subject)
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 一块石头
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一个场景最多一块石头在动`() {
        beats.forEach { beat ->
            val stones = Choreography.scene(beat).steps.filter { it.mass == Choreography.Mass.STONE }
            assertTrue(
                "$beat 有 ${stones.size} 块石头在动（${stones.map { it.target }}）—— " +
                    "两块大体积元素同时动，屏幕会像整块板子在晃",
                stones.size <= 1,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 时长上限
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `场景总时长不超过上限`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            assertTrue(
                "$beat 编排用了 ${scene.totalMs}ms（上限 ${Choreography.MAX_SCENE_MS}）—— 超过它用户是在等动画",
                scene.totalMs <= Choreography.MAX_SCENE_MS,
            )
        }
    }

    @Test
    fun `高频场景必须够短`() {
        // 落笔（STREAM_LINE）在一条回复里会出现几十次。
        // 它一旦超过 360ms，用户滚动时就会觉得"内容在追着我"。
        val scene = Choreography.scene(Choreography.Beat.STREAM_LINE)
        assertTrue("落笔场景 ${scene.totalMs}ms 太长（上限 360）", scene.totalMs <= 360)
    }

    @Test
    fun `时长不得为负且只有归位允许零时长`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            scene.steps.forEach { step ->
                assertTrue("$beat/${step.target} 时长为负", step.durationMs >= 0)
                if (beat != Choreography.Beat.RESUME_ATTACH) {
                    assertNotEquals(
                        "$beat/${step.target} 用了 0ms —— 除了归位，任何一次变化都该有过程",
                        0,
                        step.durationMs,
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ④ 过冲上限
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `过冲幅度在零到三百分点之间`() {
        beats.forEach { beat ->
            Choreography.scene(beat).steps.forEach { step ->
                assertTrue(
                    "$beat/${step.target} 过冲 ${step.settle} —— 超过 ${Choreography.MAX_SETTLE} 会读成『弹了一下』",
                    step.settle >= 0f && step.settle <= Choreography.MAX_SETTLE,
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  降级
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `关掉动画就是空场景`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat, animationsOn = false)
            assertTrue("$beat 关掉动画后仍标为非静态", scene.static)
            assertEquals("$beat 关掉动画后时长必须为 0", 0, scene.totalMs)
            assertTrue("$beat 关掉动画后不该有任何一段", scene.isEmpty)
        }
    }

    @Test
    fun `省电模式只留淡入`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat, richMotion = false)
            // 不能变空：硬切是另一种粗糙，淡入几乎不耗合成
            assertFalse("$beat 省电模式下不该什么都不动", scene.isEmpty)
            scene.steps.forEach { step ->
                assertEquals("$beat/${step.target} 省电模式还有位移", 0f, step.riseDp, 0f)
                assertEquals("$beat/${step.target} 省电模式还有横移", 0f, step.slideDp, 0f)
                assertEquals("$beat/${step.target} 省电模式还有缩放", 1f, step.fromScale, 0f)
                assertEquals("$beat/${step.target} 省电模式还有过冲", 0f, step.settle, 0f)
            }
        }
    }

    @Test
    fun `关掉动画后时长不受速度系数影响`() {
        val scene = Choreography.scene(
            Choreography.Beat.TAKEOVER,
            animationsOn = false,
            speed = Choreography.TAKEOVER_SPEED,
        )
        assertEquals(0, scene.totalMs)
    }

    // ═══════════════════════════════════════════════════════════
    //  曲线选择（R3）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `位移不用Emphasized`() {
        // Emphasized 前 20% 走完 80% 距离 —— 给淡入极好，给位移是"唰一下到位再原地磨"
        //
        // ★ 只查 X / Y。**SCALE 不算位移**：`SEND_ICON` 是 Feather + Scale，
        //   它要的就是"快起步地长出来"（0.90 → 1.0），用 Emphasized 是对的。
        //   这条测试第一版把"带 fromScale 的段"也当位移，于是误报 SEND_ICON。
        beats.forEach { beat ->
            Choreography.scene(beat).steps.forEach { step ->
                if (step.axis == Choreography.Axis.X || step.axis == Choreography.Axis.Y) {
                    assertNotEquals(
                        "$beat/${step.target} 用 ${step.axis} 位移却选了 Emphasized",
                        Choreography.Curve.EMPHASIZED,
                        Choreography.curveFor(step.mass, step.axis),
                    )
                }
            }
        }
    }

    @Test
    fun `曲线只认轴_淡入用Emphasized_位移一律Standard_长度匀速`() {
        // ①淡入快起步
        assertEquals(
            Choreography.Curve.EMPHASIZED,
            Choreography.curveFor(Choreography.Mass.FEATHER, Choreography.Axis.ALPHA),
        )
        // ②位移不分轻重，一律 STANDARD。
        //   曾经这里是 `PAPER → SETTLE`，结果 8dp 的落下在第一帧就走掉 4dp ——
        //   "纸比羽毛重所以曲线更沉"听起来很对，实际是**唰一下到位再原地磨**。
        listOf(Choreography.Mass.FEATHER, Choreography.Mass.PAPER, Choreography.Mass.STONE).forEach {
            assertEquals(
                "质重 $it 的 Y 位移",
                Choreography.Curve.STANDARD,
                Choreography.curveFor(it, Choreography.Axis.Y),
            )
            assertEquals(
                "质重 $it 的 X 位移",
                Choreography.Curve.STANDARD,
                Choreography.curveFor(it, Choreography.Axis.X),
            )
        }
        // ③缩放是"形变"，允许按质量分：羽毛（出现）快起步，纸/石头（压实）落定收尾
        assertEquals(
            Choreography.Curve.EMPHASIZED,
            Choreography.curveFor(Choreography.Mass.FEATHER, Choreography.Axis.SCALE),
        )
        assertEquals(
            Choreography.Curve.SETTLE,
            Choreography.curveFor(Choreography.Mass.PAPER, Choreography.Axis.SCALE),
        )
        // ④长度（进度/竖脊）匀速 —— 它表达"正在动"，不是"快到了"
        assertEquals(
            Choreography.Curve.LINEAR,
            Choreography.curveFor(Choreography.Mass.STONE, Choreography.Axis.EXTENT),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑤ 归位不重播
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `归位只对位不重播`() {
        val scene = Choreography.scene(Choreography.Beat.RESUME_ATTACH)
        assertTrue(
            "归位必须是纯对位（全部 0ms）—— 重播等于告诉用户『它刚刚才开始』，而事实是它一直在跑",
            scene.snapOnly,
        )
        assertEquals(1, scene.steps.size)
        assertEquals(Choreography.Target.RESUME_MARK, scene.steps.first().target)
        assertEquals(
            "归位被打断时该停在当前值",
            Choreography.OnInterrupt.FREEZE,
            scene.interrupt,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  自洽：同一元素的分段不许重叠
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同一元素的分段首尾相接不重叠`() {
        beats.forEach { beat ->
            Choreography.scene(beat).steps.groupBy { it.target }.forEach { (target, steps) ->
                val sorted = steps.sortedBy { it.atMs }
                sorted.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "$beat/$target 两段重叠：前一段到 ${a.endMs}ms，后一段从 ${b.atMs}ms 开始",
                        a.endMs <= b.atMs,
                    )
                }
            }
        }
    }

    @Test
    fun `失败卡的两段是撞墙再归位`() {
        // 这是全篇唯一"同一元素两段"的场景，必须真的一前一后、方向相反
        val steps = Choreography.scene(Choreography.Beat.CARD_FAIL)
            .stepsOf(Choreography.Target.CARD)
        assertEquals("失败卡要有『顿』和『回』两段", 2, steps.size)

        val bump = steps[0]
        val back = steps[1]
        assertTrue("第一段必须短促（撞击是瞬时的）", bump.durationMs <= 60)
        assertTrue("第一段向下砸（delta 为正）", bump.riseDeltaDp > 0f)
        assertTrue("第二段向上归位（delta 为负）", back.riseDeltaDp < 0f)
        assertEquals("两段必须首尾相接", bump.endMs, back.atMs)
        assertEquals("归位段的起点必须接住撞击段的终点", bump.toRiseDp, back.riseDp, 0f)
        assertEquals("归位后必须回到原位", 0f, back.toRiseDp, 0f)
        assertEquals("第二段不能重新淡入（重播淡入是这套设计最忌讳的事）", 1f, back.fromAlpha, 0f)
    }

    @Test
    fun `相邻两段的终点就是下一段的起点`() {
        // 这条不变量比"两段首尾相接"更强：它管的是**姿态**的连续，
        // 而不只是**时间**的连续。时间接上了、姿态没接上，就是一个跳帧。
        beats.forEach { beat ->
            Choreography.scene(beat).steps.groupBy { it.target }.forEach { (target, steps) ->
                val ordered = steps.sortedBy { it.atMs }
                ordered.zipWithNext().forEach { (a, b) ->
                    val endOfA = Playback.endOf(a)
                    val startOfB = Playback.startOf(b)
                    assertEquals("$beat/$target 终点 alpha 没接上", endOfA.alpha, startOfB.alpha, 0.001f)
                    assertEquals("$beat/$target 终点 dy 没接上", endOfA.dyDp, startOfB.dyDp, 0.001f)
                    assertEquals("$beat/$target 终点 dx 没接上", endOfA.dxDp, startOfB.dxDp, 0.001f)
                    assertEquals("$beat/$target 终点 scale 没接上", endOfA.scale, startOfB.scale, 0.001f)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  因果（顺序即语义）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `发送时气泡先于输入框塌陷`() {
        // 反过来会有一帧"文字凭空消失"—— 那是用户唯一可能怀疑"我是不是没发出去"的时刻
        val scene = Choreography.scene(Choreography.Beat.SEND)
        val bubble = scene.first(Choreography.Target.USER_BUBBLE)
        val composer = scene.first(Choreography.Target.COMPOSER)
        assertNotNull("发送场景缺少气泡", bubble)
        assertNotNull("发送场景缺少输入框复位", composer)
        assertTrue(
            "气泡(${bubble!!.atMs}ms) 必须先于输入框塌陷(${composer!!.atMs}ms)",
            bubble.atMs < composer.atMs,
        )
    }

    @Test
    fun `发送时气泡从输入框那儿长出来`() {
        // 0 缩放 = 弹窗（从无到有）；0.72 + 8dp = 从那个地方长出来
        val bubble = Choreography.scene(Choreography.Beat.SEND)
            .first(Choreography.Target.USER_BUBBLE)!!
        assertTrue("气泡起点缩放 ${bubble.fromScale} 太小，会读成弹窗", bubble.fromScale >= 0.6f)
        assertTrue("气泡起点缩放不该是 1（那就是硬切）", bubble.fromScale < 1f)
    }

    @Test
    fun `卡片登场竖脊先于本体`() {
        // 笔先落下，再写字 —— 本体（纸）必须在竖脊（羽毛）之后
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        val spine = scene.first(Choreography.Target.CARD_SPINE)!!
        val card = scene.first(Choreography.Target.CARD)!!
        assertTrue("竖脊 ${spine.atMs}ms 必须先于卡片本体 ${card.atMs}ms", spine.atMs < card.atMs)
        assertEquals("竖脊起跑者必须是它", 0, spine.atMs)
    }

    @Test
    fun `卡片登场时徽章最后出现`() {
        // 用户要先读"做什么"，再读"什么状态"
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        val body = scene.first(Choreography.Target.CARD_BODY)!!
        val badge = scene.first(Choreography.Target.CARD_BADGE)!!
        assertTrue("徽章 ${badge.atMs}ms 该晚于正文 ${body.atMs}ms", badge.atMs > body.atMs)
    }

    @Test
    fun `落定时先收线再盖章最后压实`() {
        val scene = Choreography.scene(Choreography.Beat.CARD_LAND)
        val line = scene.first(Choreography.Target.CARD_PROGRESS)!!
        val badge = scene.first(Choreography.Target.CARD_BADGE)!!
        val card = scene.first(Choreography.Target.CARD)!!
        assertTrue("进度线必须先收", line.atMs < badge.atMs)
        assertTrue("徽章必须在卡片压实之前", badge.atMs < card.atMs)
        assertEquals("收线的起跑者是它", 0, line.atMs)
        assertTrue("完成时徽章要有轻微过冲（贴上去）", badge.settle > 0f)
    }

    @Test
    fun `预演转执行是收窄不是放大`() {
        // 用户刚点了「可以」，此刻在收缩注意力（像握紧）；放大读成"炫耀"
        val card = Choreography.scene(Choreography.Beat.CARD_COMMIT)
            .first(Choreography.Target.CARD)!!
        assertTrue("起点缩放 ${card.fromScale} 必须 ≥1（从略大收窄到 1）", card.fromScale >= 1f)
    }

    @Test
    fun `执行时进度线不显示百分比`() {
        // 进度线是"这只手正在动"，不是进度条 —— 它是匀速、不回收的。
        // 这条测试钉的是"它必须是 LINEAR + EXTENT"，防止有人给它加 easing 或改成 0..1 的 tween。
        val line = Choreography.scene(Choreography.Beat.CARD_COMMIT)
            .first(Choreography.Target.CARD_PROGRESS)!!
        assertEquals(Choreography.Axis.EXTENT, line.axis)
        assertEquals(
            Choreography.Curve.LINEAR,
            Choreography.curveFor(line.mass, line.axis),
        )
    }

    @Test
    fun `失败卡必须有出口`() {
        // 曾经这里注释写了"出口按钮一个一个出现"，代码却没安排 —— 注释承诺了，代码没做。
        val scene = Choreography.scene(Choreography.Beat.CARD_FAIL)
        assertNotNull("失败卡没给出口按钮安排出场", scene.first(Choreography.Target.CARD_EXITS))
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑥ 打断要快（通话）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `用户打断时波形必须是塌陷不是淡出`() {
        val scene = Choreography.scene(Choreography.Beat.CALL_SPEAK_TO_LISTEN)
        val bars = scene.first(Choreography.Target.CALL_BARS)!!

        assertTrue("打断用了 ${bars.durationMs}ms —— 超过 80ms 就读成『它还在说，只是变小声』", bars.durationMs <= 80)
        assertEquals("打断的起跑者必须是波形本身", 0, bars.atMs)

        // 必须快于通话里任何一段进场（对比"想 → 说"时波形的站起）
        val standUp = Choreography.scene(Choreography.Beat.CALL_THINK_TO_SPEAK)
            .first(Choreography.Target.CALL_BARS)!!
        assertTrue(
            "掐断(${bars.durationMs}ms) 必须快于站起(${standUp.durationMs}ms)",
            bars.durationMs < standUp.durationMs,
        )
    }

    @Test
    fun `打断后要有余韵`() {
        // 掐断是瞬时的，但"刚才那一下"要留涟漪；然后是新的呼吸环回来
        val scene = Choreography.scene(Choreography.Beat.CALL_SPEAK_TO_LISTEN)
        assertNotNull("打断后没有涟漪", scene.first(Choreography.Target.CALL_RIPPLE))
        assertNotNull("打断后呼吸环没回来", scene.first(Choreography.Target.CALL_RING))
    }

    @Test
    fun `通话状态切换必须形变而不是硬切`() {
        // 现状是 when(motionFor(phase)) 整组画布瞬间换掉 —— 打电话时会看到一次"闪"，
        // 像是在听的时候信号断了。正确的事：同一个东西的两种状态。
        val listenToThink = Choreography.scene(Choreography.Beat.CALL_LISTEN_TO_THINK)
        val ring = listenToThink.first(Choreography.Target.CALL_RING)!!
        assertEquals("环从满大开始（它本来就在那儿）", 1f, ring.fromScale, 0f)
        assertTrue(
            "环收拢到 ${ring.toScale} —— 不能收到 0，必须留一个核给下一段长出来",
            ring.toScale > 0.05f,
        )
        assertTrue("环必须收拢（toScale < 1）", ring.toScale < 1f)

        val thinkToSpeak = Choreography.scene(Choreography.Beat.CALL_THINK_TO_SPEAK)
        val dots = thinkToSpeak.first(Choreography.Target.CALL_DOTS)!!
        assertEquals("粒子从满大开始塌", 1f, dots.fromScale, 0f)
        assertTrue("粒子必须塌进中心（toScale < 1）", dots.toScale < 1f)
        assertTrue("粒子塌缩终点要明显小于 1", dots.toScale <= 0.5f)
    }

    @Test
    fun `形变是接力不是同时`() {
        // 先收，再长 —— 两段首尾相接，不许交叉
        val scene = Choreography.scene(Choreography.Beat.CALL_LISTEN_TO_THINK)
        val ring = scene.first(Choreography.Target.CALL_RING)!!
        val dots = scene.first(Choreography.Target.CALL_DOTS)!!
        assertTrue("粒子必须等环收完再开始", dots.atMs >= ring.endMs)
    }

    // ═══════════════════════════════════════════════════════════
    //  接管：世界变慢但不停止
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `接管时世界减速但不停止`() {
        val normal = Choreography.scene(Choreography.Beat.TAKEOVER).totalMs
        val slowed = Choreography.scene(
            Choreography.Beat.TAKEOVER,
            speed = Choreography.TAKEOVER_SPEED,
        ).totalMs

        assertTrue("减速后必须更久（${slowed}ms vs ${normal}ms）", slowed > normal)
        assertTrue("减速不等于停止：编排不能变空", slowed > 0)
        assertTrue(
            "减速系数 ${Choreography.TAKEOVER_SPEED} 落在合理区间(0.05..0.9)",
            Choreography.TAKEOVER_SPEED in 0.05f..0.9f,
        )
    }

    @Test
    fun `接管条是石头_按钮更晚`() {
        // "已经停下"这件事必须显得重；先看到"停了"，才看到"可以接着来"
        val scene = Choreography.scene(Choreography.Beat.TAKEOVER)
        val bar = scene.first(Choreography.Target.TAKEOVER_BAR)!!
        val actions = scene.first(Choreography.Target.TAKEOVER_ACTIONS)!!
        assertEquals(Choreography.Mass.STONE, bar.mass)
        assertTrue("接管条必须从下方上来", bar.riseDp > 0f)
        assertTrue("按钮该晚于接管条", actions.atMs > bar.atMs)
    }

    @Test
    fun `速度系数等比缩放编排`() {
        val normal = Choreography.scene(Choreography.Beat.STREAM_LINE).totalMs
        val fast = Choreography.scene(Choreography.Beat.STREAM_LINE, speed = 2f).totalMs
        assertEquals(normal / 2f, fast.toFloat(), 2f)
    }

    @Test
    fun `速度系数被夹在合法区间`() {
        val tooFast = Choreography.scene(Choreography.Beat.STREAM_LINE, speed = 100f)
        val tooSlow = Choreography.scene(Choreography.Beat.STREAM_LINE, speed = 0.001f)
        assertTrue("速度上限应被夹住", tooFast.speed <= 4f)
        assertTrue("速度下限应被夹住", tooSlow.speed >= 0.25f)
    }

    // ═══════════════════════════════════════════════════════════
    //  错峰
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `错峰有封顶`() {
        assertEquals(
            "第 0 项不加延迟",
            100,
            Choreography.stagger(100, 0, Choreography.DRAWER_STAGGER_STEP_MS, Choreography.DRAWER_STAGGER_MAX_MS),
        )
        assertEquals(
            "第 2 项 + 60ms",
            160,
            Choreography.stagger(100, 2, Choreography.DRAWER_STAGGER_STEP_MS, Choreography.DRAWER_STAGGER_MAX_MS),
        )
        assertEquals(
            "第 20 项封顶 +180ms（不能让第 30 项等 900ms）",
            280,
            Choreography.stagger(100, 20, Choreography.DRAWER_STAGGER_STEP_MS, Choreography.DRAWER_STAGGER_MAX_MS),
        )
    }

    @Test
    fun `抽屉比列表更紧`() {
        // 抽屉是"扫一眼"的场景，错峰必须比消息列表更短
        assertTrue(
            "抽屉错峰 ${Choreography.DRAWER_STAGGER_STEP_MS}ms 应小于列表的 ${MotionRules.STAGGER_STEP_MS}ms",
            Choreography.DRAWER_STAGGER_STEP_MS <= MotionRules.STAGGER_STEP_MS,
        )
        assertTrue(
            "抽屉错峰封顶 ${Choreography.DRAWER_STAGGER_MAX_MS}ms 应小于列表的 ${MotionRules.STAGGER_MAX_MS}ms",
            Choreography.DRAWER_STAGGER_MAX_MS <= MotionRules.STAGGER_MAX_MS,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  质量的自洽：石头不比羽毛快
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `石头不会比羽毛动得快`() {
        // 质量 = "这个东西有多重"的编码。石头（底栏/抽屉/接管条/通话主视觉）
        // 若比羽毛（文字/徽章）还快，那"重"这件事就没有被表达出来。
        val durations = mapOf(
            Choreography.Mass.FEATHER to mutableListOf<Int>(),
            Choreography.Mass.PAPER to mutableListOf<Int>(),
            Choreography.Mass.STONE to mutableListOf<Int>(),
        )
        beats.forEach { beat ->
            Choreography.scene(beat).steps.forEach { step ->
                durations.getValue(step.mass).add(step.durationMs)
            }
        }
        val featherAvg = durations.getValue(Choreography.Mass.FEATHER).average()
        val stoneAvg = durations.getValue(Choreography.Mass.STONE).average()
        assertTrue(
            "羽毛平均 ${featherAvg}ms，石头平均 ${stoneAvg}ms —— 石头必须更慢",
            stoneAvg > featherAvg,
        )
    }

    @Test
    fun `stepsOf和first能取到编排`() {
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        assertEquals(1, scene.stepsOf(Choreography.Target.CARD).size)
        assertNotNull(scene.first(Choreography.Target.CARD_SPINE))
        assertTrue("取不存在的元素应为 null", scene.first(Choreography.Target.CALL_BARS) == null)
        assertTrue("取不存在的元素应为空表", scene.stepsOf(Choreography.Target.CALL_BARS).isEmpty())
    }
}
