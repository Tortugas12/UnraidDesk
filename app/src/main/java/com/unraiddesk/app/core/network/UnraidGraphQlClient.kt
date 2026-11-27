package com.unraiddesk.app.core.network

import com.unraiddesk.app.core.network.GraphQlJsonExtractor.arr
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.bool
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.double
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.intOrNull
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.long
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.obj
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.str
import com.unraiddesk.app.core.network.GraphQlJsonExtractor.stringList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Schema-resilient Unraid 7.2–7.4 GraphQL client using lightweight HTTP POST polling.
 */
class UnraidGraphQlClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) {
    class RateLimitedException(val retryAfterSeconds: Int?) :
        IOException("Unraid API rate limit exceeded (HTTP 429).")

    class UnauthorizedException(statusCode: Int) :
        IOException("Unraid API key rejected (HTTP $statusCode). Re-authorize in Server Settings.")

    class GraphQlApiException(message: String) : IOException(message)

    suspend fun probeDockerRoot(baseUrl: String, apiKey: String): DockerRootVariant {
        val probeQuery = "query { docker { containers { id } } }"
        val response = executeGraphQl(baseUrl, apiKey, probeQuery)
        val hasDockerObj = response.data?.obj("docker")?.arr("containers") != null
        if (hasDockerObj) {
            return DockerRootVariant.DOCKER_OBJ
        }

        val fallbackQuery = "query { dockerContainers { id } }"
        val fallbackResponse = executeGraphQl(baseUrl, apiKey, fallbackQuery)
        if (fallbackResponse.data?.arr("dockerContainers") != null) {
            return DockerRootVariant.DOCKER_CONTAINERS
        }

        val errorMsg = (response.errors + fallbackResponse.errors)
            .firstOrNull { it.message.isNotBlank() }
            ?.message
            ?: "Unable to detect Unraid Docker GraphQL root."
        throw GraphQlApiException(errorMsg)
    }

    suspend fun fetchDashboard(baseUrl: String, apiKey: String): DashboardSnapshot {
        val response = executeGraphQl(baseUrl, apiKey, DASHBOARD_QUERY)
        val data = response.data ?: throw GraphQlApiException(
            response.errors.firstOrNull()?.message ?: "Empty dashboard response."
        )
        val warnings = response.errors.mapNotNull { it.message.takeIf(String::isNotBlank) }

        val infoObj = data.obj("info")
        val osObj = infoObj?.obj("os")
        val cpuObj = infoObj?.obj("cpu")
        val serverInfo = ServerInfo(
            platform = osObj?.str("platform").orEmpty(),
            distro = osObj?.str("distro").orEmpty(),
            release = osObj?.str("release").orEmpty(),
            uptime = osObj?.str("uptime").orEmpty(),
            cpuManufacturer = cpuObj?.str("manufacturer").orEmpty(),
            cpuBrand = cpuObj?.str("brand").orEmpty(),
            cpuCores = cpuObj?.intOrNull("cores") ?: 0,
            cpuThreads = cpuObj?.intOrNull("threads") ?: 0
        )

        val metricsObj = data.obj("metrics")
        val cpuMetrics = metricsObj?.obj("cpu")
        val memMetrics = metricsObj?.obj("memory")
        val serverMetrics = ServerMetrics(
            cpuPercent = cpuMetrics?.double("percentTotal")
                ?: metricsObj?.double("cpuPercent")
                ?: 0.0,
            ramPercent = memMetrics?.double("percentTotal")
                ?: metricsObj?.double("memoryPercent")
                ?: 0.0,
            ramUsedBytes = memMetrics?.long("used") ?: 0L,
            ramTotalBytes = memMetrics?.long("total") ?: 0L
        )

        val arrayObj = data.obj("array")
        val capacityObj = arrayObj?.obj("capacity")
        val capKilobytes = capacityObj?.obj("kilobytes")
        val capDisks = capacityObj?.obj("disks")
        val usedBytes = if (capKilobytes != null) {
            capKilobytes.long("used") * 1024L
        } else {
            capDisks?.long("used") ?: 0L
        }
        val freeBytes = if (capKilobytes != null) {
            capKilobytes.long("free") * 1024L
        } else {
            capDisks?.long("free") ?: 0L
        }
        val totalBytes = if (capKilobytes != null) {
            capKilobytes.long("total") * 1024L
        } else {
            capDisks?.long("total") ?: 0L
        }
        val parityObj = arrayObj?.obj("parityCheckStatus")
        val disks = parseDiskList(arrayObj?.arr("disks"), isCache = false)
        val caches = parseDiskList(arrayObj?.arr("caches"), isCache = true)

        val arrayStatus = ArrayStatus(
            state = arrayObj?.str("state", "UNKNOWN") ?: "UNKNOWN",
            usedBytes = usedBytes,
            freeBytes = freeBytes,
            totalBytes = totalBytes,
            parityCheckStatus = ParityCheckStatus(
                status = parityObj?.str("status", "UNKNOWN") ?: "UNKNOWN",
                progressPercent = parityObj?.double("progress") ?: 0.0
            ),
            disks = disks,
            caches = caches
        )

        return DashboardSnapshot(
            info = serverInfo,
            metrics = serverMetrics,
            array = arrayStatus,
            warnings = warnings
        )
    }

    suspend fun fetchShares(baseUrl: String, apiKey: String): List<UnraidShare> {
        val response = executeGraphQl(baseUrl, apiKey, SHARES_QUERY)
        val data = response.data ?: throw GraphQlApiException(
            response.errors.firstOrNull()?.message ?: "Empty shares response."
        )
        val sharesArray = data.arr("shares") ?: return emptyList()
        return sharesArray.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name")
            if (name.isEmpty()) return@mapNotNull null
            UnraidShare(
                name = name,
                comment = obj.str("comment"),
                sizeBytes = obj.long("size"),
                freeBytes = obj.long("free"),
                usedBytes = obj.long("used"),
                include = obj.stringList("include"),
                exclude = obj.stringList("exclude"),
                cache = obj.str("cache")
            )
        }
    }

    suspend fun fetchDockerContainers(
        baseUrl: String,
        apiKey: String,
        variant: DockerRootVariant = DockerRootVariant.DOCKER_OBJ
    ): List<DockerContainer> {
        val serverHost = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        val query = when (variant) {
            DockerRootVariant.DOCKER_OBJ -> DOCKER_OBJ_QUERY
            DockerRootVariant.DOCKER_CONTAINERS -> DOCKER_CONTAINERS_QUERY
        }
        val response = executeGraphQl(baseUrl, apiKey, query)
        val data = response.data ?: throw GraphQlApiException(
            response.errors.firstOrNull()?.message ?: "Empty Docker response."
        )

        val containerArray = when (variant) {
            DockerRootVariant.DOCKER_OBJ -> data.obj("docker")?.arr("containers")
            DockerRootVariant.DOCKER_CONTAINERS -> data.arr("dockerContainers")
        } ?: return emptyList()

        return containerArray.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            if (id.isEmpty()) return@mapNotNull null
            val rawName = obj.stringList("names").firstOrNull()
                ?: obj.str("name")
            val cleanName = rawName.removePrefix("/").ifEmpty { id.take(12) }

            val ports = obj.arr("ports")?.mapNotNull { portElem ->
                val p = portElem as? JsonObject ?: return@mapNotNull null
                PortMapping(
                    privatePort = p.intOrNull("privatePort") ?: 0,
                    publicPort = p.intOrNull("publicPort"),
                    type = p.str("type", "tcp")
                )
            } ?: emptyList()

            val labels = GraphQlJsonExtractor.parseLabels(obj["labels"])
            val webUiUrl = parseDockerWebUiUrl(
                rawTemplate = labels[LABEL_WEBUI],
                serverHost = serverHost,
                ports = ports
            )

            DockerContainer(
                id = id,
                name = cleanName,
                image = obj.str("image"),
                state = obj.str("state", "UNKNOWN"),
                status = obj.str("status"),
                autoStart = obj.bool("autoStart"),
                webUiUrl = webUiUrl,
                ports = ports,
                labels = labels
            )
        }
    }

    suspend fun startContainer(baseUrl: String, apiKey: String, containerId: String) {
        val vars = buildJsonObject { put("id", JsonPrimitive(containerId)) }
        val response = executeGraphQl(baseUrl, apiKey, DOCKER_START_MUTATION, vars)
        if (response.data == null && response.errors.isNotEmpty()) {
            throw GraphQlApiException(response.errors.first().message)
        }
    }

    suspend fun stopContainer(baseUrl: String, apiKey: String, containerId: String) {
        val vars = buildJsonObject { put("id", JsonPrimitive(containerId)) }
        val response = executeGraphQl(baseUrl, apiKey, DOCKER_STOP_MUTATION, vars)
        if (response.data == null && response.errors.isNotEmpty()) {
            throw GraphQlApiException(response.errors.first().message)
        }
    }

    suspend fun restartContainer(
        baseUrl: String,
        apiKey: String,
        containerId: String,
        variant: DockerRootVariant = DockerRootVariant.DOCKER_OBJ,
        pollIntervalMillis: Long = 500L,
        maxPolls: Int = 10
    ) {
        stopContainer(baseUrl, apiKey, containerId)
        repeat(maxPolls) {
            delay(pollIntervalMillis)
            val containers = fetchDockerContainers(baseUrl, apiKey, variant)
            val target = containers.firstOrNull { it.id == containerId }
            if (target != null && !target.isRunning) {
                startContainer(baseUrl, apiKey, containerId)
                return
            }
        }
        startContainer(baseUrl, apiKey, containerId)
    }

    internal suspend fun executeGraphQl(
        baseUrl: String,
        apiKey: String,
        query: String,
        variables: JsonObject? = null
    ): GraphQlResponse = withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trimEnd('/') + "/graphql"
        val bodyPayload = json.encodeToString(
            GraphQlRequest.serializer(),
            GraphQlRequest(query = query, variables = variables)
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(bodyPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                429 -> {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull()
                    throw RateLimitedException(retryAfter)
                }
                401, 403 -> throw UnauthorizedException(response.code)
            }

            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful && rawBody.isBlank()) {
                throw GraphQlApiException("GraphQL request failed with HTTP ${response.code}.")
            }
            if (rawBody.isBlank()) {
                throw GraphQlApiException("Empty response body from Unraid GraphQL endpoint.")
            }

            json.decodeFromString(GraphQlResponse.serializer(), rawBody)
        }
    }

    private fun parseDiskList(array: kotlinx.serialization.json.JsonArray?, isCache: Boolean): List<ArrayDisk> {
        if (array == null) return emptyList()
        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id").ifEmpty { obj.str("name") }
            if (id.isEmpty()) return@mapNotNull null
            ArrayDisk(
                id = id,
                name = obj.str("name", id),
                status = obj.str("status", "UNKNOWN"),
                tempCelsius = obj.intOrNull("temp"),
                sizeBytes = obj.long("size"),
                numReads = obj.long("numReads"),
                numWrites = obj.long("numWrites"),
                isCache = isCache
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val LABEL_WEBUI = "net.unraid.docker.webui"

        private val DASHBOARD_QUERY = """
            query {
              info {
                os { platform distro release uptime }
                cpu { manufacturer brand cores threads }
              }
              metrics {
                cpu { percentTotal }
                memory { percentTotal used total }
              }
              array {
                state
                capacity { kilobytes { free used total } }
                parityCheckStatus { status progress }
                disks { id name status temp size numReads numWrites }
                caches { id name status temp size numReads numWrites }
              }
            }
        """.trimIndent()

        private val SHARES_QUERY = """
            query {
              shares { name comment size free used include exclude cache }
            }
        """.trimIndent()

        private const val CONTAINER_FIELDS =
            "id names image state status autoStart labels ports { privatePort publicPort type }"

        private val DOCKER_OBJ_QUERY = """
            query {
              docker {
                containers { $CONTAINER_FIELDS }
              }
            }
        """.trimIndent()

        private val DOCKER_CONTAINERS_QUERY = """
            query {
              dockerContainers { $CONTAINER_FIELDS }
            }
        """.trimIndent()

        private val DOCKER_START_MUTATION = """
            mutation StartContainer(${'$'}id: PrefixedID!) {
              docker {
                start(id: ${'$'}id) { id state }
              }
            }
        """.trimIndent()

        private val DOCKER_STOP_MUTATION = """
            mutation StopContainer(${'$'}id: PrefixedID!) {
              docker {
                stop(id: ${'$'}id) { id state }
              }
            }
        """.trimIndent()
    }
}
