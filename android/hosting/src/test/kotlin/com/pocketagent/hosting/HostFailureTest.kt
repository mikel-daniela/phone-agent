package com.pocketagent.hosting

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 虚拟屏失败的分类与话术。
 *
 * 依据：用户 m15452「脱离电脑环境时，虚拟屏起不来……执行失败」——
 * 没电脑时拿不到 logcat，只能靠角色那句话把原因说清楚。
 */
class HostFailureTest {

    @Test
    fun su没通算没有root() {
        assertThat(HostFailure.classify("root 命令没返回（su 不可用？）：exit=124"))
            .isEqualTo(HostFailure.Kind.NO_ROOT)
        assertThat(HostFailure.classify("这台机器没有 root")).isEqualTo(HostFailure.Kind.NO_ROOT)
    }

    @Test
    fun 建屏失败算建不了屏() {
        assertThat(
            HostFailure.classify("虚拟屏没建起来：createVirtualDisplay 被拒（SecurityException: x）"),
        ).isEqualTo(HostFailure.Kind.NO_DISPLAY)
        assertThat(HostFailure.classify("虚拟屏还没建起来 —— 先调用 action=start"))
            .isEqualTo(HostFailure.Kind.NO_DISPLAY)
    }

    @Test
    fun 找不到入口与启动被拒分开() {
        assertThat(HostFailure.classify("「com.tencent.mm」没有可启动的入口（可能它没有桌面图标）"))
            .isEqualTo(HostFailure.Kind.NO_ENTRY)
        assertThat(HostFailure.classify("启动失败：exit=1 Error: Activity not started"))
            .isEqualTo(HostFailure.Kind.REJECTED)
    }

    @Test
    fun 没有画面与输入不了中文分开() {
        assertThat(HostFailure.classify("这块屏上暂时还没有画面（应用可能还在冷启动）"))
            .isEqualTo(HostFailure.Kind.NO_FRAME)
        assertThat(HostFailure.classify("这段文字里有 input 送不进去的字符（关于你），只认 ASCII"))
            .isEqualTo(HostFailure.Kind.TEXT_NOT_ASCII)
    }

    @Test
    fun 认不出来就说不认识不去猜() {
        assertThat(HostFailure.classify(null)).isEqualTo(HostFailure.Kind.UNKNOWN)
        assertThat(HostFailure.classify("   ")).isEqualTo(HostFailure.Kind.UNKNOWN)
        assertThat(HostFailure.classify("something went wrong")).isEqualTo(HostFailure.Kind.UNKNOWN)
    }

    @Test
    fun 每一类都有自己的做法且互不重复() {
        val kinds = HostFailure.Kind.entries.filter { it != HostFailure.Kind.UNKNOWN }
        val advices = kinds.map { HostFailure.advice(it) }
        assertThat(advices.toSet().size).isEqualTo(kinds.size)
        assertThat(advices.all { it.length >= 20 }).isTrue()
    }

    @Test
    fun 没有root的做法要指向设置里的运行模式() {
        val text = HostFailure.advice(HostFailure.Kind.NO_ROOT)
        assertThat(text).contains("运行模式")
        assertThat(text).contains("root")
    }

    @Test
    fun 描述先给原因再给做法() {
        val text = HostFailure.describe("虚拟屏没建起来：createVirtualDisplay 被拒（SecurityException）")
        assertThat(text).startsWith("虚拟屏没建起来")
        assertThat(text).contains("↘")
        assertThat(text.indexOf("createVirtualDisplay"))
            .isLessThan(text.indexOf("↘"))
    }

    @Test
    fun 没拿到原因时也说人话() {
        val text = HostFailure.describe(null)
        assertThat(text).contains("没有拿到原因")
        assertThat(text).contains("↘")
    }

    @Test
    fun 英文数字能送进去_中文不行() {
        assertThat(HostFailure.isTypeable("hello 123 !@#")).isTrue()
        assertThat(HostFailure.isTypeable("关于你")).isFalse()
        assertThat(HostFailure.isTypeable("hello 关于")).isFalse()
    }

    @Test
    fun 送不进去时点出是哪几个字() {
        assertThat(HostFailure.typingNote("hello")).isNull()
        val note = HostFailure.typingNote("关于你的今天")
        assertThat(note).isNotNull()
        assertThat(note!!).contains("关于你的今天".take(4))
        assertThat(note).contains("ASCII")
    }
}
