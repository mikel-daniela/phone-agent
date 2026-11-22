package com.pocketagent.keylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别器的行为测试。
 *
 * ★ 这里钉的不是「认得出多少家」，而是三件更容易出事的事：
 *   1. **确定性**：同样的输入永远同样的结论，且结论不取决于 catalog 里谁先写
 *   2. **不误伤**：没收录的格式必须是 Unknown（放行），不是 Rejected（拦下）
 *   3. **优先级**：`sk-ant-api03-…` 不能被泛 `sk-` 抢先认成 OpenAI 兼容
 *       —— 这条错了不会抛异常，只会让用户去改一个本来就对的配置
 */
class ApiKeyRecognizerTest {

    private fun identified(key: String): ApiKeyFormat {
        val recognition = ApiKeyRecognizer.recognize(key)
        assertTrue("期望 Identified，实际 $recognition", recognition is KeyRecognition.Identified)
        return (recognition as KeyRecognition.Identified).keyFormat
    }

    private fun rejected(key: String): RejectReason {
        val recognition = ApiKeyRecognizer.recognize(key)
        assertTrue("期望 Rejected，实际 $recognition", recognition is KeyRecognition.Rejected)
        return (recognition as KeyRecognition.Rejected).reason
    }

    // ── BreezeBlue（本次要加的那一家）──────────────────────────────────

    @Test
    fun `brz 前缀识别为 BreezeBlue`() {
        val format = identified("brz_Y3XIHNbh9kQmvT7pLd2s")
        assertEquals("breezeblue", format.id)
        assertEquals(KeyStrength.SIGNATURE, format.strength)
    }

    @Test
    fun `BreezeBlue 的 key 里可以有连字符与大写`() {
        // 官方文档里 /v1/api-keys/current 返回的 prefix 形如 brz_Y3XI-NHN，
        // 说明连字符与大小写都出现在 key 本体里 —— 正则若只写 [a-z0-9] 就会漏。
        assertEquals("breezeblue", identified("brz_Y3XI-NHNabc123XYZ").id)
    }

    @Test
    fun `BreezeBlue 走 xi-api-key 头而不是 Bearer`() {
        // ★ 填错认证头会把「配置问题」伪装成「key 无效」：用户会去重新生成 key。
        assertEquals("xi-api-key", identified("brz_Y3XIHNbh9kQmvT7pLd2s").authHeader)
    }

    @Test
    fun `BreezeBlue 同时能用于对话与语音`() {
        assertEquals(KeyKind.BOTH, identified("brz_Y3XIHNbh9kQmvT7pLd2s").kind)
    }

    @Test
    fun `brz 后面太短则不认`() {
        // 11 个字符，过了 MIN_KEY_LENGTH，但 brz_ 后面只有 7 位，不够 {12,}
        assertTrue(ApiKeyRecognizer.recognize("brz_abcdefg") is KeyRecognition.Unknown)
    }

    // ── 优先级：更具体的签名字前缀必须赢 ────────────────────────────────

    @Test
    fun `sk-ant-api03 不会被泛 sk- 抢先`() {
        assertEquals("anthropic-api03", identified(ApiKeyCatalog.SAMPLES.getValue("anthropic-api03")).id)
    }

    @Test
    fun `sk-ant 旧前缀仍然认得出`() {
        assertEquals("anthropic", identified(ApiKeyCatalog.SAMPLES.getValue("anthropic")).id)
    }

    @Test
    fun `字面前缀长度能区分具体度`() {
        // 三个前缀的字面部分：sk-ant-api03- / sk-ant- / sk-
        assertEquals(13, ApiKeyRecognizer.literalPrefixLength("^sk-ant-api03-[A-Za-z0-9_-]{20,}\$"))
        assertEquals(7, ApiKeyRecognizer.literalPrefixLength("^sk-ant-[A-Za-z0-9_-]{20,}\$"))
        assertEquals(3, ApiKeyRecognizer.literalPrefixLength("^sk-[A-Za-z0-9_-]{16,}\$"))
    }

    @Test
    fun `brz 的字面前缀是四位`() {
        assertEquals(4, ApiKeyRecognizer.literalPrefixLength("^brz_[A-Za-z0-9_-]{12,}\$"))
    }

    @Test
    fun `以字符类开头的正则没有字面前缀`() {
        // 裸 hex / 裸 alnum 这类「弱指纹」本来就不该有信息量
        assertEquals(0, ApiKeyRecognizer.literalPrefixLength("^[A-Za-z0-9]{32}\$"))
    }

    // ── 签名级：其它厂商 ──────────────────────────────────────────────

    @Test
    fun `签名级厂商逐一认得出来`() {
        val expected = mapOf(
            "openai-project" to "openai-project",
            "openai-svcacct" to "openai-svcacct",
            "openai-admin" to "openai-admin",
            "openrouter" to "openrouter",
            "groq" to "groq",
            "xai" to "xai",
            "perplexity" to "perplexity",
            "huggingface" to "huggingface",
            "replicate" to "replicate",
            "google-api" to "google-api",
            "fireworks" to "fireworks",
            "together" to "together",
            "nvidia" to "nvidia",
            "cerebras" to "cerebras",
            "elevenlabs" to "elevenlabs",
            "jwt" to "jwt",
        )
        for ((sampleId, expectedId) in expected) {
            val sample = ApiKeyCatalog.SAMPLES.getValue(sampleId)
            assertEquals("样本 $sampleId 认错了", expectedId, identified(sample).id)
        }
    }

    @Test
    fun `ElevenLabs 是下划线不是连字符`() {
        // sk_ 与 sk- 只差一个字符，但含义完全不同：
        // 把 sk_… 填到 OpenAI 那栏会得到 401，用户会以为是 key 错了。
        val format = identified(ApiKeyCatalog.SAMPLES.getValue("elevenlabs"))
        assertEquals("elevenlabs", format.id)
        assertEquals(KeyKind.TTS, format.kind)
    }

    // ── 家族级：只能定到一片厂商 ──────────────────────────────────────

    @Test
    fun `裸 sk 连字符只能定到 OpenAI 兼容家族`() {
        val recognition = ApiKeyRecognizer.recognize(ApiKeyCatalog.SAMPLES.getValue("openai-compat"))
        assertTrue(recognition is KeyRecognition.Ambiguous)
        val ambiguous = recognition as KeyRecognition.Ambiguous
        assertEquals("openai-compat", ambiguous.formats.first().id)
    }

    @Test
    fun `家族级即使只命中一条也仍是 Ambiguous`() {
        // ★ 确定性由「档位」决定，不由「命中条数」决定：
        //   openai-compat 这一条本身就代表十几家，只有一条≠知道是谁。
        val recognition = ApiKeyRecognizer.recognize(ApiKeyCatalog.SAMPLES.getValue("openai-compat"))
        assertTrue(recognition is KeyRecognition.Ambiguous)
    }

    // ── 弱级 ─────────────────────────────────────────────────────────

    @Test
    fun `32 位十六进制落到弱候选`() {
        val recognition = ApiKeyRecognizer.recognize(ApiKeyCatalog.SAMPLES.getValue("hex32"))
        assertTrue(recognition is KeyRecognition.Ambiguous)
        val ids = (recognition as KeyRecognition.Ambiguous).formats.map { it.id }
        assertTrue("应包含 hex32，实际 $ids", ids.contains("hex32"))
    }

    @Test
    fun `弱候选的顺序是确定的`() {
        val sample = ApiKeyCatalog.SAMPLES.getValue("hex32")
        val first = ApiKeyRecognizer.recognize(sample)
        val second = ApiKeyRecognizer.recognize(sample)
        assertEquals(first, second)
    }

    // ── 不认识 ≠ 有错（最容易写错的一条）──────────────────────────────

    @Test
    fun `没收录的格式是 Unknown 而不是错误`() {
        // ★ 任何人今天都可以开一家新厂商、发一种新前缀的 key。
        //   拿过时的表去拦他，等于把我们跟不上时代变成他的错。
        val recognition = ApiKeyRecognizer.recognize("zzzzzzzzzzzzzzzzzzzz")
        assertTrue(recognition is KeyRecognition.Unknown)
        assertFalse(ApiKeyRecognizer.shouldBlockSave(recognition))
    }

    // ── 输入本身的问题 ────────────────────────────────────────────────

    @Test
    fun `空输入`() {
        assertEquals(RejectReason.EMPTY, rejected(""))
        assertEquals(RejectReason.EMPTY, rejected("   "))
    }

    @Test
    fun `中间有空白`() {
        assertEquals(RejectReason.HAS_WHITESPACE, rejected("sk-abc def ghi jkl"))
    }

    @Test
    fun `含中文`() {
        assertEquals(RejectReason.NON_ASCII, rejected("sk-abcdefg中文xyz"))
    }

    @Test
    fun `粘成了网址`() {
        assertEquals(RejectReason.LOOKS_LIKE_URL, rejected("https://api.deepseek.com/v1"))
        assertEquals(RejectReason.LOOKS_LIKE_URL, rejected("www.example.com/key"))
    }

    @Test
    fun `粘成了邮箱`() {
        assertEquals(RejectReason.LOOKS_LIKE_EMAIL, rejected("someone@example.com"))
    }

    @Test
    fun `太短`() {
        assertEquals(RejectReason.TOO_SHORT, rejected("short"))
    }

    @Test
    fun `太长`() {
        assertEquals(RejectReason.TOO_LONG, rejected("a".repeat(ApiKeyRecognizer.MAX_KEY_LENGTH + 1)))
    }

    @Test
    fun `首尾空白被容忍`() {
        // 粘贴带尾随换行是常态，那不是用户的错。
        assertEquals("breezeblue", identified("\n  brz_Y3XIHNbh9kQmvT7pLd2s \r\n").id)
    }

    // ── shouldBlockSave：只有「不是 key」才拦 ─────────────────────────

    @Test
    fun `输入本身的问题才拦保存`() {
        assertTrue(ApiKeyRecognizer.shouldBlockSave(KeyRecognition.Rejected(RejectReason.EMPTY)))
        assertTrue(ApiKeyRecognizer.shouldBlockSave(KeyRecognition.Rejected(RejectReason.HAS_WHITESPACE)))
        assertTrue(ApiKeyRecognizer.shouldBlockSave(KeyRecognition.Rejected(RejectReason.LOOKS_LIKE_URL)))
        assertTrue(ApiKeyRecognizer.shouldBlockSave(KeyRecognition.Rejected(RejectReason.TOO_LONG)))
    }

    @Test
    fun `长度不足不拦保存`() {
        // ★ 长度判定归 keymgmt 的 CredentialInput（MIN_KEY_LENGTH 同样是 8）。
        //   两处各写一套，最后用户会看到两个互相矛盾的提示。
        assertFalse(ApiKeyRecognizer.shouldBlockSave(KeyRecognition.Rejected(RejectReason.TOO_SHORT)))
    }

    @Test
    fun `识别成功与不认识都不拦保存`() {
        assertFalse(ApiKeyRecognizer.shouldBlockSave(KeyRecognition.Unknown))
        assertFalse(
            ApiKeyRecognizer.shouldBlockSave(
                KeyRecognition.Identified(ApiKeyCatalog.byId("breezeblue")!!),
            ),
        )
        assertFalse(
            ApiKeyRecognizer.shouldBlockSave(
                KeyRecognition.Ambiguous(listOf(ApiKeyCatalog.byId("openai-compat")!!)),
            ),
        )
    }

    // ── hint 文案 ────────────────────────────────────────────────────

    @Test
    fun `提示里带上厂商名`() {
        val hint = ApiKeyRecognizer.hint(KeyRecognition.Identified(ApiKeyCatalog.byId("breezeblue")!!))
        assertTrue(hint, hint.contains("BreezeBlue"))
    }

    @Test
    fun `不认识的提示必须说可以照常保存`() {
        // 否则用户看到「未识别」会以为存不了，去改一个本来正确的 key。
        val hint = ApiKeyRecognizer.hint(KeyRecognition.Unknown)
        assertTrue(hint, hint.contains("照常保存"))
    }

    @Test
    fun `拒绝的提示就是原因文案`() {
        assertEquals(
            RejectReason.HAS_WHITESPACE.message,
            ApiKeyRecognizer.hint(KeyRecognition.Rejected(RejectReason.HAS_WHITESPACE)),
        )
    }

    // ── 掩码 ─────────────────────────────────────────────────────────

    @Test
    fun `掩码只留头尾`() {
        val masked = ApiKeyRecognizer.maskForDisplay("brz_Y3XIHNbh9kQmvT7pLd2s")
        assertNotNull(masked)
        assertEquals("brz_Y3…Ld2s", masked)
    }

    @Test
    fun `太短的 key 不给掩码`() {
        // 遮完只剩省略号，等于没遮 —— 让调用方自己决定显示什么。
        assertNull(ApiKeyRecognizer.maskForDisplay("sk-abc"))
        assertNull(ApiKeyRecognizer.maskForDisplay("abcdefghijk"))
    }

    // ── rank ────────────────────────────────────────────────────────

    @Test
    fun `rank 把更具体的排前面且可复现`() {
        val broad = ApiKeyCatalog.byId("openai-compat")!!
        val specific = ApiKeyCatalog.byId("anthropic-api03")!!
        val ranked = ApiKeyRecognizer.rank(listOf(broad, specific))
        assertEquals("anthropic-api03", ranked.first().id)
        assertEquals(ranked, ApiKeyRecognizer.rank(listOf(broad, specific)))
    }
}
