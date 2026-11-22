package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **求值器**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是"数学对了没有"，不是"好不好看"
 * ═══════════════════════════════════════════════════════════════
 *
 * 手感没法在单测里断言，但手感**依赖的数学**可以：
 *
 *  · 曲线反解错了 ⇒ 每个元素都"冲一下"（标准曲线的中段偏快 8%）
 *  · 端点不精确 ⇒ 段与段之间出现 1 帧的跳（肉眼就是"抖了一下"）
 *  · 过冲算成绝对值 ⇒ 1dp 的小东西也在弹（廉价感）
 *  · extent 当 alpha 用 ⇒ 进度线变成"渐显的线"而不是"生长的线"
 *
 * 这些全都测得了。测不了的那部分（比如"1.5dp 的撞击够不够"）
 * 只能真机看 —— 但至少别让它被数学错误遮住。
 */
class PlaybackTest {

    private val beats: List<Choreography.Beat> = Choreography.Beat.values().toList()

    // ═══════════════════════════════════════════════════════════
    //  曲线
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `曲线两端精确`() {
        Choreography.Curve.values().forEach { c ->
            val curve = Playback.curveOf(c)
            assertEquals("$c 在 x=0 处必须为 0", 0f, Playback.solve(curve, 0f), 0f)
            assertEquals("$c 在 x=1 处必须为 1", 1f, Playback.solve(curve, 1f), 0f)
        }
    }

    @Test
    fun `线性曲线就是恒等`() {
        val linear = Playback.curveOf(Choreography.Curve.LINEAR)
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f).forEach { x ->
            assertEquals("线性在 $x 处该等于自己", x, Playback.solve(linear, x), 1e-3f)
        }
    }

    @Test
    fun `所有曲线单调不减`() {
        // 缓动曲线一旦非单调，元素就会"先退再进"—— 那是明显的机械感
        Choreography.Curve.values().forEach { c ->
            val curve = Playback.curveOf(c)
            var prev = -1f
            var x = 0f
            while (x <= 1f) {
                val y = Playback.solve(curve, x)
                assertTrue("$c 在 x=$x 处回退了（$prev → $y）", y >= prev - 1e-5f)
                prev = y
                x += 0.01f
            }
        }
    }

    @Test
    fun `标准曲线在中段偏快`() {
        // 这条钉的是"必须反解 x→t"。若直接把 x 当 t 用（常见错误），
        // 中点会算出 0.5；正确值是 0.79 左右 —— 差 30%，
        // 在 24dp 的位移上就是 7dp 的可见偏差。
        val v = Playback.solve(MaterialMotion.STANDARD, 0.5f)
        assertTrue("标准曲线中点应明显快于线性（实得 $v）", v > 0.65f)
        assertTrue("但不该直接到顶（实得 $v）", v < 0.95f)
    }

    @Test
    fun `Emphasized比Standard更快起步`() {
        val e = Playback.solve(Playback.curveOf(Choreography.Curve.EMPHASIZED), 0.25f)
        val s = Playback.solve(Playback.curveOf(Choreography.Curve.STANDARD), 0.25f)
        assertTrue("Emphasized 在 1/4 处应更快（$e vs $s）", e > s)
    }

    // ═══════════════════════════════════════════════════════════
    //  端点（段与段的接缝）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `未开始时停在起点`() {
        val line = Choreography.scene(Choreography.Beat.STREAM_LINE)
            .first(Choreography.Target.STREAM_LINE)!!
        // atMs = 0，所以用一个 atMs > 0 的段来测"还没开始"
        val gap = Choreography.scene(Choreography.Beat.STREAM_LINE)
            .first(Choreography.Target.PARA_GAP)!!
        val before = Playback.poseOf(gap, 0)
        assertEquals("还没开始：位置应在起点", Playback.startOf(gap).dyDp, before.dyDp, 1e-4f)
        assertEquals("还没开始：长度为 0", 0f, before.extent, 0f)

        val starting = Playback.poseOf(line, 0)
        assertEquals("刚好在 atMs：也是起点", 6f, starting.dyDp, 1e-4f)
    }

    @Test
    fun `终点精确落在to上`() {
        beats.forEach { beat ->
            Choreography.scene(beat).steps.forEach { step ->
                val atEnd = Playback.poseOf(step, step.endMs)
                val expected = Playback.endOf(step)
                // 端点不允许有过冲残留：sin(pi*1) = 0，所以这里必须严格等于 to
                assertEquals("$beat/${step.target} 终点 alpha 不准", expected.alpha, atEnd.alpha, 1e-4f)
                assertEquals("$beat/${step.target} 终点 dy 不准", expected.dyDp, atEnd.dyDp, 1e-4f)
                assertEquals("$beat/${step.target} 终点 dx 不准", expected.dxDp, atEnd.dxDp, 1e-4f)
                assertEquals("$beat/${step.target} 终点 scale 不准", expected.scale, atEnd.scale, 1e-4f)
                assertEquals("$beat/${step.target} 终点 extent 不准", expected.extent, atEnd.extent, 1e-4f)
            }
        }
    }

    @Test
    fun `过了终点不会继续变化`() {
        val line = Choreography.scene(Choreography.Beat.STREAM_LINE)
            .first(Choreography.Target.STREAM_LINE)!!
        val atEnd = Playback.poseOf(line, line.endMs)
        val wayAfter = Playback.poseOf(line, line.endMs + 5_000)
        assertEquals(atEnd.dyDp, wayAfter.dyDp, 0f)
        assertEquals(atEnd.alpha, wayAfter.alpha, 0f)
    }

    @Test
    fun `零时长的段直接到终点`() {
        val mark = Choreography.scene(Choreography.Beat.RESUME_ATTACH)
            .first(Choreography.Target.RESUME_MARK)!!
        assertEquals(0, mark.durationMs)
        // 归位：不重播。任何时刻都是终态。
        assertEquals(1f, Playback.poseOf(mark, 0).alpha, 0f)
        assertEquals(1f, Playback.poseOf(mark, 10).alpha, 0f)
    }

    // ═══════════════════════════════════════════════════════════
    //  具体场景的骨架（数字对了，手感才可能有）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `落笔的线从下方浮起`() {
        val line = Choreography.scene(Choreography.Beat.STREAM_LINE)
            .first(Choreography.Target.STREAM_LINE)!!
        assertEquals("起点在下方 6dp", 6f, Playback.poseOf(line, 0).dyDp, 1e-4f)
        assertEquals("终点归零", 0f, Playback.poseOf(line, 180).dyDp, 1e-4f)
        // 中途必须真的在中间（不是跳过去的）
        val mid = Playback.poseOf(line, 90).dyDp
        assertTrue("中途应在 0..6 之间（实得 $mid）", mid > 0f && mid < 6f)
    }

    @Test
    fun `撞墙段是向下且极短`() {
        val steps = Choreography.scene(Choreography.Beat.CARD_FAIL)
            .stepsOf(Choreography.Target.CARD)
        val bump = steps[0]
        val back = steps[1]

        assertEquals("撞墙起点在原位", 0f, Playback.poseOf(bump, 0).dyDp, 1e-4f)
        val mid = Playback.poseOf(bump, 20).dyDp
        assertTrue("撞墙中途必须向下（实得 $mid）", mid > 0f)
        assertEquals("撞墙终点在下方 1.5dp", 1.5f, Playback.poseOf(bump, 40).dyDp, 1e-4f)

        assertEquals("归位起点接住撞击终点", 1.5f, Playback.poseOf(back, 40).dyDp, 1e-4f)
        assertEquals("归位终点回到原位", 0f, Playback.poseOf(back, 280).dyDp, 1e-4f)
        assertTrue("归位中的某一点应该比起点更靠近 0", Playback.poseOf(back, 200).dyDp < 1.5f)
    }

    @Test
    fun `过冲是相对量而不是绝对值`() {
        // CARD_LAND 的徽章：dx -8 → 0，settle = 3%。
        // 过冲量必须 ≈ 8 * 0.03 = 0.24dp，而不是恒定的 3dp。
        val badge = Choreography.scene(Choreography.Beat.CARD_LAND)
            .first(Choreography.Target.CARD_BADGE)!!
        val samples = (0..160 step 2).map { Playback.poseOf(badge, 40 + it).dxDp }

        val peak = samples.max()
        assertTrue("必须真的越过终点（实得峰值 $peak）", peak > 0.01f)
        assertTrue(
            "过冲不该超过位移的 ${Choreography.MAX_SETTLE}（实得 $peak，上限约 0.24）",
            peak <= 8f * Choreography.MAX_SETTLE + 0.01f,
        )
    }

    @Test
    fun `进度线是生长不是渐显`() {
        val progress = Choreography.scene(Choreography.Beat.CARD_COMMIT)
            .first(Choreography.Target.CARD_PROGRESS)!!
        assertEquals("起跑时长度为 0", 0f, Playback.poseOf(progress, 120).extent, 1e-4f)
        assertEquals("到头时长度为满", 1f, Playback.poseOf(progress, 600).extent, 1e-4f)
        val mid = Playback.poseOf(progress, 360).extent
        assertTrue("中途应在 0.2..0.8（实得 $mid）", mid > 0.2f && mid < 0.8f)
        // 它是 EXTENT + LINEAR：中点就该是 0.5 左右
        assertTrue("线性生长在中点应接近 0.5（实得 $mid）", kotlin.math.abs(mid - 0.5f) < 0.1f)
    }

    @Test
    fun `掐断是高度归零`() {
        val bars = Choreography.scene(Choreography.Beat.CALL_SPEAK_TO_LISTEN)
            .first(Choreography.Target.CALL_BARS)!!
        assertEquals("起跑时是满高", 1f, Playback.poseOf(bars, 0).extent, 0f)
        assertEquals("60ms 后归零", 0f, Playback.poseOf(bars, 60).extent, 1e-4f)
        val mid = Playback.poseOf(bars, 30).extent
        assertTrue("中途已明显矮下去（实得 $mid）", mid < 0.6f)
    }

    @Test
    fun `收拢留下核`() {
        val ring = Choreography.scene(Choreography.Beat.CALL_LISTEN_TO_THINK)
            .first(Choreography.Target.CALL_RING)!!
        assertEquals("起点满大", 1f, Playback.poseOf(ring, 0).scale, 1e-4f)
        assertEquals("终点是核", 0.15f, Playback.poseOf(ring, 240).scale, 1e-4f)
        assertTrue("核不能是 0（否则下一段没有起点可接）", Playback.poseOf(ring, 240).scale > 0.05f)
    }

    // ═══════════════════════════════════════════════════════════
    //  整条时间轴的性质
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `整条时间轴没有NaN`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            (0..scene.totalMs + 100).forEach { t ->
                Playback.frameOf(scene, t).forEach { (target, p) ->
                    assertFalse("$beat/$target 在 ${t}ms 处 alpha 是 NaN", p.alpha.isNaN())
                    assertFalse("$beat/$target 在 ${t}ms 处 dy 是 NaN", p.dyDp.isNaN())
                    assertFalse("$beat/$target 在 ${t}ms 处 scale 是 NaN", p.scale.isNaN())
                    assertFalse("$beat/$target 在 ${t}ms 处 extent 是 NaN", p.extent.isNaN())
                    assertTrue("$beat/$target 在 ${t}ms 处 alpha 越界(${p.alpha})", p.alpha in -0.001f..1.001f)
                    assertTrue("$beat/$target 在 ${t}ms 处 extent 越界(${p.extent})", p.extent in -0.001f..1.001f)
                    assertTrue("$beat/$target 在 ${t}ms 处 scale 为负(${p.scale})", p.scale >= 0f)
                }
            }
        }
    }

    @Test
    fun `每个场景内alpha不会回落`() {
        // 同一场景里出现"淡出又淡入"就是一次闪。入场场景不该有这件事。
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            val targets = scene.steps.map { it.target }.distinct()
            targets.forEach { target ->
                var prev = -1f
                var t = 0
                while (t <= scene.totalMs) {
                    val a = Playback.frameOf(scene, t)[target]!!.alpha
                    assertTrue("$beat/$target 在 ${t}ms 处 alpha 回落（$prev → $a）", a >= prev - 0.001f)
                    prev = a
                    t += 8
                }
            }
        }
    }

    @Test
    fun `位移不会在一帧内瞬移`() {
        // ★ 这条**不**是"限制速度"。快是允许的：抽屉一屏 320dp/300ms，
        //   60fps 下每帧就移动 17dp —— 那是设计，不是 bug。
        //   它抓的是"瞬移"：一段总共只该走 8dp，却在一帧里走掉 6dp。
        //   上限因此取"总位移的一半"，另给 1dp 余量给浮点与圆角对齐。
        val frameMs = 16f
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            scene.steps.forEach { step ->
                val total = kotlin.math.abs(step.riseDeltaDp) + kotlin.math.abs(step.slideDp - step.toSlideDp)
                if (total == 0f || step.durationMs <= 0) return@forEach
                val allow = total * 0.5f + 1f

                var t = step.atMs.toFloat()
                while (t + frameMs <= step.endMs) {
                    val a = Playback.poseOf(step, t.toInt())
                    val b = Playback.poseOf(step, (t + frameMs).toInt())
                    val d = kotlin.math.abs(b.dyDp - a.dyDp) + kotlin.math.abs(b.dxDp - a.dxDp)
                    assertTrue(
                        "$beat/${step.target} 在 ${t.toInt()}ms → ${(t + frameMs).toInt()}ms 间瞬移了 " +
                            "${d}dp（该段总位移 $total，上限 $allow）",
                        d <= allow,
                    )
                    t += frameMs
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  帧表与采样
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `帧表按target给出姿态`() {
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        val frame = Playback.frameOf(scene, 0)
        assertEquals("CARD_ENTER 有 4 个元素（实得 ${frame.keys}）", 4, frame.size)
        assertTrue("CARD_ENTER 应含竖脊", frame.containsKey(Choreography.Target.CARD_SPINE))
        assertTrue("CARD_ENTER 应含卡片本体", frame.containsKey(Choreography.Target.CARD))
        assertTrue("CARD_ENTER 应含内容", frame.containsKey(Choreography.Target.CARD_BODY))
        assertTrue("CARD_ENTER 应含徽章", frame.containsKey(Choreography.Target.CARD_BADGE))
        // t=0 时还没出现的元素必须是不可见的（alpha 0）
        assertEquals("t=0 时卡片本体还不在场", 0f, frame.getValue(Choreography.Target.CARD).alpha, 1e-4f)
        assertEquals("t=0 时竖脊还不透明", 0f, frame.getValue(Choreography.Target.CARD_SPINE).alpha, 1e-4f)
        // 竖脊是 EXTENT（从 0 长到满）
        assertEquals("竖脊从 0 开始生长", 0f, frame.getValue(Choreography.Target.CARD_SPINE).extent, 1e-4f)
    }

    @Test
    fun `每一段的起止时刻都精确落在端点上`() {
        // 采样边界最容易漏一帧：atMs 时刻应当**已经**是起点（而不是还没动），
        // endMs 时刻应当**已经**是终点（而不是还差最后一步）。
        // 漏了的直接后果是每次进场开头/结尾各闪一下 —— 单看不明显，连起来就是"抖"。
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            scene.steps.forEach { step ->
                val atStart = Playback.poseOf(step, step.atMs)
                val atEnd = Playback.poseOf(step, step.endMs)
                val start = Playback.startOf(step)
                val end = Playback.endOf(step)

                assertEquals("$beat/${step.target} 起点 alpha", start.alpha, atStart.alpha, 1e-3f)
                assertEquals("$beat/${step.target} 起点 dy", start.dyDp, atStart.dyDp, 1e-3f)
                assertEquals("$beat/${step.target} 起点 scale", start.scale, atStart.scale, 1e-3f)
                assertEquals("$beat/${step.target} 终点 alpha", end.alpha, atEnd.alpha, 1e-3f)
                assertEquals("$beat/${step.target} 终点 dy", end.dyDp, atEnd.dyDp, 1e-3f)
                assertEquals("$beat/${step.target} 终点 scale", end.scale, atEnd.scale, 1e-3f)
                assertEquals("$beat/${step.target} 终点 extent", end.extent, atEnd.extent, 1e-3f)
            }
        }
    }

    @Test
    fun `帧表末帧全部到位`() {
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            val frame = Playback.frameOf(scene, scene.totalMs)
            frame.forEach { (target, pose) ->
                assertEquals("$beat/$target 末帧该可见", 1f, pose.alpha, 1e-4f)
                assertEquals("$beat/$target 末帧该在原位", 0f, pose.dyDp, 1e-3f)
                assertEquals("$beat/$target 末帧该在原位", 0f, pose.dxDp, 1e-3f)
            }
        }
    }

    @Test
    fun `采样含首尾`() {
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        val samples = Playback.sample(scene, stepMs = 16)
        assertEquals("首帧必须在 0ms", 0, samples.first().first)
        assertEquals("末帧必须正好是 totalMs", scene.totalMs, samples.last().first)
        assertTrue("采样点应覆盖整场", samples.size >= scene.totalMs / 16)
    }

    @Test
    fun `采样间隔必须为正`() {
        val scene = Choreography.scene(Choreography.Beat.SEND)
        var threw = false
        try {
            Playback.sample(scene, stepMs = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("0 间隔应该被拒绝", threw)
    }

    @Test
    fun `关掉动画时帧表为空`() {
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER, animationsOn = false)
        assertTrue("静态场景不该有任何帧表内容", Playback.frameOf(scene, 0).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    //  演示数据 = 真机数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `导出的JSON结构正确`() {
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        val json = Playback.toJson(scene, stepMs = 32)
        assertTrue("应是对象", json.startsWith("{") && json.endsWith("}"))
        assertTrue("必须带 beat 名", json.contains("\"beat\":\"CARD_ENTER\""))
        assertTrue("必须带总时长", json.contains("\"totalMs\":${scene.totalMs}"))
        assertTrue("必须列出元素", json.contains("CARD_SPINE"))
        assertTrue("必须有帧数据", json.contains("\"frames\":[["))
        assertFalse("不该出现科学计数法（演示端按普通数字解析）", json.contains("E-"))
        // 帧头形如 `[0,[…` —— `[数字,[` 只会出现在帧的起点
        val frames = Regex("\\[\\d+,\\[").findAll(json).count()
        assertTrue("帧数应大于 5（实得 $frames）", frames > 5)
    }

    @Test
    fun `复杂场景的JSON不含NaN`() {
        beats.forEach { beat ->
            val json = Playback.toJson(Choreography.scene(beat), stepMs = 64)
            assertFalse("$beat 的 JSON 出现 NaN", json.contains("NaN"))
            assertFalse("$beat 的 JSON 出现 Infinity", json.contains("Infinity"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  单元素求值 ≡ 整帧求值（真机走前者，演示走后者）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `单元素求值与整帧求值完全一致`() {
        // 渲染层为了零分配，对每个元素单独调 poseOf(scene, target, t)；
        // 而演示/调试/导出走 frameOf(scene, t) 再取 Map。
        // 两条路必须给出**逐位相同**的数字，否则"演示里好好的，真机上错位"。
        beats.forEach { beat ->
            val scene = Choreography.scene(beat)
            var t = 0
            while (t <= scene.totalMs) {
                val frame = Playback.frameOf(scene, t)
                frame.forEach { (target, expected) ->
                    val actual = Playback.poseOf(scene, target, t)
                    assertEquals("$beat/$target @${t}ms alpha", expected.alpha, actual.alpha, 1e-6f)
                    assertEquals("$beat/$target @${t}ms dx", expected.dxDp, actual.dxDp, 1e-6f)
                    assertEquals("$beat/$target @${t}ms dy", expected.dyDp, actual.dyDp, 1e-6f)
                    assertEquals("$beat/$target @${t}ms scale", expected.scale, actual.scale, 1e-6f)
                    assertEquals("$beat/$target @${t}ms extent", expected.extent, actual.extent, 1e-6f)
                }
                t += 8
            }
        }
    }

    @Test
    fun `不在场景里的元素返回静止姿态`() {
        // 渲染层靠这个"什么都不做"：场景不管的元素不该被套上 alpha 0（那会凭空隐身）。
        val scene = Choreography.scene(Choreography.Beat.CARD_ENTER)
        val pose = Playback.poseOf(scene, Choreography.Target.CALL_BARS, 120)
        assertTrue("不在场景里的元素必须是静止姿态", pose.isRest)
    }
}
