package com.pocketagent.agentui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `ToolTrace` 的规矩。
 *
 * 这些断言对应的是**界面能不能正确显示"它做了什么"** ——
 * 拆错一条，用户就会看到"全部成功"而实际上有一步被拦下了。
 */
class ToolTraceTest {

    @Test
    fun `成功那行拆出工具名与结果`() {
        val step = ToolTrace.parse("linux_exec → 退出码 0")
        assertThat(step.tool).isEqualTo("linux_exec")
        assertThat(step.detail).isEqualTo("退出码 0")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.DONE)
    }

    @Test
    fun `失败那行是失败不是成功`() {
        val step = ToolTrace.parse("linux_exec 失败：容器没起来")
        assertThat(step.tool).isEqualTo("linux_exec")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.FAILED)
        assertThat(step.detail).isEqualTo("容器没起来")
    }

    @Test
    fun `被拦下那行是拦住不是失败`() {
        val step = ToolTrace.parse("android_tap 被拦下：这一步要花钱")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.BLOCKED)
        assertThat(step.detail).isEqualTo("这一步要花钱")
    }

    @Test
    fun `详情里再出现分隔符时只看最先出现的那个`() {
        // `.oneLine()` 会把换行压空格，详情完全可能自带 " → "。
        val step = ToolTrace.parse("read_file → 内容是 a → b")
        assertThat(step.tool).isEqualTo("read_file")
        assertThat(step.detail).isEqualTo("内容是 a → b")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.DONE)
    }

    @Test
    fun `开头就是分隔符的行认不出来`() {
        val step = ToolTrace.parse(" → 没有工具名")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.UNKNOWN)
        assertThat(step.tool).isEmpty()
    }

    @Test
    fun `带空白的工具名认不出来，但整行原样留着`() {
        val step = ToolTrace.parse("它说 linux_exec → 退出码 0")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.UNKNOWN)
        // ⚠️ 关键：内容不能丢 —— 界面上宁可多一行没有工具名的字。
        assertThat(step.detail).isEqualTo("它说 linux_exec → 退出码 0")
    }

    @Test
    fun `没有分隔符的行归到未知`() {
        val step = ToolTrace.parse("正在想下一步")
        assertThat(step.outcome).isEqualTo(ToolTrace.Outcome.UNKNOWN)
        assertThat(step.detail).isEqualTo("正在想下一步")
    }

    @Test
    fun `空行不算一步`() {
        assertThat(ToolTrace.parse(listOf("a → b", "  ", "", "c → d"))).hasSize(2)
    }

    @Test
    fun `摘要同时报成功与失败`() {
        val steps = ToolTrace.parse(
            listOf(
                "android_tap → 点到了",
                "android_tap 失败：没有无障碍",
                "linux_exec → 退出码 0",
            ),
        )
        assertThat(ToolTrace.summary(steps)).isEqualTo("跑了 3 个动作 · 2 成功 / 1 失败")
    }

    @Test
    fun `摘要把被拦下的单独数`() {
        val steps = ToolTrace.parse(
            listOf(
                "android_tap → 点到了",
                "android_type 被拦下：不许输验证码",
            ),
        )
        assertThat(ToolTrace.summary(steps)).isEqualTo("跑了 2 个动作 · 1 成功 / 1 被拦")
    }

    @Test
    fun `没跑过动作时摘要为空`() {
        assertThat(ToolTrace.summary(emptyList())).isEmpty()
    }

    @Test
    fun `一条都没认出来也要报个数`() {
        val steps = ToolTrace.parse(listOf("莫名其妙的一行"))
        assertThat(ToolTrace.summary(steps)).isEqualTo("跑了 1 个动作")
    }
}
