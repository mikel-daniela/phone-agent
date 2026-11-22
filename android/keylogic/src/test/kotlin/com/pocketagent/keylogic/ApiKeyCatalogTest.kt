package com.pocketagent.keylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹表本身的自检。
 *
 * ★ 这张表最大的风险不是「认错」，而是**悄悄退化**：
 *   有人为了加一家新厂商，顺手把某条正则改宽/改窄，或者把某条删了「因为没见过」。
 *   这种改动没有编译错误、也没有行为测试会红（除非刚好有测试用到那一条）。
 *   所以这里钉三类全局不变量：
 *     1. id 唯一（复制粘贴加新条目时最容易撞）
 *     2. 每条都有样本，且样本能匹配自己 —— 改正则改坏了立刻红
 *     3. 正则必须整体锚定 —— 否则 `contains` 语义会让一个 500 字符的 Base64
 *        因为中间碰巧出现 `sk-` 就被认成 OpenAI key
 */
class ApiKeyCatalogTest {

    @Test
    fun `id 不重复`() {
        val ids = ApiKeyCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `每条指纹都有样本`() {
        val missing = ApiKeyCatalog.ALL.map { it.id }.filter { !ApiKeyCatalog.SAMPLES.containsKey(it) }
        assertTrue("这些指纹没有样本：$missing", missing.isEmpty())
    }

    @Test
    fun `样本表里没有多余的条目`() {
        val known = ApiKeyCatalog.ALL.map { it.id }.toSet()
        val orphans = ApiKeyCatalog.SAMPLES.keys.filter { it !in known }
        assertTrue("样本表指向了不存在的指纹：$orphans", orphans.isEmpty())
    }

    @Test
    fun `每条指纹都能匹配自己的样本`() {
        for (format in ApiKeyCatalog.ALL) {
            val sample = ApiKeyCatalog.SAMPLES.getValue(format.id)
            assertTrue("${format.id} 无法匹配自己的样本 $sample", format.matches(sample))
        }
    }

    @Test
    fun `样本互不相同`() {
        // 两条指纹共用一个样本，说明其中一条的样本是抄来的，自检等于没检。
        val values = ApiKeyCatalog.SAMPLES.values.toList()
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `正则以定界符锚定`() {
        for (format in ApiKeyCatalog.ALL) {
            val body = format.pattern.pattern
            assertTrue("${format.id} 的应以 ^ 开头", body.startsWith("^"))
            assertTrue("${format.id} 的应以 \$ 结尾", body.endsWith("\$"))
        }
    }

    @Test
    fun `展示字段不为空`() {
        for (format in ApiKeyCatalog.ALL) {
            assertTrue(format.id, format.id.isNotBlank())
            assertTrue(format.id, format.displayName.isNotBlank())
            assertTrue(format.id, format.vendor.isNotBlank())
        }
    }

    @Test
    fun `providerId 给就必须是有效标识`() {
        for (format in ApiKeyCatalog.ALL) {
            val providerId = format.providerId ?: continue
            assertTrue("${format.id} 的 providerId 有空白", providerId.none { it.isWhitespace() })
            assertEquals("${format.id} 的 providerId 应小写", providerId.lowercase(), providerId)
        }
    }

    @Test
    fun `覆盖度不低于二十条`() {
        // ★ 这条不是凑数：用户要的是「覆盖市面上大多数」，
        //   而一条指纹被删掉时没有任何东西会红 —— 除了这条下限。
        assertTrue("当前只有 ${ApiKeyCatalog.ALL.size} 条", ApiKeyCatalog.ALL.size >= 20)
    }

    @Test
    fun `三档强度都有内容`() {
        for (strength in KeyStrength.values()) {
            assertTrue("$strength 一个都没有", ApiKeyCatalog.byStrength(strength).isNotEmpty())
        }
    }

    @Test
    fun `按 id 能取回`() {
        val format = ApiKeyCatalog.byId("breezeblue")
        assertNotNull(format)
        assertEquals("BreezeBlue", format!!.displayName)
    }

    @Test
    fun `取不存在的 id 返回 null`() {
        assertEquals(null, ApiKeyCatalog.byId("nope"))
    }

    @Test
    fun `每条样本都不像网址也不含空白`() {
        // 样本是给「识别得出来」用的；如果它自己都过不了输入检查，
        // 那条自检就是假的（recognize 会在匹配阶段之前就把它拒了）。
        for ((id, sample) in ApiKeyCatalog.SAMPLES) {
            assertTrue("$id 的样本含空白", sample.none { it.isWhitespace() })
            assertTrue("$id 的样本像网址", !sample.contains("://"))
        }
    }
}
