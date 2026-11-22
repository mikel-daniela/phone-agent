package com.pocketagent.personalogic

/**
 * **环境小事**（导演写的 `smallMoments`）—— 只改这一句的写法，不改状态。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-11-21：这个类是为了补一条"建好了没接线"
 * ═══════════════════════════════════════════════════════════════
 *
 * `DirectorOutput.smallMoments` 从 2026-11-03 就有了：导演让它写
 * "窗外在下雨"「粥在锅里咕嘟」这种**氛围小事**，`DirectorGate` 也做了
 * 过滤（最多 2 条）。但 `AppContainer` 收下之后**一句都没用过** ——
 * 用户提的"世界书 AI 会根据世界书和当前角色加入随机性的大小事件"，
 * 大小事件里"小事件"这半边从来没到过前台模型。
 *
 * ⚠️ 与 [PerformanceSlot] 的分工：
 *   · `performance` 说「**这一句怎么演**」（动作指导，一句话）；
 *   · 这个说「**这会儿周围是什么样**」（环境，0..2 句）。
 *    两者都会影响这一句的写法，但一个在语气上、一个在内容上。
 *
 * ⚠️ 它**不进状态**：`DirectorOutput` 的注释写得很清楚 ——
 *    "混在一起会让「窗外下雨」变成「所以她很抑郁」"。
 *    所以这里只存文本，不碰激素、不碰 `PersonaState`。
 *
 * @param clock 时钟（毫秒）。测试注入，生产用 `System.currentTimeMillis`。
 */
class MomentSlot(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 有效期。
     *
     * ★ 比 [Performance.TTL_MS]（20 分钟）短：表演指导是"这轮该怎么接话"，
     *   可以跨几轮；"窗外在下雨"超过十分钟还在说，就变成角色卡在原地了。
     *   导演每 3 轮跑一次，15 分钟足够覆盖它下一次给的时机。
     */
    @Volatile
    private var value: Moment? = null

    /** 当前有效的那批；过期返回 null。 */
    fun current(): Moment? = value?.takeIf { it.isFresh(clock()) }

    /** 当前有效的文案（0..2 条，已去空白）。过期就是空列表。 */
    fun currentLines(): List<String> = current()?.lines.orEmpty()

    /**
     * 收一批新的。
     *
     * @return 是否真的换了 —— 空列表**不会**清掉旧的（"这轮没大事"是常态，
     *   不该因此让"窗外在下雨"立刻消失）。
     */
    fun offer(lines: List<String>, nowMs: Long = clock()): Boolean {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES)
        if (cleaned.isEmpty()) return false
        value = Moment(lines = cleaned, atMs = nowMs)
        return true
    }

    /** 未判有效期的原始持有值。**只给日志与测试用。** */
    fun raw(): Moment? = value

    /** 清空（换人格 / 换会话）。 */
    fun clear() {
        value = null
    }

    companion object {
        const val MAX_LINES = 2
        const val TTL_MS = 15 * 60 * 1000L
    }
}

/** 一批环境小事。 */
data class Moment(
    val lines: List<String>,
    val atMs: Long,
) {
    fun isFresh(nowMs: Long): Boolean = (nowMs - atMs) in 0..MomentSlot.TTL_MS
}
