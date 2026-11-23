package com.pocketagent.skilllogic

/**
 * 从一句人话里判断"他要用哪个技能"。
 *
 * ## 为什么是评分而不是"关键词命中即触发"
 *
 * 关键词命中太脆：`做一个表` 里既有"表"也有"做"，而 `别做表了` 同样命中。
 * 评分至少能做三件事：
 * 1. 长词比短词值钱（「幻灯片」比「表」更确定）
 * 2. 多个词同时出现时叠加（「做个 ppt 汇报」= 两个词，比只说「汇报」更确定）
 * 3. 不够分就**不触发** —— 宁可不加技能，也不要把不相干的正文塞进提示词
 *
 * ## 为什么不做同义词表 / 向量
 *
 * 同义词表永远补不完，向量要联网或带模型。这里的目标不是"理解"，是
 * **在明显的时候不出错**。判错的代价不对称：漏触发只是产出普通一点，
 * 误触发会让模型对着"会议纪要"的规则去写一封邮件。
 *
 * @see Skill.triggers
 */
object SkillMatch {

    /**
     * 触发门槛。
     *
     * 一条 2 字词值 5 分（`3 + 2`）—— 也就是说**单独一个短词就能触发**，
     * 这是刻意的：「做个表格」这种话里没有第二个线索，不触发就永远用不上。
     * 门槛设在 5 而不是更高，是因为漏触发的代价（产出平庸）远大于误触发
     * （多几百字提示词，而用户已经开了"token 不限量"）。
     */
    const val MIN_SCORE = 5

    /** 单词最高得分，防止一条超长触发词把分数撑爆。 */
    private const val MAX_TRIGGER_SCORE = 12

    /** 技能名整段出现在句子里时的加权。名字是最强的意图信号。 */
    private const val NAME_SCORE = 8

    data class Hit(
        val skill: Skill,
        val score: Int,
        /** 命中的词，按出现顺序。用于日志与"为什么选它"。 */
        val hits: List<String>,
    )

    /**
     * 排序后的候选。**同分按 [skills] 的原始顺序**（内置技能的顺序是刻意排的），
     * 不按 id 排 —— id 排序会让「整理表格」意外压过「做演示文稿」。
     */
    fun rank(text: String, skills: List<Skill> = BuiltinSkills.all, limit: Int = 3): List<Hit> {
        if (text.isBlank() || skills.isEmpty() || limit <= 0) return emptyList()
        val haystack = text.lowercase()
        val scored = ArrayList<Hit>(skills.size)
        for (skill in skills) {
            val hits = ArrayList<String>(skill.triggers.size)
            var score = 0
            for (trigger in skill.triggers) {
                val needle = trigger.lowercase()
                if (needle.isNotEmpty() && haystack.contains(needle)) {
                    hits += trigger
                    score += minOf(MAX_TRIGGER_SCORE, 3 + trigger.length)
                }
            }
            if (haystack.contains(skill.name.lowercase())) {
                hits += skill.name
                score += NAME_SCORE
            }
            if (score >= MIN_SCORE && hits.isNotEmpty()) {
                scored += Hit(skill = skill, score = score, hits = hits)
            }
        }
        // sortedByDescending 是**稳定**的：同分保持原始顺序。
        return scored.sortedByDescending { it.score }.take(limit)
    }

    /** 最强的一条，或 null。 */
    fun best(text: String, skills: List<Skill> = BuiltinSkills.all): Hit? =
        rank(text, skills, limit = 1).firstOrNull()

    /**
     * 两条候选分数接近时（差距 ≤ 2）判为"说不准"，返回 null。
     *
     * ★ 这一条防的是「做个 ppt 汇报」这种同时命中两个技能的话：
     *   把两个技能的正文都塞进去会互相打架（一个说"页数少讲透"，一个说"三段以内"）。
     *   宁可都不加，让模型按通用习惯做。
     */
    fun confident(text: String, skills: List<Skill> = BuiltinSkills.all): Hit? {
        val top = rank(text, skills, limit = 2)
        if (top.isEmpty()) return null
        if (top.size >= 2 && top[0].score - top[1].score <= 2) return null
        return top[0]
    }
}
