package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveForPurpose]：按用途挑模型，且**每一级回落都能被说出来**。
 *
 * ⚠️ 这个测试守的是**静默**这一类 bug：
 *    `ChatPort` 曾经是 `firstOrNull { enabled }`（按 createdAt 升序 = **最旧优先**），
 *    用户配了多个模型时，聊天用哪个完全无从得知。
 *    下面 `顺序不再是_最旧优先_` 那条就是这次修改的回归锚点。
 */
class ModelResolutionTest {

    private var seq = 0

    /** 造一条配置。`seq` 自增 ⇒ 列表顺序即"添加顺序"（模拟 `ORDER BY createdAt ASC`）。 */
    private fun model(
        modelId: String,
        roles: Set<ModelRole> = setOf(ModelRole.WORKER),
        enabled: Boolean = true,
    ) = ModelConfig(
        id = "row-${seq++}",          // ★ 数据库行 id（UUID），**不是**模型名
        label = "",
        credentialId = "cred-1",
        modelId = modelId,
        modelDisplayName = modelId,
        tier = ModelTier.STANDARD,
        roles = roles,
        enabled = enabled,
    )

    // ── ① EXACT ─────────────────────────────────────────────────

    @Test
    fun `勾了对话的模型被选中且标为 EXACT`() {
        val target = model("glm-5.3-flash", roles = setOf(ModelRole.CHAT))
        val models = listOf(model("cheap", roles = setOf(ModelRole.WORKER)), target)

        val r = models.resolveForPurpose(ModelRole.CHAT)
        assertTrue(r is ModelResolution.Found)
        r as ModelResolution.Found
        assertEquals(target.id, r.config.id)
        assertEquals(ResolutionStep.EXACT, r.via)
        assertFalse("显式选中不该被标成回落", r.via.isFallback)
        assertEquals("显式选中不需要给提示", "", r.via.explanation)
    }

    // ── ② ★ 回归锚点：不再是"最旧优先" ──────────────────────────

    @Test
    fun `顺序不再是_最旧优先_`() {
        // 最早添加的是 WORKER，后加的是 CHAT ⇒ 必须选**后加的那个**
        val old = model("old-worker", roles = setOf(ModelRole.WORKER))
        val newer = model("new-chat", roles = setOf(ModelRole.CHAT))
        val models = listOf(old, newer)

        val r = models.resolveForPurpose(ModelRole.CHAT) as ModelResolution.Found
        assertEquals("应选勾了「对话」的那条，而不是最早添加的那条", newer.id, r.config.id)
        assertEquals(ResolutionStep.EXACT, r.via)
    }

    // ── ③ 三级回落 ──────────────────────────────────────────────

    @Test
    fun `没有勾对话时回落到执行任务并标出 FALLBACK_WORKER`() {
        val worker = model("only-worker", roles = setOf(ModelRole.WORKER))
        val r = listOf(worker).resolveForPurpose(ModelRole.CHAT) as ModelResolution.Found
        assertEquals(worker.id, r.config.id)
        assertEquals(ResolutionStep.FALLBACK_WORKER, r.via)
        assertTrue(r.via.isFallback)
        assertTrue("回落必须给得出「下一步」", r.via.explanation.contains("勾上"))
    }

    @Test
    fun `连执行任务都没有时兜底到任意启用项并标出 FALLBACK_ANY`() {
        // 只有调度者 —— 它不做实际工作，但此刻是唯一可用的
        val sched = model("sched-only", roles = setOf(ModelRole.SCHEDULER))
        val r = listOf(sched).resolveForPurpose(ModelRole.CHAT) as ModelResolution.Found
        assertEquals(sched.id, r.config.id)
        assertEquals(ResolutionStep.FALLBACK_ANY, r.via)
        assertTrue("兜底更要说清楚", r.via.explanation.contains("勾上"))
    }

    @Test
    fun `一条都没有时返回 None`() {
        assertEquals(ModelResolution.None, emptyList<ModelConfig>().resolveForPurpose(ModelRole.CHAT))
    }

    // ── ④ 停用必须被跳过（每一级都要跳）────────────────────────

    @Test
    fun `停用的对话模型被跳过_回落到执行任务`() {
        val disabledChat = model("chat-off", roles = setOf(ModelRole.CHAT), enabled = false)
        val worker = model("worker-on", roles = setOf(ModelRole.WORKER))
        val r = listOf(disabledChat, worker).resolveForPurpose(ModelRole.CHAT) as ModelResolution.Found
        assertEquals("停用的不能被选中", worker.id, r.config.id)
        assertEquals(ResolutionStep.FALLBACK_WORKER, r.via)
    }

    @Test
    fun `全部停用时返回 None`() {
        val models = listOf(
            model("a", roles = setOf(ModelRole.CHAT), enabled = false),
            model("b", roles = setOf(ModelRole.WORKER), enabled = false),
        )
        assertEquals(ModelResolution.None, models.resolveForPurpose(ModelRole.CHAT))
    }

    // ── ⑤ 用途之间互不串味 ─────────────────────────────────────

    @Test
    fun `挑调度者时不会命中只勾了对话的模型`() {
        val chatOnly = model("chat-only", roles = setOf(ModelRole.CHAT))
        // CHAT 不在 WORKER 的回落链里 ⇒ 挑 SCHEDULER 时只该走 FALLBACK_ANY
        val r = listOf(chatOnly).resolveForPurpose(ModelRole.SCHEDULER) as ModelResolution.Found
        assertEquals(chatOnly.id, r.config.id)
        assertEquals(ResolutionStep.FALLBACK_ANY, r.via)
    }

    @Test
    fun `挑执行任务时不会走_先回落到对话_这种怪路`() {
        val chat = model("chat", roles = setOf(ModelRole.CHAT))
        val worker = model("worker", roles = setOf(ModelRole.WORKER))
        val r = listOf(chat, worker).resolveForPurpose(ModelRole.WORKER) as ModelResolution.Found
        assertEquals("purpose 就是 WORKER 时第一步就该命中它", worker.id, r.config.id)
        assertEquals(ResolutionStep.EXACT, r.via)
    }

    // ── ⑤b 撇开本机已不存在的凭据 ─────────────────────────────

    @Test
    fun `绑着已删 Key 的模型不会被选中`() {
        // ★ 2026-11-22 真机回归：记忆抽取走的也是这个函数，它当时不看凭据，
        //   于是分身里选中了绑着已删 Key 的 glm-5.3-flash ⇒ useKey 返回 null
        //   ⇒ 记忆库永远 0 条，而日志只说"拿到空返回"。
        val orphan = ModelConfig(
            id = "row-orphan", label = "", credentialId = "gone",
            modelId = "glm-5.3-flash", modelDisplayName = "glm-5.3-flash",
            tier = ModelTier.STANDARD, roles = setOf(ModelRole.CHAT), enabled = true,
        )
        val live = ModelConfig(
            id = "row-live", label = "", credentialId = "alive",
            modelId = "deepseek-v4-pro", modelDisplayName = "deepseek-v4-pro",
            tier = ModelTier.STANDARD, roles = setOf(ModelRole.CHAT), enabled = true,
        )
        val r = listOf(orphan, live).resolveForPurpose(
            ModelRole.CHAT,
            knownCredentialIds = setOf("alive"),
        ) as ModelResolution.Found
        assertEquals("跳过了绑着已删 Key 的那条", live.id, r.config.id)
    }

    @Test
    fun `全都是孤儿配置时说的是去重加 Key 而不是去配模型`() {
        // ⚠️ 两者用户要做的动作完全不同。合并成一个 None 的话，
        //   界面只能说"没有可用模型"，而用户看着列表里明明有 22 个模型。
        val models = listOf(
            ModelConfig(
                id = "row-1", label = "", credentialId = "gone-1",
                modelId = "a", modelDisplayName = "a",
                tier = ModelTier.STANDARD, roles = setOf(ModelRole.CHAT), enabled = true,
            ),
        )
        val r = models.resolveForPurpose(ModelRole.CHAT, knownCredentialIds = setOf("other"))
        assertTrue(r is ModelResolution.AllOrphans)
        assertEquals("必须把数量带出来，界面才好说话", 1, (r as ModelResolution.AllOrphans).count)
    }

    @Test
    fun `不传凭据集合时行为与以前完全一致`() {
        // ⚠️ 这条守的是向后兼容：老调用方不传 knownCredentialIds，
        //   不能因为新参数就把它们的模型全判成孤儿。
        val m = model("glm-5.3-flash", roles = setOf(ModelRole.CHAT))
        val r = listOf(m).resolveForPurpose(ModelRole.CHAT) as ModelResolution.Found
        assertEquals(m.id, r.config.id)
    }

    // ── ⑥ 返回的是 ModelConfig，调用方要自己取 modelId ──────────

    @Test
    fun `返回的 config 里 id 与 modelId 是两个不同的东西`() {
        val m = model("glm-5.3-flash", roles = setOf(ModelRole.CHAT))
        val r = listOf(m).resolveForPurpose(ModelRole.CHAT) as ModelResolution.Found
        // ★ 这两行是那次「模型不存在」事故的核心：
        //   `id` 是数据库行 id（UUID），`modelId` 才是发给服务端的模型名。
        //   混用会得到「模型不存在」——而用户的 Key 和余额都没问题，
        //   于是他只会去查钱包，排查方向完全错。
        assertEquals("row-0", r.config.id)
        assertEquals("glm-5.3-flash", r.config.modelId)
        assertNotEquals("两者必须不同，否则说明哪里搞混了", r.config.id, r.config.modelId)
    }
}
