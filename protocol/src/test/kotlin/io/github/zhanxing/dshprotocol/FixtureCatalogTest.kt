package io.github.zhanxing.dshprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureCatalogTest {
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
            assertFalse("$name leaks a host path", text.contains("C:\\\\Users"))
            assertFalse("$name leaks a token query", token.containsMatchIn(text))
        }
    }
}
