package com.pocketagent.personalogic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [ReplyShape] 的判据 —— "像文档"的排版必须被拿掉，且不能误伤正常说话。
 */
class ReplyShapeTest {

    @Test
    fun `加粗会被去掉`() {
        assertThat(ReplyShape.strip("这也**太巧了**吧")).isEqualTo("这也太巧了吧")
    }

    @Test
    fun `小标题只留下标题文字`() {
        assertThat(ReplyShape.strip("## 今天的事\n去了趟菜市场").split("\n").first())
            .isEqualTo("今天的事")
    }

    @Test
    fun `分点记号会被去掉`() {
        val out = ReplyShape.strip("- 买了菜\n- 洗了衣服\n1. 睡了会儿")
        assertThat(out).isEqualTo("买了菜\n洗了衣服\n睡了会儿")
    }

    @Test
    fun `分隔线整行删掉`() {
        assertThat(ReplyShape.strip("先这样\n---\n回头说")).isEqualTo("先这样\n回头说")
    }

    @Test
    fun `代码块原样保留`() {
        val code = "```bash\n# 注释\n- 参数\n```"
        assertThat(ReplyShape.strip(code)).isEqualTo(code)
    }

    @Test
    fun `正常的一句话一个字都不动`() {
        val plain = "刚到家，累死了。你呢？"
        assertThat(ReplyShape.strip(plain)).isEqualTo(plain)
    }

    @Test
    fun `整条都是排版时返回原文，不能洗成空`() {
        val onlyMarks = "---\n**\n"
        assertThat(ReplyShape.strip(onlyMarks)).isEqualTo(onlyMarks)
    }

    @Test
    fun `连着几行空行压成一个`() {
        assertThat(ReplyShape.strip("一\n\n\n\n二")).isEqualTo("一\n\n二")
    }

    @Test
    fun `有没有排版痕迹的判据`() {
        assertThat(ReplyShape.hasFormatting("**重点**")).isTrue()
        assertThat(ReplyShape.hasFormatting("### 标题")).isTrue()
        assertThat(ReplyShape.hasFormatting("- 一条")).isTrue()
        assertThat(ReplyShape.hasFormatting("就是普通说话")).isFalse()
    }

    @Test
    fun `代码块里的井号不算小标题`() {
        assertThat(ReplyShape.hasFormatting("```\n# 这是 shell 注释\n```")).isFalse()
    }
}
