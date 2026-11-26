package com.xinyu.companion.data.search

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenMeteoWeatherProviderTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(5))
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `geocodes city and returns structured current weather`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"results":[{"name":"苏州","country":"中国","admin1":"江苏省","latitude":31.3,"longitude":120.6}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "current": {
                    "time": "2026-11-25T10:30",
                    "temperature_2m": 18.2,
                    "apparent_temperature": 17.6,
                    "relative_humidity_2m": 62,
                    "precipitation": 0,
                    "weather_code": 2,
                    "wind_speed_10m": 8.4
                  }
                }
                """.trimIndent(),
            ),
        )
        val provider = OpenMeteoWeatherProvider(
            client = client,
            json = json,
            geocodingUrl = server.url("/geo").toString(),
            forecastUrl = server.url("/weather").toString(),
        )

        val weather = provider.current("苏州").getOrThrow()

        assertThat(weather.city).isEqualTo("苏州")
        assertThat(weather.region).isEqualTo("江苏省")
        assertThat(weather.condition).isEqualTo("局部多云")
        assertThat(weather.temperatureCelsius).isEqualTo(18.2)
        assertThat(weather.conciseText()).contains("18.2°C")
        assertThat(server.takeRequest().path).contains("name=")
        assertThat(server.takeRequest().path).contains("latitude=31.3")
    }

    @Test
    fun `reports unknown city without issuing forecast call`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val provider = OpenMeteoWeatherProvider(
            client = client,
            json = json,
            geocodingUrl = server.url("/geo").toString(),
            forecastUrl = server.url("/weather").toString(),
        )

        val result = provider.current("不存在的地方")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("没有找到")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `reports forecast failure`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"name":"苏州","country":"中国","admin1":"江苏省","latitude":31.3,"longitude":120.6}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        val provider = OpenMeteoWeatherProvider(
            client = client,
            json = json,
            geocodingUrl = server.url("/geo").toString(),
            forecastUrl = server.url("/weather").toString(),
        )

        val result = provider.current("苏州")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("天气服务")
    }
}
