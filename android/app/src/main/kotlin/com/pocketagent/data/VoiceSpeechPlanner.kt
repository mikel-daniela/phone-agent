package com.pocketagent.data

import com.pocketagent.provider.api.SpeechRequest
import com.pocketagent.provider.ttsopenai.TtsProfile
import com.pocketagent.voicelogic.ModelRules
import com.pocketagent.voicelogic.ModelRulesResolver
import com.pocketagent.voicelogic.RuleHint
import com.pocketagent.voicelogic.RuleSource
import com.pocketagent.voicelogic.VoiceCapabilities
import com.pocketagent.voicelogic.VoiceRender
import com.pocketagent.voicelogic.VoiceTagParser
import com.pocketagent.voicelogic.VoiceTagRenderer

/**
 * **从"她说了这句话"到"该发什么语音请求"**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里只做搬运，不做判断
 * ═══════════════════════════════════════════════════════════════
 *
 * 判断全在 `:voicelogic`：
 *  · 哪些方括号是标签 —— [VoiceTagParser]
 *  · 这个厂商能表达几层 —— [VoiceTagRenderer.chooseMode]
 *  · 上限从哪知道的 —— [ModelRulesResolver]
 *
 * 放在 app 侧的理由是它需要同时看到"厂商配置"和"纯逻辑"这两边，
 * 而 `:voicelogic` 刻意不依赖 `:provider:api`（那是给网络层用的契约，
 * 一旦互相依赖，纯逻辑模块就跑不进离线验证器了）。
 *
 * ⚠️ 这一层**没有**写进任何日志的正文：音频是最高敏感的一类数据，
 *    连"她说了什么"都不该进 logcat。
 */
object VoiceSpeechPlanner {

    /**
     * 把厂商配置翻译成渲染器认识的能力表。
     *
     * ⚠️ `inlineTokens` 直接来自 profile，不在这里"补几个常用的"：
     *    多给一个不支持的标记，用户听到的是逐字念出来的方括号。
     */
    fun capabilitiesOf(profile: TtsProfile, modelId: String? = null): VoiceCapabilities =
        VoiceCapabilities(
            instructions = profile.supportsInstructions,
            // 协议层没有 SSML 这一位 —— 现有两家都不支持，
            // 将来要开必须在 profile 里明写，不靠猜。
            ssml = false,
            inlineTokens = profile.inlineTokens,
            maxCharacters = profile.maxCharacters,
        )

    /**
     * 这套模型的使用规则。
     *
     * @param stored 上次成功请求时记下的线索（可空）。
     */
    fun rulesOf(profile: TtsProfile, modelId: String? = null, stored: RuleHint? = null): ModelRules {
        val hints = buildList {
            // 内置规则：厂商文档写的（可能过时）
            add(
                RuleHint(
                    source = RuleSource.BUILTIN,
                    maxCharacters = profile.maxCharacters,
                    instructions = profile.supportsInstructions,
                    ssml = false,
                    inlineTokens = profile.inlineTokens,
                ),
            )
            if (stored != null) add(stored)
        }
        return ModelRulesResolver.resolve(modelId ?: profile.defaultModel, hints)
    }

    /**
     * 生成一个可以直接发出去的语音请求。
     *
     * @param reply 模型刚说出来的那句话，**含方括号标签的原样**
     * @param rate  用户设的语速；null 用厂商默认
     */
    fun plan(
        reply: String,
        profile: TtsProfile,
        modelId: String? = null,
        stored: RuleHint? = null,
        voiceId: String? = null,
        rate: Double? = null,
    ): SpeechPlan {
        val rules = rulesOf(profile, modelId, stored)
        val parsed = VoiceTagParser.split(reply)
        val render = VoiceTagRenderer.render(
            spoken = parsed.spoken,
            tags = parsed.tags,
            placements = parsed.placements,
            capabilities = rules.capabilities,
        )

        val request = SpeechRequest(
            // ★ 发出去的是**渲染后**的文本：
            //   SSML 档带 <speak>/<break>，内嵌档带 [uv_break]，
            //   纯文本档就是干净原文。三种都不含我们自己的方括号标签。
            text = render.text,
            voiceId = voiceId,
            rate = rate,
            // ⚠️ 说明只在支持时说。不支持时塞进去会被念出来。
            instructions = render.instructions,
        )

        return SpeechPlan(request = request, render = render, rules = rules)
    }

    /**
     * 超长时切成几条请求。
     *
     * ⚠️ 按**渲染后**的长度切，不是原文长度：
     *    SSML 的一个 `<break time="350ms"/>` 是 19 个字符，
     *    十几个停顿就吃掉几百字的额度。
     * ⚠️ 切段会丢掉标签位置信息 —— 这是有意的：
     *    宁可第二段语气平一点，也不要为了对齐位置把一句话劈成两半念。
     */
    fun splitRequests(plan: SpeechPlan): List<SpeechRequest> {
        val limit = plan.rules.maxCharacters
        if (plan.request.text.length <= limit) return listOf(plan.request)

        return VoiceTagRenderer.splitByLimit(plan.request.text, limit).mapIndexed { index, piece ->
            plan.request.copy(
                text = piece,
                // 说明只跟第一段走 —— 每段都发一遍会让整通朗读的语调被反复重置。
                instructions = if (index == 0) plan.request.instructions else null,
            )
        }
    }
}

/** 一次语音请求的完整决策，连同"降级到了哪一档"一起交出去。 */
data class SpeechPlan(
    val request: SpeechRequest,
    val render: VoiceRender,
    val rules: ModelRules,
)
