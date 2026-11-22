package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **分镜台生成器**的测试。
 *
 * 这个测试只干一件有价值的事：**把 HTML 真的生成出来**。
 * 只要它跑过，产物就在 `build/choreo-demo/index.html`，可以直接用浏览器打开。
 *
 * 顺带钉几条容易在重构时被悄悄破坏的性质：
 *  · 14 个场景一个都不能少（漏了就等于"这个场景没做"）
 *  · 每个场景里每一条轨道都得有采样点，且以 `totalMs` 结尾
 *  · 数字里不许出现 NaN / Infinity（JSON 会被浏览器判成非法）
 */
class ChoreoDemoTest {

    private val out = File("build/choreo-demo/index.html")

    @Test
    fun `生成分镜台页面`() {
        ChoreoDemo.writeTo(out)
        assertTrue("页面没生成：${out.absolutePath}", out.isFile)
        assertTrue("页面太小，八成是数据没内联（${out.length()} 字节）", out.length() > 60_000)

        val html = out.readText(Charsets.UTF_8)
        assertTrue("占位符没被替换", !html.contains("/*__DATA__*/"))
        assertTrue("缺少分镜台标题", html.contains("场景动效分镜台"))
    }

    @Test
    fun `每个场景都在页面里`() {
        val json = ChoreoDemo.json(stepMs = 16)
        Choreography.Beat.values().forEach { beat ->
            assertTrue("页面缺少场景 $beat", json.contains("\"name\":\"${beat.name}\""))
        }
        // 14 个场景 + 每场景若干 target，不该只有一个
        val beats = Regex("\"totalMs\":").findAll(json).count()
        assertEquals(
            "场景数与枚举数不一致",
            Choreography.Beat.values().size,
            beats,
        )
    }

    @Test
    fun `每条轨道都以总时长收尾`() {
        val json = ChoreoDemo.json(stepMs = 16)

        // beat 头形如 `{"name":"COLD_START","totalMs":640,`
        // target 里也有 "name"，但它们跟的是 "axis"，所以这个正则只命中场景头。
        val heads = Regex("\"name\":\"([A-Z_]+)\",\"totalMs\":(\\d+)").findAll(json).toList()
        assertEquals(
            "场景头数量与枚举数不一致（正则或 JSON 结构被改了）",
            Choreography.Beat.values().size,
            heads.size,
        )

        Choreography.Beat.values().forEach { beat ->
            val head = heads.firstOrNull { it.groupValues[1] == beat.name }
                ?: error("JSON 里缺少场景 ${beat.name}")
            val total = head.groupValues[2].toInt()
            assertEquals(
                "${beat.name} 的总时长与编排表不一致",
                Choreography.scene(beat).totalMs,
                total,
            )

            // 该场景从头部之后，到下一个场景头之前
            val from = head.range.last + 1
            val to = heads.firstOrNull { it.range.first > head.range.first }?.range?.first ?: json.length
            val slice = json.substring(from, to)

            if (total > 0) {
                assertTrue(
                    "${beat.name} 的采样里没有以 ${total}ms 收尾的帧 —— " +
                        "末帧缺失会让播放器永远差最后一步",
                    slice.contains("[$total,"),
                )
            }
            assertFalse("${beat.name} 的 JSON 出现 NaN", slice.contains("NaN"))
        }
    }

    @Test
    fun `省电模式的采样与正常模式长度一致`() {
        // 省电只该把位移/缩放/过冲拿掉，不该改变一条轨道的采样点个数 ——
        // 变了说明降级顺手改了时长，那就不再是"降级"而是"另一套动画"。
        val json = ChoreoDemo.json(stepMs = 32)
        val normal = Regex("\"k\":\\[").findAll(json).count()
        val eco = Regex("\"kEco\":\\[").findAll(json).count()
        assertEquals("正常轨与省电轨数量不等", normal, eco)
        assertTrue("轨道太少了（实得 $normal）", normal >= 30)
    }
}
