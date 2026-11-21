package com.pocketagent.agentui

/**
 * 一次"看一眼我的屏幕"的结果。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 为什么不是 `ByteArray?`
 * ══════════════════════════════════════════════════════════════
 *
 * 截图失败的原因有五种以上（无障碍没开、页面不适合外传、压缩失败、
 * 体积超限、系统不给录屏），每一种对用户要说的话都不一样。
 * 用 `null` 表示失败，界面只能说"失败了"——用户唯一的反应是再点一次。
 *
 * 所以失败也带一句**人能看懂的原因**，由这里一路传到输入框下方的提示行。
 */
sealed interface ScreenShareResult {
    /** 拿到了像素。失败时 `png` 非空但 `reason` 也非空 —— 见 [Ok.partial]。 */
    data class Ok(val png: ByteArray) : ScreenShareResult {
        /** 字节相等即可 —— `ByteArray` 的默认 equals 是引用比较 */
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 没拿到像素。[reason] 必须能直接显示给人看。 */
    data class No(val reason: String) : ScreenShareResult
}
