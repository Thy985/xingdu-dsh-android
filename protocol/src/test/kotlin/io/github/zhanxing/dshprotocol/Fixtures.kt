package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject

/** Loads the M0 contract fixtures from protocol/fixtures (test resource root). */
internal object Fixtures {
    private val manifest: JsonObject by lazy { Json.parseObject(read("manifest")) }

    val names: List<String> by lazy {
        manifest.getAsJsonArray("fixtures").map { it.asString }
    }

    val target: String by lazy { manifest.get("DSH_PROTOCOL_TARGET").asString }

    fun read(name: String): String {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream("$name.json")
            ?: error("missing fixture resource: $name.json")
        return stream.use { it.reader(Charsets.UTF_8).readText() }
    }

    fun json(name: String): JsonObject = Json.parseObject(read(name))

    fun itemValue(name: String): JsonObject = json(name).getAsJsonObject("value")

    fun event(name: String): JsonObject = itemValue(name).getAsJsonObject("event")
}
