package com.pocketagent.agentui

import java.io.File
import java.util.Locale

/**
 * 把编排表导成一个**自包含的 HTML 分镜台**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么值得多写一个生成器
 * ═══════════════════════════════════════════════════════════════
 *
 * 动效方案最容易变成"文档里写得很美，代码里根本没实现"。
 * 这个生成器把 [Choreography]（编排）与 [Playback]（求值）**当真源**，
 * 按 8ms 采样每一帧的姿态，塞进一个能拖时间轴、能关动画、能看数字的页面。
 *
 * 于是：
 *  · 页面上看到的每一次抖，都是数学给的 —— 不是 CSS 手写出来的另一种东西；
 *  · 改一个 `atMs`，重跑一次生成，页面上的节奏就变了（没有第二份真相）；
 *  · 不用真机、不用装 APK 就能审"三拍之间是不是差 140ms"。
 *
 * 注意页面的**外壳**（分镜台本身）是暗色的工具界面，而**产物**（手机画面）
 * 用的是产品自己的暖米色 + 陶土强调 —— 一个是仪器，一个是被观察的东西。
 *
 * @param stepMs 采样步长。8ms ≈ 半帧，够插值；调小只会让文件变大。
 */
object ChoreoDemo {

    const val STEP_MS: Int = 8

    private const val PLACEHOLDER = "/*__DATA__*/null"
    private const val TEMPLATE = "/choreo-demo/template.html"

    /** 只导出数据（给别的可视化/CI 用）。 */
    fun json(stepMs: Int = STEP_MS): String = buildString(96 * 1024) {
        append("{\"stepMs\":").append(stepMs).append(",\"beats\":[")
        Choreography.Beat.values().forEachIndexed { index, beat ->
            if (index > 0) append(',')
            appendBeat(this, beat, stepMs)
        }
        append("]}")
    }

    private fun appendBeat(out: StringBuilder, beat: Choreography.Beat, stepMs: Int) {
        val normal = Choreography.scene(beat)
        // 省电模式由**同一张表**降级得到（位移/缩放/过冲归零），
        // 不是 demo 自己再实现一遍 —— 否则演示会与真机漂移。
        val eco = Choreography.scene(beat, animationsOn = true, richMotion = false)

        out.append("{\"name\":\"").append(beat.name).append('"')
        out.append(",\"totalMs\":").append(normal.totalMs)
        out.append(",\"ecoTotalMs\":").append(eco.totalMs)
        out.append(",\"static\":").append(normal.static)
        out.append(",\"interrupt\":\"").append(normal.interrupt.name).append('"')
        out.append(",\"targets\":[")

        val targets = normal.steps.map { it.target }.distinct()
        targets.forEachIndexed { index, target ->
            if (index > 0) out.append(',')
            val segments = normal.steps.filter { it.target == target }
            val head = segments.minByOrNull { it.atMs } ?: return@forEachIndexed

            out.append("{\"name\":\"").append(target.name).append('"')
            out.append(",\"axis\":\"").append(head.axis.name).append('"')
            out.append(",\"atMs\":").append(head.atMs)
            out.append(",\"durationMs\":").append(head.durationMs)
            out.append(",\"mass\":\"").append(head.mass.name).append('"')
            out.append(",\"settle\":").append(number(head.settle))
            out.append(",\"curve\":\"")
                .append(Choreography.curveFor(head.mass, head.axis).name).append('"')
            // 一个元素在一个场景里可能有多段（撞墙 + 归位），
            // 所以采样要用"按元素取姿态"的重载，它会自己跨段。
            out.append(",\"k\":")
            appendSamples(out, normal, target, stepMs)
            out.append(",\"kEco\":")
            appendSamples(out, eco, target, stepMs)
            out.append('}')
        }
        out.append("]}")
    }

    private fun appendSamples(
        out: StringBuilder,
        scene: Choreography.Scene,
        target: Choreography.Target,
        stepMs: Int,
    ) {
        out.append('[')
        var first = true
        var t = 0
        while (t <= scene.totalMs) {
            first = false
            out.append('[').append(t).append(',')
            appendPose(out, Playback.poseOf(scene, target, t))
            out.append(']')
            if (t + stepMs > scene.totalMs) break
            out.append(',')
            t += stepMs
        }
        // 末点若不是整步长，补一个 —— 否则播放器永远看不到"完全到位"的那一帧
        if (scene.totalMs % stepMs != 0 || scene.totalMs == 0) {
            if (!first) out.append(',')
            out.append('[').append(scene.totalMs).append(',')
            appendPose(out, Playback.poseOf(scene, target, scene.totalMs))
            out.append(']')
        }
        out.append(']')
    }

    private fun appendPose(out: StringBuilder, pose: Playback.Pose) {
        out.append(number(pose.alpha)).append(',')
        out.append(number(pose.dxDp)).append(',')
        out.append(number(pose.dyDp)).append(',')
        out.append(number(pose.scale)).append(',')
        out.append(number(pose.extent))
    }

    private fun number(value: Float): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        return String.format(Locale.ROOT, "%.4f", value)
    }

    /** 完整页面（数据已内联）。 */
    fun html(stepMs: Int = STEP_MS): String {
        val template = readTemplate()
        check(template.contains(PLACEHOLDER)) {
            "模板里找不到数据占位符 $PLACEHOLDER —— 模板被改坏了？"
        }
        return template.replace(PLACEHOLDER, json(stepMs))
    }

    /** 写到磁盘，返回写入的文件。 */
    fun writeTo(file: File, stepMs: Int = STEP_MS): File {
        file.parentFile?.mkdirs()
        file.writeText(html(stepMs), Charsets.UTF_8)
        return file
    }

    private fun readTemplate(): String {
        val stream = ChoreoDemo::class.java.getResourceAsStream(TEMPLATE)
            ?: error(
                "找不到 $TEMPLATE。检查 agentui 的 src/main/resources 是否参与打包",
            )
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
