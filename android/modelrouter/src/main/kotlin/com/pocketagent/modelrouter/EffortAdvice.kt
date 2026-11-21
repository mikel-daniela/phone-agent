package com.pocketagent.modelrouter

/**
 * **这一轮对话是什么场景** —— 只用于挑思考强度，不参与选模型。
 *
 * ⚠️ 与 [Scenario] 的区别：那个是"要不要动手"（CHAT/AGENT），
 * 这个是"快还是要准"。两件事会交叉 —— 打电话属于 CHAT，
 * 但它比任何 AGENT 都更需要快。
 */
enum class EffortScene(val label: String) {

    /** 打字聊天。 */
    CHAT("聊天"),

    /** 语音通话 —— 延迟最刺眼的场景。 */
    CALL("通话"),

    /** 带一屏截图的一轮（用户点「屏幕共享」，或模型要看图）。 */
    SCREEN_SHARE("屏幕共享"),
    ;

    companion object {

        /**
         * 把"这一轮带不带图"翻译成场景。
         *
         * ⚠️ 不在这里猜"图是不是截图" —— 调用方知道来源，
         *    它自己传 [SCREEN_SHARE]。这里只处理"没有来源信息的图"。
         */
        fun of(hasImage: Boolean): EffortScene = if (hasImage) SCREEN_SHARE else CHAT
    }
}

/**
 * **推荐的思考强度** —— 用户没改过任何东西时，各场景该用哪一档。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-21：用户要求
 * ═══════════════════════════════════════════════════════════════
 *
 * 原话：
 * > "打电话的时候为了提高响应速度，全部模型无论是什么全部默认是
 * >  最低的思考等级 …… 屏幕共享默认是中等的思考等级，其实这些都是
 * >  可以让用户自己去修改的，但我们需要有一个推荐的思考等级进去"
 *
 * ⇒ 本文件就是那个"推荐"，它是**数据**不是文案：
 *   界面可以显示它、容器可以按它兜底，但用户一改就以用户的为准
 *   （见 `AppContainer.effortFor`）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么屏幕共享是 [ThinkingEffort.HIGH] 而不是 LOW
 * ═══════════════════════════════════════════════════════════════
 *
 * 这一档的 [ThinkingEffort.wireValue] 就是 `"medium"` —— 也就是
 * 厂商侧的"中等思考"。而我们自己的四档里，`HIGH` 正好落在中间偏上：
 *
 * ```
 *  off ── low ── high ── max          ← 界面上的四档
 *   │      │      │       │
 *  null  "low" "medium" "high"        ← 真正发出去的 reasoning_effort
 * ```
 *
 * ⇒ 用户说的"中等"在协议层就是 `medium`，在这套四档里就是 `HIGH`。
 *   写成 `LOW` 会让屏幕共享比聊天还省，而看图恰恰是需要多想的那个。
 */
object EffortAdvice {

    /** 所有场景的默认档 —— **最低**。 */
    val DEFAULT: ThinkingEffort = ThinkingEffort.OFF

    /**
     * 这个场景推荐几档。
     *
     * · 聊天 → OFF：闲聊不需要推理链，首字延迟最要紧。
     * · 通话 → OFF：它比聊天还急（用户是"在对话"，不是"在等答案"）。
     * · 屏幕共享 → HIGH（= 厂商侧 `medium`）：要读懂一屏界面，
     *   但也不必上 MAX —— 那会让"看一眼"变成"想半天"。
     */
    fun recommended(scene: EffortScene): ThinkingEffort = when (scene) {
        EffortScene.CHAT -> ThinkingEffort.OFF
        EffortScene.CALL -> ThinkingEffort.OFF
        EffortScene.SCREEN_SHARE -> ThinkingEffort.HIGH
    }

    /** 为什么是这个档 —— 一句话，可直接显示。 */
    fun why(scene: EffortScene): String = when (scene) {
        EffortScene.CHAT -> "闲聊不需要推理链，首字快更重要"
        EffortScene.CALL -> "通话时你说完就在等它开口，多一秒都很明显"
        EffortScene.SCREEN_SHARE -> "要读懂一屏界面，中等思考刚好；再高就变成「看一眼要想半天」"
    }

    /**
     * 用户的选择与推荐**取哪个**。
     *
     * ⚠️ 规则只有一条：**用户显式选过就用用户的**（[userChoice] 非空即视为选过）。
     *    推荐值只负责"用户没表态时"的默认，不能反过来盖掉用户。
     */
    fun resolve(scene: EffortScene, userChoice: ThinkingEffort?): ThinkingEffort =
        userChoice ?: recommended(scene)

    /**
     * 界面上那一行建议文案。
     *
     * ⚠️ 用户已经选了和推荐一样的档时就不要重复劝 —— 那句"推荐 off"
     *    摆在那里会像个没生效的提示。只在**不一致**时给理由。
     */
    fun note(scene: EffortScene, current: ThinkingEffort): String {
        val want = recommended(scene)
        return if (current == want) {
            "推荐 ${want.label}：${why(scene)}"
        } else {
            "这一档比推荐值（${want.label}）更${if (current.ordinal > want.ordinal) "费" else "省"}；" +
                why(scene)
        }
    }
}
