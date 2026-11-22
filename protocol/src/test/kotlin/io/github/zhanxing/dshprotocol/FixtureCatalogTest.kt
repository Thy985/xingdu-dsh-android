package io.github.zhanxing.dshprotocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureCatalogTest {
    /**
     * Any drive-letter absolute path by shape, not by example: colon raw (`D:`)
     * or encoded (`D%3A`), separator raw (`\` / `/`) or encoded (`%5C` / `%2F`),
     * in any mix and repetition (`D:\`, `D:\\\\`, `D:/`, `D%3A%5C`, `D%3A%2F`,
     * `D:%5C`, `D:%2F`, `D%3A\\`).
     */
    private val drivePath = Regex(
        """(?i)(?<![A-Za-z0-9])(?:[A-Z]%3A|[A-Z]:)(?:[\\/]|%5C|%2F){1,4}""",
    )

    /** Host skill catalogs describe the maintainer's machine, never the protocol. */
    private val skillCatalogMarkers = listOf(
        "<available_skills>",
        "A skill is a reusable set of task-specific instructions",
    )

    private val skillEntryPlaceholder = "<SKILL_ENTRY_REDACTED>"

    @Test
    fun manifestPinsBundledTarget() {
        assertEquals("0.2.1-rc.2", Fixtures.target)
        assertEquals(DSH_PROTOCOL_TARGET, Fixtures.target)
    }

    @Test
    fun allManifestFixturesExistAndParse() {
        assertTrue("expected the 31 M0 fixtures", Fixtures.names.size >= 31)
        Fixtures.names.forEach { name ->
            val text = Fixtures.read(name)
            assertTrue("$name is empty", text.isNotBlank())
            Json.parse(text)
        }
    }

    @Test
    fun fixturesContainNoSecretsOrHostPaths() {
        val token = Regex("token=[A-Za-z0-9]{8,}")
        Fixtures.names.forEach { name ->
            val text = Fixtures.read(name)
            assertFalse("$name leaks an API key", text.contains("sk-"))
            assertFalse("$name leaks the key env name", text.contains("QINIU_API_KEY"))
            assertFalse("$name leaks a token query", token.containsMatchIn(text))
            assertFalse(
                "$name leaks a drive-absolute host path: ${firstMatch(drivePath, text)}",
                drivePath.containsMatchIn(text),
            )
            skillCatalogMarkers.forEach { marker ->
                assertFalse("$name leaks a host skill catalog ($marker)", text.contains(marker))
            }
            assertFalse(
                "$name leaks undecanted skill entries",
                leakedSkillEntries(Json.parse(text)),
            )
        }
    }

    /** Skill catalogs are nested under `source.entries` and any similarly shaped list. */
    private fun leakedSkillEntries(element: JsonElement): Boolean = when {
        element.isJsonArray -> element.asJsonArray.any { leakedSkillEntries(it) }
        element.isJsonObject -> element.asJsonObject.let { obj ->
            val entries = obj.get("entries")
            (entries is JsonArray && entries.any { entryLeaks(it) }) ||
                obj.entrySet().any { leakedSkillEntries(it.value) }
        }

        else -> false
    }

    private fun entryLeaks(element: JsonElement): Boolean {
        if (!element.isJsonObject) return false
        val obj = element.asJsonObject
        return listOf("name", "description").any { key ->
            val value = obj.get(key)
            value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                value.asString != skillEntryPlaceholder
        }
    }

    private fun firstMatch(regex: Regex, text: String): String =
        regex.find(text)?.value ?: ""
}
