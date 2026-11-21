package com.pocketagent.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框那一排图标的"诚实性"。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 这个测试防的是哪一种 bug
 * ══════════════════════════════════════════════════════════════
 *
 * `ComposerAction.wired = false` 时，点击必须落到 `pendingNote`，
 * 那是一句**给用户看的话**。真实发生过的漂移是：加图标时把 `wired`
 * 忘了设（默认值或复制粘贴），于是图标看起来能用、点下去**毫无反应** ——
 * 用户不会认为"这个功能没做"，而会认为"这个应用坏了"。
 *
 * 所以这里钉两件事：
 * 1. 没接通的，必须**有话说**（pendingNote 不能是空串）；
 * 2. 接通的，不许还挂着"还没接上"这种话（会让人以为点了没用）。
 */
class ComposerActionTest {

    @Test
    fun `没接通的入口必须有一句能给人看的话`() {
        val silent = ComposerAction.entries.filter { !it.wired && it.pendingNote.isBlank() }
        assertEquals("这些入口点了会没反应：$silent", emptyList<ComposerAction>(), silent)
    }

    @Test
    fun `接通的入口不许再挂一句还没接上`() {
        val lying = ComposerAction.entries.filter { it.wired && it.pendingNote.contains("还没接上") }
        assertEquals("这些入口已经能用，却还在说没接上：$lying", emptyList<ComposerAction>(), lying)
    }

    @Test
    fun `通话与屏幕共享都已接通`() {
        assertTrue(ComposerAction.SCREEN_SHARE.wired)
        assertTrue(ComposerAction.VOICE_CALL.wired)
        // ★ 2026-11-21：视频通话接通了 —— 相机管线走老的 hardware.Camera，
        //   权限在通话页运行时申请。所以这里断言它**不再**是占位。
        assertTrue(ComposerAction.VIDEO_CALL.wired)
        assertEquals("", ComposerAction.VIDEO_CALL.pendingNote)
    }

    @Test
    fun `只有一个入口还没接通 —— 语音输入`() {
        // 这条是"接线债"的账本：数字变了就说明有人推进了或退回了。
        // 现在只剩语音输入（要先把输入框那条 STT 路接上）。
        val notWired = ComposerAction.entries.filter { !it.wired }.map { it.name }
        assertEquals(listOf("AUDIO"), notWired)
    }

    @Test
    fun `每个入口都有标签和图标可用`() {
        for (action in ComposerAction.entries) {
            assertTrue("入口没有标签：$action", action.label.isNotBlank())
        }
        // 数量钉死，防止有人加图标不加提示（上面两条会红，但先在这里提醒一句）
        assertEquals(6, ComposerAction.entries.size)
    }
}
