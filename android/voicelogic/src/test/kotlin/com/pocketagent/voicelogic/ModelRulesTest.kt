package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型使用规则的合并。
 *
 * 这一份测的核心是**谁说了算**：接口探测到的 > 上次实测的 > 内置文档的 > 保守默认。
 * 顺序错了不会报错，只会让一个明明知道上限的模型被按 2000 硬切 —— 慢，但看起来正常。
 */
class ModelRulesTest {

    // ═══════════════════════════════════════════════════════
    //  一、来源优先级
    // ═══════════════════════════════════════════════════════

    @Test
    fun `接口探测到的上限压过内置文档`() {
        val rules = ModelRulesResolver.resolve(
            "tts-1",
            listOf(
                RuleHint(RuleSource.BUILTIN, maxCharacters = 4096),
                RuleHint(RuleSource.PROBED, maxCharacters = 1024),
            ),
        )

        assertEquals(1024, rules.maxCharacters)
        assertEquals(RuleSource.PROBED, rules.origins[RuleField.MAX_CHARACTERS])
    }

    @Test
    fun `上次实测压过内置文档`() {
        // 文档说 4096，但这个中转站上次 3000 字就被拒了。实测更可信。
        val rules = ModelRulesResolver.resolve(
            "tts-1",
            listOf(
                RuleHint(RuleSource.BUILTIN, maxCharacters = 4096),
                RuleHint(RuleSource.STORED, maxCharacters = 3000),
            ),
        )

        assertEquals(3000, rules.maxCharacters)
        assertEquals(RuleSource.STORED, rules.origins[RuleField.MAX_CHARACTERS])
    }

    @Test
    fun `探测压过实测`() {
        val rules = ModelRulesResolver.resolve(
            "m",
            listOf(
                RuleHint(RuleSource.STORED, maxCharacters = 3000),
                RuleHint(RuleSource.PROBED, maxCharacters = 8000),
            ),
        )

        assertEquals(8000, rules.maxCharacters)
    }

    @Test
    fun `线索顺序不影响结果`() {
        val forward = ModelRulesResolver.resolve(
            "m",
            listOf(
                RuleHint(RuleSource.CONSERVATIVE, maxCharacters = 2000),
                RuleHint(RuleSource.PROBED, maxCharacters = 1024),
            ),
        )
        val backward = ModelRulesResolver.resolve(
            "m",
            listOf(
                RuleHint(RuleSource.PROBED, maxCharacters = 1024),
                RuleHint(RuleSource.CONSERVATIVE, maxCharacters = 2000),
            ),
        )

        assertEquals(forward, backward)
    }

    // ═══════════════════════════════════════════════════════
    //  二、逐项合并（不是整条替换）
    // ═══════════════════════════════════════════════════════

    @Test
    fun `接口只说了音色时上限仍然沿用内置的`() {
        // 真实情况：探测拿回音色列表，但没回上限。整条替换会把 4096 丢掉。
        val rules = ModelRulesResolver.resolve(
            "tts-1",
            listOf(
                RuleHint(RuleSource.BUILTIN, maxCharacters = 4096),
                RuleHint(RuleSource.PROBED, inlineTokens = setOf("[uv_break]")),
            ),
        )

        assertEquals(4096, rules.maxCharacters)
        assertEquals(RuleSource.BUILTIN, rules.origins[RuleField.MAX_CHARACTERS])
        assertEquals(RuleSource.PROBED, rules.origins[RuleField.INLINE_TOKENS])
        assertTrue(rules.capabilities.inlineTokens.contains("[uv_break]"))
    }

    @Test
    fun `每一档各管一项时四项来源各不相同`() {
        val rules = ModelRulesResolver.resolve(
            "m",
            listOf(
                RuleHint(RuleSource.CONSERVATIVE),
                RuleHint(RuleSource.BUILTIN, ssml = true),
                RuleHint(RuleSource.STORED, instructions = true),
                RuleHint(RuleSource.PROBED, maxCharacters = 1500),
            ),
        )

        assertEquals(RuleSource.PROBED, rules.origins[RuleField.MAX_CHARACTERS])
        assertEquals(RuleSource.STORED, rules.origins[RuleField.INSTRUCTIONS])
        assertEquals(RuleSource.BUILTIN, rules.origins[RuleField.SSML])
        assertNull(rules.origins[RuleField.INLINE_TOKENS])
    }

    // ═══════════════════════════════════════════════════════
    //  三、null 表示"这条线索没提"
    // ═══════════════════════════════════════════════════════

    @Test
    fun `探测到的 false 不能被下级的 true 顶掉`() {
        // 「探测到不支持」和「没探测到」是两件事。若把 null 当成 false 参与合并，
        // 下线里的 true 会翻上来，于是给一个不支持 SSML 的厂商发 SSML。
        val rules = ModelRulesResolver.resolve(
            "m",
            listOf(
                RuleHint(RuleSource.PROBED, ssml = false),
                RuleHint(RuleSource.BUILTIN, ssml = true),
            ),
        )

        assertFalse(rules.capabilities.ssml)
        assertEquals(RuleSource.PROBED, rules.origins[RuleField.SSML])
    }

    @Test
    fun `空线索只贡献来源不贡献值`() {
        val rules = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.PROBED)))

        assertEquals(ModelRulesResolver.CONSERVATIVE_MAX_CHARACTERS, rules.maxCharacters)
        assertTrue(rules.origins.isEmpty())
    }

    @Test
    fun `没有任何线索时全部走保守默认`() {
        val rules = ModelRulesResolver.resolve("m", emptyList())

        assertEquals(2000, rules.maxCharacters)
        assertFalse(rules.capabilities.instructions)
        assertFalse(rules.capabilities.ssml)
        assertTrue(rules.capabilities.inlineTokens.isEmpty())
    }

    // ═══════════════════════════════════════════════════════
    //  四、保守默认的取值
    // ═══════════════════════════════════════════════════════

    @Test
    fun `保守默认给数不留空`() {
        // 留 null 意味着"不切段"，超长直接被接口拒 = 没有声音；
        // 给 2000 只是多分几段 = 慢一点。
        assertEquals(2000, ModelRulesResolver.CONSERVATIVE_MAX_CHARACTERS)
    }

    @Test
    fun `保守默认不能大过常见厂商的真实上限`() {
        // 4096 是 OpenAI 的数，国内厂商普遍 1024 / 2000。按 4096 切等于没切。
        assertTrue(ModelRulesResolver.CONSERVATIVE_MAX_CHARACTERS <= 2048)
    }

    @Test
    fun `上限永远不是一个空值`() {
        val rules = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.BUILTIN)))

        assertTrue(rules.maxCharacters > 0)
    }

    // ═══════════════════════════════════════════════════════
    //  五、能不能表达标签
    // ═══════════════════════════════════════════════════════

    @Test
    fun `三种表达方式任一可用就算能表达`() {
        assertTrue(
            ModelRulesResolver
                .resolve("m", listOf(RuleHint(RuleSource.PROBED, instructions = true)))
                .canExpressTags,
        )
        assertTrue(
            ModelRulesResolver
                .resolve("m", listOf(RuleHint(RuleSource.PROBED, ssml = true)))
                .canExpressTags,
        )
        assertTrue(
            ModelRulesResolver
                .resolve("m", listOf(RuleHint(RuleSource.PROBED, inlineTokens = setOf("[uv_break]"))))
                .canExpressTags,
        )
    }

    @Test
    fun `纯文本模型算不能表达`() {
        val rules = ModelRulesResolver.resolve(
            "m",
            listOf(RuleHint(RuleSource.PROBED, instructions = false, ssml = false)),
        )

        assertFalse(rules.canExpressTags)
    }

    @Test
    fun `空标签集合不算能表达`() {
        val rules = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.PROBED, inlineTokens = emptySet())))

        assertFalse(rules.canExpressTags)
    }

    // ═══════════════════════════════════════════════════════
    //  六、成功后记的账
    // ═══════════════════════════════════════════════════════

    @Test
    fun `成功记录用的是实际发出去的长度`() {
        val hint = ModelRulesResolver.recordSuccess("m", sentCharacters = 1800, previous = null)

        assertEquals(1800, hint.maxCharacters)
        assertEquals(RuleSource.STORED, hint.source)
    }

    @Test
    fun `成功一次不会把上限压低`() {
        // 上次顺利发了 3000，这次只发了 500。记 500 会让以后都按 500 切。
        val previous = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.PROBED, maxCharacters = 3000)))
        val hint = ModelRulesResolver.recordSuccess("m", sentCharacters = 500, previous = previous)

        assertEquals(3000, hint.maxCharacters)
    }

    @Test
    fun `成功记录的最低位至少是 1`() {
        val hint = ModelRulesResolver.recordSuccess("m", sentCharacters = 0, previous = null)

        assertTrue(hint.maxCharacters!! >= 1)
    }

    @Test
    fun `成功记录不谎报其它能力`() {
        // 只观察到了长度，没有观察 SSML / 说明 / 标签。这三项必须是 null（未提），
        // 否则下次合并会把它们的保守默认当成实测结论。
        val hint = ModelRulesResolver.recordSuccess("m", sentCharacters = 100, previous = null)

        assertNull(hint.instructions)
        assertNull(hint.ssml)
        assertNull(hint.inlineTokens)
    }

    // ═══════════════════════════════════════════════════════
    //  七、给人看的一句话
    // ═══════════════════════════════════════════════════════

    @Test
    fun `描述里带上模型名与上限`() {
        val rules = ModelRulesResolver.resolve("tts-1", listOf(RuleHint(RuleSource.PROBED, maxCharacters = 4096)))
        val text = rules.describe()

        assertTrue(text.contains("tts-1"))
        assertTrue(text.contains("4096"))
    }

    @Test
    fun `描述里写清上限是从哪知道的`() {
        val probed = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.PROBED, maxCharacters = 4096)))
        val guessed = ModelRulesResolver.resolve("m", emptyList())

        assertTrue(probed.describe().contains("接口探测"))
        assertTrue(guessed.describe().contains("保守默认"))
    }

    @Test
    fun `不支持标签时描述里明说`() {
        val rules = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.PROBED, instructions = false)))

        assertTrue(rules.describe().contains("不支持声音标签"))
    }

    @Test
    fun `支持标签时描述里不写不支持`() {
        val rules = ModelRulesResolver.resolve("m", listOf(RuleHint(RuleSource.PROBED, instructions = true)))

        assertFalse(rules.describe().contains("不支持声音标签"))
    }
}
