package com.pocketagent.hosting

/**
 * **虚拟屏为什么没起来** —— 把五种失败翻成用户能自己动手的一句话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * "在脱离电脑环境时，虚拟屏起不来。我对工作模式里的角色说打开微信然后
 *  发一条关于你的朋友圈，这时候就会执行失败。"
 *
 * 关键在**脱离电脑**：连着电脑时能看 logcat，起不来也知道为什么；
 * 手机在手上的时候只有角色那句话。而原来的失败文案是
 * 「虚拟屏没建起来（见日志）」—— 那句话在没电脑时等于什么都没说。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 分类的价值
 * ═══════════════════════════════════════════════════════════════
 *
 * 五种失败对应的**动作完全不同**：
 *
 * | 类别 | 用户要做什么 |
 * |---|---|
 * | 没有 root | 去运行模式里切到 root 档 / 在 root 管理器里放行 |
 * | 建不了屏 | 重启应用（上一块屏可能没释放干净） |
 * | 找不到入口 | 那个应用可能没装，或被停用了 |
 * | 启动被拒 | 系统拒绝了这次 `am start`，换一个应用先试 |
 * | 没有画面 | 等几秒再 shot —— 冷启动要 3～6 秒 |
 *
 * ⇒ 界面上必须按类给话，而不是统一一句"失败了"。
 */
object HostFailure {

    /** 失败类别。 */
    enum class Kind {
        NO_ROOT,
        NO_DISPLAY,
        NO_ENTRY,
        REJECTED,
        NO_FRAME,
        TEXT_NOT_ASCII,
        UNKNOWN,
    }

    /**
     * 按失败文案分类。
     *
     * ⚠️ 判据是**我们自己写的那几句话**（在 `VirtualScreenHost` / `HostingAdapter`
     *    里生成），不是随机的 shell 输出。所以这里用关键词是安全的；
     *    输出里夹带的英文照旧不看。
     */
    fun classify(raw: String?): Kind {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Kind.UNKNOWN
        return when {
            "su 不可用" in text || "root 命令没返回" in text ||
                "没有 root" in text || "Magisk" in text -> Kind.NO_ROOT
            "没建起来" in text || "createVirtualDisplay" in text -> Kind.NO_DISPLAY
            "没有可启动的入口" in text -> Kind.NO_ENTRY
            "启动失败" in text || "被拒" in text -> Kind.REJECTED
            "没有画面" in text || "还没有画面" in text -> Kind.NO_FRAME
            "ASCII" in text -> Kind.TEXT_NOT_ASCII
            else -> Kind.UNKNOWN
        }
    }

    /** 这一类失败，用户该做什么。 */
    fun advice(kind: Kind): String = when (kind) {
        Kind.NO_ROOT ->
            "虚拟屏要把应用拉到一块独立的屏上，这一步需要 root。" +
                "去「设置 → 运行模式」切到 root 那一档；如果已经是 root 档，" +
                "就在 root 管理器（Magisk / KernelSU / ReSukiSU）里给本应用放行，然后重试。"
        Kind.NO_DISPLAY ->
            "这块屏没建起来 —— 通常是上一次用过之后没释放干净。" +
                "把应用从后台划掉重开一次，再让我试；还是不行就重启手机。"
        Kind.NO_ENTRY ->
            "这个应用没有可启动的入口，可能是没装、被停用了，或者包名不对。" +
                "先确认它在桌面能点开。"
        Kind.REJECTED ->
            "系统拒绝了这次启动。常见原因是这个应用自己不允许被拉到副屏 —— " +
                "换一个应用先试一下，能起来就说明是那个应用的问题。"
        Kind.NO_FRAME ->
            "屏是好的，只是上面还没有画面：应用冷启动通常要 3～6 秒。" +
                "等一下再让我看一眼，不用重新 start。"
        Kind.TEXT_NOT_ASCII ->
            "虚拟屏上的文字是靠系统的 input 命令送进去的，它只认 ASCII（英文和数字）。" +
                "中文得在主屏上用输入法打 —— 或者你把要发的内容给我，我想别的办法。"
        Kind.UNKNOWN ->
            "这一步没成功，但没拿到能定位的原因。把应用从后台划掉重开一次再试；" +
                "连着电脑的话可以看 logcat 里的「虚拟屏」几行。"
    }

    /**
     * 一句话版：原因 + 该做什么。
     *
     * ★ 顺序不能反。先说"发生了什么"，再说"你该做什么" ——
     *   用户读第一句就知道要不要动手。
     */
    fun describe(raw: String?): String {
        val kind = classify(raw)
        val reason = raw?.trim().orEmpty().ifEmpty { "没有拿到原因" }
        return "$reason ↘ ${advice(kind)}"
    }

    /** 这段文字能不能靠 `input text` 送进虚拟屏。 */
    fun isTypeable(text: String): Boolean = text.all { it.code in 0x20..0x7E }

    /**
     * 不能送时的替代做法。
     *
     * ⚠️ 刻意**不**假装成功：`input text` 遇到中文会一个字符都不输入、
     *    也几乎不报错，界面停在原地。模型如果以为"打进去了"，
     *    下一步就会去点"发送"，于是发出一条空的朋友圈。
     */
    fun typingNote(text: String): String? {
        if (isTypeable(text)) return null
        val bad = text.filter { it.code !in 0x20..0x7E }.take(8)
        return "这段文字里有 input 送不进去的字符（$bad），" + advice(Kind.TEXT_NOT_ASCII)
    }
}
