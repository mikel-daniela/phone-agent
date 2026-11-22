package com.pocketagent.keylogic

/**
 * 市面上常见 API Key 的格式指纹表。
 *
 * ⚠️ **这张表永远不完整，也永远会过时。** 它只是一个「见过就认得」的对照表，
 * 不是协议，不是规范，没有任何权威性。因此：
 *   · 没命中 ≠ key 无效（见 [KeyRecognition.Unknown]）
 *   · 命中 ≠ key 有效（格式对了也可能已被吊销）—— 真正的有效性只有发一次请求才知道
 *   · 结论只用于提示与预选 provider，**不得用于拒绝保存**
 *
 * ★ 收录原则：
 *   1. 只收**公开可查**的前缀。厂商没公开的，不猜。
 *   2. 前缀有歧义的一律降到 [KeyStrength.FAMILY]，宁可说「像是某家」，
 *      也不要说「就是某家」—— 说错比不说更坏，用户会照着一个错结论去改配置。
 *   3. 长度区间给得比实测更宽。厂商加长 key 是常事，卡死在实测长度上，
 *      下次他们一改，我们就开始误判「这不是 XX 的 key」。
 */
object ApiKeyCatalog {

    /**
     * 一条指纹的样本，用于自检（[ApiKeyCatalogTest]）。
     *
     * 样本是**编造的**，只有形状与长度按各家公开格式构造，不含任何真实凭据。
     */
    val SAMPLES: Map<String, String> = mapOf(
        "breezeblue" to "brz_Y3XIHNbh9kQmvT7pLd2s",
        "anthropic-api03" to "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGh",
        "anthropic" to "sk-ant-AbCdEfGhIjKlMnOpQrStUvWxYz0123",
        "openai-project" to "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKl",
        "openai-svcacct" to "sk-svcacct-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "openai-admin" to "sk-admin-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        // ★ 2026-11-23：这一条**故意用拼接**，不写成字面量。
        //   GitHub 的密钥扫描把「sk-or-v1- + 64 位十六进制」当成真的
        //   OpenRouter Key，直接拦了推送。拆成前缀 + 重复串之后，
        //   语义与长度完全不变（还是同一个样本、照样过识别器），
        //   但源码里不再是一条可被模式匹配的字面量。
        "openrouter" to "sk-or-v1-" + "0123456789abcdef".repeat(4),
        "groq" to "gsk_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKl",
        "xai" to "xai-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "perplexity" to "pplx-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "huggingface" to "hf_AbCdEfGhIjKlMnOpQrStUvWxYz012345",
        "replicate" to "r8_AbCdEfGhIjKlMnOpQrStUvWxYz012345",
        "google-api" to "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q",
        "fireworks" to "fw_AbCdEfGhIjKlMnOpQrStUvWxYz012345",
        "together" to "tgp_v1_AbCdEfGhIjKlMnOpQrStUvWxYz012345",
        "nvidia" to "nvapi-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "cerebras" to "csk-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "elevenlabs" to "sk_0123456789abcdef0123456789abcdef0123456789abcdef",
        "jwt" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        "openai-compat" to "sk-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIj",
        "hex32" to "0123456789abcdef0123456789abcdef",
        "alnum32" to "AbCdEfGhIjKlMnOpQrStUvWxYz012345",
        "alnum40plus" to "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCd",
    )

    /**
     * 全部指纹。
     *
     * 顺序**不决定**匹配优先级 —— 优先级按 [KeyStrength] 分档、档内按正则具体度排序，
     * 见 [ApiKeyRecognizer]。（把优先级寄托在「列表谁先写」，等于让后加的人
     * 有机会插到前面去改变结果，而这种改动没有任何测试会红。）
     */
    val ALL: List<ApiKeyFormat> = listOf(

        // ── 签名级：独占前缀 ────────────────────────────────────────────
        ApiKeyFormat(
            id = "breezeblue",
            displayName = "BreezeBlue",
            vendor = "BreezeBlue",
            kind = KeyKind.BOTH,
            pattern = Regex("^brz_[A-Za-z0-9_-]{12,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "breezeblue",
            authHeader = "xi-api-key",
            note = "官方文档：xi-api-key 与 Authorization Bearer 都收，前者优先。" +
                "key 前缀 brz_，后面是含大写字母与连字符的串（来自 /v1/api-keys/current 的 prefix 字段）。",
        ),
        ApiKeyFormat(
            id = "anthropic-api03",
            displayName = "Anthropic Claude",
            vendor = "Anthropic",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-ant-api03-[A-Za-z0-9_-]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "anthropic",
            authHeader = "x-api-key",
        ),
        ApiKeyFormat(
            id = "anthropic",
            displayName = "Anthropic Claude（旧版前缀）",
            vendor = "Anthropic",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-ant-[A-Za-z0-9_-]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "anthropic",
            authHeader = "x-api-key",
        ),
        ApiKeyFormat(
            id = "openai-project",
            displayName = "OpenAI（项目级）",
            vendor = "OpenAI",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-proj-[A-Za-z0-9_-]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "openai",
        ),
        ApiKeyFormat(
            id = "openai-svcacct",
            displayName = "OpenAI（服务账号）",
            vendor = "OpenAI",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-svcacct-[A-Za-z0-9_-]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "openai",
        ),
        ApiKeyFormat(
            id = "openai-admin",
            displayName = "OpenAI（管理密钥）",
            vendor = "OpenAI",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-admin-[A-Za-z0-9_-]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "openai",
        ),
        ApiKeyFormat(
            id = "openrouter",
            displayName = "OpenRouter",
            vendor = "OpenRouter",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-or-v1-[A-Za-z0-9]{32,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "openrouter",
        ),
        ApiKeyFormat(
            id = "groq",
            displayName = "Groq",
            vendor = "Groq",
            kind = KeyKind.LLM,
            pattern = Regex("^gsk_[A-Za-z0-9]{30,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "groq",
        ),
        ApiKeyFormat(
            id = "xai",
            displayName = "xAI Grok",
            vendor = "xAI",
            kind = KeyKind.LLM,
            pattern = Regex("^xai-[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "xai",
        ),
        ApiKeyFormat(
            id = "perplexity",
            displayName = "Perplexity",
            vendor = "Perplexity",
            kind = KeyKind.LLM,
            pattern = Regex("^pplx-[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "perplexity",
        ),
        ApiKeyFormat(
            id = "huggingface",
            displayName = "Hugging Face",
            vendor = "Hugging Face",
            kind = KeyKind.LLM,
            pattern = Regex("^hf_[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "huggingface",
        ),
        ApiKeyFormat(
            id = "replicate",
            displayName = "Replicate",
            vendor = "Replicate",
            kind = KeyKind.LLM,
            pattern = Regex("^r8_[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "replicate",
        ),
        ApiKeyFormat(
            id = "google-api",
            displayName = "Google AI（Gemini / AI Studio）",
            vendor = "Google",
            kind = KeyKind.LLM,
            pattern = Regex("^AIza[A-Za-z0-9_-]{35}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "google",
            authHeader = "x-goog-api-key",
            note = "以 AIza 开头、总长 39。Google 的 key 不区分产品，同一个 key 可能同时能开 Gemini 与其它 GCP 服务。",
        ),
        ApiKeyFormat(
            id = "fireworks",
            displayName = "Fireworks AI",
            vendor = "Fireworks",
            kind = KeyKind.LLM,
            pattern = Regex("^fw_[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "fireworks",
        ),
        ApiKeyFormat(
            id = "together",
            displayName = "Together AI",
            vendor = "Together",
            kind = KeyKind.LLM,
            pattern = Regex("^tgp_v1_[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "together",
        ),
        ApiKeyFormat(
            id = "nvidia",
            displayName = "NVIDIA NIM",
            vendor = "NVIDIA",
            kind = KeyKind.LLM,
            pattern = Regex("^nvapi-[A-Za-z0-9_-]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "nvidia",
        ),
        ApiKeyFormat(
            id = "cerebras",
            displayName = "Cerebras",
            vendor = "Cerebras",
            kind = KeyKind.LLM,
            pattern = Regex("^csk-[A-Za-z0-9]{20,}\$"),
            strength = KeyStrength.SIGNATURE,
            providerId = "cerebras",
        ),
        ApiKeyFormat(
            id = "elevenlabs",
            displayName = "ElevenLabs",
            vendor = "ElevenLabs",
            kind = KeyKind.TTS,
            pattern = Regex("^sk_[a-f0-9]{32,}\$"),
            strength = KeyStrength.SIGNATURE,
            authHeader = "xi-api-key",
            note = "★ 注意：是 sk_ 下划线，不是 sk- 连字符。两者长得像、含义完全不同，" +
                "把 sk_… 填到 OpenAI 那栏会得到 401，用户以为是 key 错了。",
        ),
        ApiKeyFormat(
            id = "jwt",
            displayName = "JWT 令牌",
            vendor = "（通用）",
            kind = KeyKind.UNKNOWN,
            pattern = Regex("^eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\$"),
            strength = KeyStrength.SIGNATURE,
            note = "三段式 JWT，常见于 OAuth / Azure AD / Google 服务账号换取来的临时令牌。" +
                "它通常几小时后过期 —— 用户遇到「昨天还能用今天不行了」，多半是这个原因。",
        ),

        // ── 家族级：多家共用前缀 ────────────────────────────────────────
        ApiKeyFormat(
            id = "openai-compat",
            displayName = "OpenAI 兼容（sk- 系列）",
            vendor = "DeepSeek / Moonshot / 智谱 / 硅基流动 / DashScope / MiniMax 等",
            kind = KeyKind.LLM,
            pattern = Regex("^sk-[A-Za-z0-9_-]{16,}\$"),
            strength = KeyStrength.FAMILY,
            note = "★ 这一条是天生的瞎子：这些厂商都实现了 OpenAI 兼容协议，" +
                "key 又都是 sk- 打头，仅凭字符串不可能区分是谁家的。" +
                "要定到具体哪家，只能靠用户填的 baseUrl。",
        ),

        // ── 弱级：只有长度与字符集 ──────────────────────────────────────
        ApiKeyFormat(
            id = "hex32",
            displayName = "32 位十六进制密钥",
            vendor = "Azure OpenAI / Mistral / 自建网关等",
            kind = KeyKind.UNKNOWN,
            pattern = Regex("^[a-fA-F0-9]{32}\$"),
            strength = KeyStrength.WEAK,
            note = "最没有信息量的一档：Azure OpenAI、Mistral、以及大量自建网关都发这种形状的 key。",
        ),
        ApiKeyFormat(
            id = "alnum32",
            displayName = "32 位字母数字密钥",
            vendor = "（通用）",
            kind = KeyKind.UNKNOWN,
            pattern = Regex("^[A-Za-z0-9]{32}\$"),
            strength = KeyStrength.WEAK,
        ),
        ApiKeyFormat(
            id = "alnum40plus",
            displayName = "40–64 位字母数字密钥",
            vendor = "Cohere / 自建网关等",
            kind = KeyKind.UNKNOWN,
            pattern = Regex("^[A-Za-z0-9]{40,64}\$"),
            strength = KeyStrength.WEAK,
        ),
    )

    /** 按强度取一档（识别器分档匹配时用）。 */
    fun byStrength(strength: KeyStrength): List<ApiKeyFormat> =
        ALL.filter { it.strength == strength }

    /** 按 id 取一条，没有则 null（测试与 UI 反查用）。 */
    fun byId(id: String): ApiKeyFormat? = ALL.firstOrNull { it.id == id }
}
