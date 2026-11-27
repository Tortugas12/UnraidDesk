package com.unraiddesk.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class GraphQlRequest(
    val query: String,
    val variables: JsonObject? = null
)

@Serializable
data class GraphQlError(
    val message: String = "",
    val path: List<JsonElement> = emptyList()
)

@Serializable
data class GraphQlResponse(
    val data: JsonObject? = null,
    val errors: List<GraphQlError> = emptyList()
)

enum class DockerRootVariant {
    DOCKER_OBJ,
    DOCKER_CONTAINERS
}

@Serializable
data class ServerInfo(
    val platform: String = "",
    val distro: String = "",
    val release: String = "",
    val uptime: String = "",
    val cpuManufacturer: String = "",
    val cpuBrand: String = "",
    val cpuCores: Int = 0,
    val cpuThreads: Int = 0
)

@Serializable
data class ServerMetrics(
    val cpuPercent: Double = 0.0,
    val ramPercent: Double = 0.0,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 0L
)

@Serializable
data class ArrayDisk(
    val id: String = "",
    val name: String = "",
    val status: String = "",
    val tempCelsius: Int? = null,
    val sizeBytes: Long = 0L,
    val numReads: Long = 0L,
    val numWrites: Long = 0L,
    val isCache: Boolean = false
)

@Serializable
data class ParityCheckStatus(
    val status: String = "UNKNOWN",
    val progressPercent: Double = 0.0
)

@Serializable
data class ArrayStatus(
    val state: String = "UNKNOWN",
    val usedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val parityCheckStatus: ParityCheckStatus = ParityCheckStatus(),
    val disks: List<ArrayDisk> = emptyList(),
    val caches: List<ArrayDisk> = emptyList()
)

@Serializable
data class DashboardSnapshot(
    val info: ServerInfo = ServerInfo(),
    val metrics: ServerMetrics = ServerMetrics(),
    val array: ArrayStatus = ArrayStatus(),
    val warnings: List<String> = emptyList()
)

@Serializable
data class UnraidShare(
    val name: String,
    val comment: String = "",
    val sizeBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val cache: String = ""
)

@Serializable
data class PortMapping(
    val privatePort: Int = 0,
    val publicPort: Int? = null,
    val type: String = "tcp"
)

@Serializable
data class DockerContainer(
    val id: String,
    val name: String,
    val image: String = "",
    val state: String = "UNKNOWN",
    val status: String = "",
    val autoStart: Boolean = false,
    val webUiUrl: String? = null,
    val ports: List<PortMapping> = emptyList(),
    val labels: Map<String, String> = emptyMap()
) {
    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true)
}

/**
 * Parses Unraid `net.unraid.docker.webui` label templates such as
 * `http://[IP]:[PORT:8080]/` by substituting `[IP]` with [serverHost]
 * and `[PORT:xxxx]` with the mapped host port from [ports] (falling back to `xxxx`).
 */
fun parseDockerWebUiUrl(
    rawTemplate: String?,
    serverHost: String,
    ports: List<PortMapping>
): String? {
    val template = rawTemplate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    var resolved = template.replace("[IP]", serverHost, ignoreCase = true)
    val portRegex = Regex("\\[PORT:(\\d+)]", RegexOption.IGNORE_CASE)
    resolved = portRegex.replace(resolved) { match ->
        val privatePort = match.groupValues[1].toIntOrNull()
        val mapped = if (privatePort != null) {
            ports.firstOrNull { it.privatePort == privatePort }?.publicPort
        } else {
            null
        }
        (mapped ?: privatePort ?: match.groupValues[1]).toString()
    }
    return resolved
}

internal object GraphQlJsonExtractor {
    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonObject.str(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
        val prim = this[key] as? JsonPrimitive ?: return default
        return prim.contentOrNull?.toBooleanStrictOrNull() ?: default
    }

    fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

    fun JsonObject.long(key: String, default: Long = 0L): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: default

    fun JsonObject.double(key: String, default: Double = 0.0): Double =
        (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: default

    fun JsonObject.stringList(key: String): List<String> {
        return when (val element = this[key]) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            is JsonPrimitive -> element.contentOrNull
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            else -> emptyList()
        }
    }

    fun parseLabels(element: JsonElement?): Map<String, String> {
        return when (element) {
            is JsonObject -> element.entries.mapNotNull { (k, v) ->
                val value = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                k to value
            }.toMap()
            is JsonArray -> element.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val key = obj.str("key").ifEmpty { obj.str("name") }
                val value = obj.str("value")
                if (key.isNotEmpty()) key to value else null
            }.toMap()
            else -> emptyMap()
        }
    }
}
