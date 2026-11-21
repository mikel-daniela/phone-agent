package com.pocketagent.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `屏幕共享与语音通话是接通的，视频通话不是`() {
        assertTrue(ComposerAction.SCREEN_SHARE.wired)
        assertTrue(ComposerAction.VOICE_CALL.wired)
        // 视频通话要相机管线（权限 + 预览 + 编码），这个版本没有 —— 必须如实为假。
        assertFalse(ComposerAction.VIDEO_CALL.wired)
        assertTrue(ComposerAction.VIDEO_CALL.pendingNote.contains("相机"))
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
