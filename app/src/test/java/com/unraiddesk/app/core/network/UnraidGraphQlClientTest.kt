package com.unraiddesk.app.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnraidGraphQlClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: UnraidGraphQlClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = UnraidGraphQlClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun probeAndFetchDockerContainersSupportsUnraid72DockerObjectVariant() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"docker":{"containers":[{"id":"c1"}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "docker": {
                      "containers": [
                        {
                          "id": "c1",
                          "names": ["/plex"],
                          "image": "lscr.io/linuxserver/plex:latest",
                          "state": "RUNNING",
                          "status": "Up 3 days",
                          "autoStart": true,
                          "labels": {
                            "net.unraid.docker.webui": "http://[IP]:[PORT:32400]/web"
                          },
                          "ports": [
                            { "privatePort": 32400, "publicPort": 32401, "type": "tcp" }
                          ]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
            )
        )

        val variant = client.probeDockerRoot(baseUrl(), "test-key-72")
        assertEquals(DockerRootVariant.DOCKER_OBJ, variant)

        val containers = client.fetchDockerContainers(baseUrl(), "test-key-72", variant)
        assertEquals(1, containers.size)
        val plex = containers.first()
        assertEquals("plex", plex.name)
        assertTrue(plex.isRunning)
        assertEquals("http://${server.hostName}:32401/web", plex.webUiUrl)

        val recorded = server.takeRequest()
        assertEquals("test-key-72", recorded.getHeader("x-api-key"))
        assertEquals("/graphql", recorded.path)
    }

    @Test
    fun probeAndFetchDockerContainersFallsBackToUnraid74DockerContainersRoot() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":null,"errors":[{"message":"Cannot query field 'docker' on type 'Query'."}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"dockerContainers":[{"id":"c2"}]}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "dockerContainers": [
                      {
                        "id": "c2",
                        "names": ["/sonarr"],
                        "image": "lscr.io/linuxserver/sonarr:latest",
                        "state": "EXITED",
                        "status": "Exited (0)",
                        "autoStart": false,
                        "labels": [
                          { "key": "net.unraid.docker.webui", "value": "http://[IP]:[PORT:8989]/" }
                        ],
                        "ports": []
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        val variant = client.probeDockerRoot(baseUrl(), "test-key-74")
        assertEquals(DockerRootVariant.DOCKER_CONTAINERS, variant)

        val containers = client.fetchDockerContainers(baseUrl(), "test-key-74", variant)
        assertEquals(1, containers.size)
        assertEquals("sonarr", containers.first().name)
        assertEquals("http://${server.hostName}:8989/", containers.first().webUiUrl)
    }

    @Test
    fun fetchDashboardHandlesPartialDataWithGraphQlErrorsGracefully() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "info": {
                      "os": { "platform": "linux", "distro": "Unraid", "release": "7.4.0", "uptime": "5d" },
                      "cpu": { "manufacturer": "AMD", "brand": "Ryzen 7", "cores": 8, "threads": 16 }
                    },
                    "metrics": {
                      "cpu": { "percentTotal": 18.5 },
                      "memory": { "percentTotal": 42.0, "used": 17179869184, "total": 34359738368 }
                    },
                    "array": {
                      "state": "STARTED",
                      "capacity": { "kilobytes": { "free": 4000000, "used": 6000000, "total": 10000000 } },
                      "parityCheckStatus": { "status": "IDLE", "progress": 0.0 },
                      "disks": [
                        { "id": "disk1", "name": "disk1", "status": "DISK_OK", "temp": 34, "size": 10000000, "numReads": 1200, "numWrites": 400 }
                      ],
                      "caches": []
                    }
                  },
                  "errors": [
                    { "message": "Temperature sensor unavailable on nvme0" }
                  ]
                }
                """.trimIndent()
            )
        )

        val snapshot = client.fetchDashboard(baseUrl(), "test-key")
        assertEquals("7.4.0", snapshot.info.release)
        assertEquals(18.5, snapshot.metrics.cpuPercent, 0.01)
        assertEquals("STARTED", snapshot.array.state)
        assertEquals(6_000_000L * 1024L, snapshot.array.usedBytes)
        assertEquals(4_000_000L * 1024L, snapshot.array.freeBytes)
        assertEquals(10_000_000L * 1024L, snapshot.array.totalBytes)
        assertEquals(1, snapshot.array.disks.size)
        assertEquals(1, snapshot.warnings.size)
    }

    @Test
    fun rateLimit429ThrowsRateLimitedExceptionWithRetryAfter() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setBody("Too Many Requests")
        )

        var caught: UnraidGraphQlClient.RateLimitedException? = null
        try {
            client.fetchShares(baseUrl(), "test-key")
        } catch (e: UnraidGraphQlClient.RateLimitedException) {
            caught = e
        }
        assertTrue(caught != null)
        assertEquals(30, caught!!.retryAfterSeconds)
    }

    @Test
    fun dockerStartStopAndRestartExecuteExpectedMutationsAndPoll() = runTest {
        // start
        server.enqueue(MockResponse().setBody("""{"data":{"docker":{"start":{"id":"c1","state":"RUNNING"}}}}"""))
        client.startContainer(baseUrl(), "test-key", "c1")

        // stop
        server.enqueue(MockResponse().setBody("""{"data":{"docker":{"stop":{"id":"c1","state":"EXITED"}}}}"""))
        client.stopContainer(baseUrl(), "test-key", "c1")

        // restart = stop -> poll -> start
        server.enqueue(MockResponse().setBody("""{"data":{"docker":{"stop":{"id":"c1","state":"EXITED"}}}}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"docker":{"containers":[{"id":"c1","names":["/plex"],"state":"EXITED","ports":[],"labels":{}}]}}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"data":{"docker":{"start":{"id":"c1","state":"RUNNING"}}}}"""))

        client.restartContainer(
            baseUrl = baseUrl(),
            apiKey = "test-key",
            containerId = "c1",
            variant = DockerRootVariant.DOCKER_OBJ,
            pollIntervalMillis = 10L,
            maxPolls = 3
        )

        assertEquals(5, server.requestCount)
    }

    @Test
    fun webUiLabelParserSubstitutesIpAndMappedPortsWithFallback() {
        val mapped = parseDockerWebUiUrl(
            rawTemplate = "https://[IP]:[PORT:8080]/admin",
            serverHost = "192.168.1.50",
            ports = listOf(PortMapping(privatePort = 8080, publicPort = 18080))
        )
        assertEquals("https://192.168.1.50:18080/admin", mapped)

        val fallback = parseDockerWebUiUrl(
            rawTemplate = "http://[IP]:[PORT:9000]/",
            serverHost = "tower.local",
            ports = emptyList()
        )
        assertEquals("http://tower.local:9000/", fallback)
    }
}
