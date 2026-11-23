package com.pocketagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketagent.skilllogic.Skill
import com.pocketagent.skilllogic.SkillDoc
import com.pocketagent.skilllogic.SkillSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 独立 DataStore：技能与角色设定、关于你各存各的，互不影响。 */
private val Context.skillStore_: DataStore<Preferences> by preferencesDataStore("skills")

/**
 * **用户导入的工作技能**。
 *
 * ## 为什么只存导入的，不存内置的
 *
 * 内置技能是代码（`BuiltinSkills.all`），存一份到磁盘就有两个真相：
 * 应用升级改了正文，磁盘上的旧正文会盖住新的 —— 而用户完全不知道自己
 * "身上带着一份旧技能"。所以磁盘上只放 [SkillSource.IMPORTED] 的那几条。
 *
 * ## 为什么用一条 JSON 而不是一条一条存
 *
 * 技能最多几十条，读的时候一定全读（要拼清单）。一条一条存会让
 * "打开工作台"变成几十次 DataStore 读取，而它们的更新是原子的（一次导入）。
 * 唯一代价：并发导入要串行 —— 这里由调用方（容器）用 Mutex 保证。
 *
 * ## 坏数据的处理
 *
 * 解码时**逐条**兜住：缺字段、id 不合法、正文超长都跳过那一条，
 * 不整份丢掉。理由：用户导了十条，其中一条手抖写坏了，不该让另外九条消失。
 */
class SkillStore(private val context: Context) {

    suspend fun load(): List<Skill> = context.skillStore_.data
        .map { prefs -> prefs[KEY]?.let { decode(it) } ?: emptyList() }
        .first()

    suspend fun save(skills: List<Skill>) {
        val imported = skills.filter { it.source == SkillSource.IMPORTED }
        context.skillStore_.edit { prefs -> prefs[KEY] = encode(imported) }
    }

    // ── 序列化 ────────────────────────────────────────────────

    private fun encode(skills: List<Skill>): String = buildJsonObject {
        put(
            "skills",
            buildJsonArray {
                for (s in skills) {
                    add(
                        buildJsonObject {
                            put("id", s.id)
                            put("name", s.name)
                            put("summary", s.summary)
                            put("body", s.body)
                            put("origin", s.origin)
                            put(
                                "triggers",
                                buildJsonArray { s.triggers.forEach { add(JsonPrimitive(it)) } },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decode(raw: String): List<Skill> = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val arr = root["skills"]?.jsonArray ?: return emptyList()
        arr.mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                Skill(
                    id = o["id"]!!.jsonPrimitive.content,
                    name = o["name"]!!.jsonPrimitive.content,
                    summary = o["summary"]!!.jsonPrimitive.content,
                    body = SkillDoc.clampBody(o["body"]!!.jsonPrimitive.content),
                    triggers = o["triggers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    origin = o["origin"]?.jsonPrimitive?.content ?: "",
                    source = SkillSource.IMPORTED,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private companion object {
        val KEY = stringPreferencesKey("imported_skills_v1")
    }
}
