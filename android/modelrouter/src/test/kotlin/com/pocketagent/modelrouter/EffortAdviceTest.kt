package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考档的**推荐值**与"用户没动过就用推荐值"这条规矩。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 这个测试防的是哪一种 bug
 * ══════════════════════════════════════════════════════════════
 *
 * 用户 2026-11-21 的要求是"全部模型默认最低档，通话要快，屏幕共享中等，
 * 但这些都可以让用户自己改，同时要有推荐档位"。这里有三个容易做错的地方：
 *
 *  1. 默认档悄悄回到 HIGH —— 那就等于"用户什么都没改，却在等推理"；
 *  2. `resolve` 把"用户没改过"和"用户改成了和推荐一样"混为一谈 ——
 *     后果是用户手动选了 OFF，之后换场景**又被改回推荐值**；
 *  3. 屏幕共享的推荐值被写成 LOW —— 它其实要**读一屏界面**，比闲聊更费。
 */
class EffortAdviceTest {

    @Test
    fun `没动过的时候每个场景都取推荐值`() {
        for (scene in EffortScene.entries) {
            assertEquals(scene.name, EffortAdvice.recommended(scene), EffortAdvice.resolve(scene, null))
        }
    }

    @Test
    fun `闲聊与通话的推荐值都是最低档`() {
        assertEquals(ThinkingEffort.OFF, EffortAdvice.recommended(EffortScene.CHAT))
        assertEquals(ThinkingEffort.OFF, EffortAdvice.recommended(EffortScene.CALL))
    }

    @Test
    fun `屏幕共享的推荐值高于闲聊`() {
        val shot = EffortAdvice.recommended(EffortScene.SCREEN_SHARE)
        val chat = EffortAdvice.recommended(EffortScene.CHAT)
        assertTrue("屏幕共享要比闲聊更费思考：$shot vs $chat", shot.ordinal > chat.ordinal)
        // 而且它发出去的 wire 值必须是 medium —— 用户说的"中等"就是这个。
        assertEquals("medium", shot.wireValue)
    }

    @Test
    fun `用户改过就听用户的，包括比推荐值更低的选择`() {
        assertEquals(
            ThinkingEffort.MAX,
            EffortAdvice.resolve(EffortScene.CHAT, ThinkingEffort.MAX),
        )
        // ★ 关键：用户选了 OFF，屏幕共享也该是 OFF —— 不许被"推荐值"覆盖回去。
        assertEquals(
            ThinkingEffort.OFF,
            EffortAdvice.resolve(EffortScene.SCREEN_SHARE, ThinkingEffort.OFF),
        )
    }

    @Test
    fun `提示语在偏离推荐时会指出方向`() {
        val same = EffortAdvice.note(EffortScene.SCREEN_SHARE, EffortAdvice.recommended(EffortScene.SCREEN_SHARE))
        assertTrue(same.contains("推荐"))
        val lower = EffortAdvice.note(EffortScene.SCREEN_SHARE, ThinkingEffort.OFF)
        assertNotEquals(same, lower)
        assertTrue("偏离推荐时要说清代价：$lower", lower.contains("省") || lower.contains("费"))
    }

    @Test
    fun `有图必然归到屏幕共享这一类`() {
        assertEquals(EffortScene.SCREEN_SHARE, EffortScene.of(hasImage = true))
        assertEquals(EffortScene.CHAT, EffortScene.of(hasImage = false))
    }

    @Test
    fun `默认值就是最低档`() {
        assertEquals(ThinkingEffort.OFF, EffortAdvice.DEFAULT)
    }
}
