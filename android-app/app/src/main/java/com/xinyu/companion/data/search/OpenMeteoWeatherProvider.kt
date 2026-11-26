package com.xinyu.companion.data.search

import com.xinyu.companion.domain.perception.WeatherException
import com.xinyu.companion.domain.perception.WeatherProvider
import com.xinyu.companion.domain.perception.WeatherSnapshot
import com.xinyu.companion.data.network.readUtf8Within
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class OpenMeteoWeatherProvider internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val geocodingUrl: String = GEOCODING_URL,
    private val forecastUrl: String = FORECAST_URL,
) : WeatherProvider {
    @Inject
    constructor(
        client: OkHttpClient,
        json: Json,
    ) : this(client, json, GEOCODING_URL, FORECAST_URL)

    override suspend fun current(city: String): Result<WeatherSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCity = city.trim()
            require(cleanCity.isNotBlank()) { "城市不能为空" }
            require(cleanCity.length <= MAX_CITY_CHARS) { "城市名称过长" }
            val location = geocode(cleanCity)
            val weather = fetchCurrent(location)
            WeatherSnapshot(
                city = location.name,
                region = location.admin1,
                country = location.country,
                condition = weatherDescription(weather.current.weatherCode),
                temperatureCelsius = weather.current.temperature,
                apparentTemperatureCelsius = weather.current.apparentTemperature,
                humidityPercent = weather.current.humidity?.toInt(),
                precipitationMm = weather.current.precipitation,
                windSpeedKph = weather.current.windSpeed,
                observedAt = weather.current.time,
            )
        }.recoverCatching { error ->
            if (error is CancellationException) throw error
            if (error is WeatherException) throw error
            throw WeatherException(error.message ?: "天气查询失败")
        }
    }

    private fun geocode(city: String): GeoLocation {
        val url = geocodingUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", city)
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "zh")
            .addQueryParameter("format", "json")
            .build()
        val response = executeJson<GeocodingResponse>(url, "天气位置查询失败")
        val result = response.results.firstOrNull() ?: throw WeatherException("没有找到这个城市")
        return GeoLocation(
            name = result.name,
            admin1 = result.admin1,
            country = result.country,
            latitude = result.latitude,
            longitude = result.longitude,
        )
    }

    private fun fetchCurrent(location: GeoLocation): OpenMeteoResponse {
        val url = forecastUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m",
            )
            .addQueryParameter("timezone", "auto")
            .build()
        return executeJson(url, "天气服务暂时不可用")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> executeJson(
        url: okhttp3.HttpUrl,
        fallbackMessage: String,
    ): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "YanYu-Android")
            .get()
            .build()
        return client.newBuilder()
            .callTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw WeatherException(fallbackMessage)
                val body = response.body?.readUtf8Within(MAX_RESPONSE_BYTES).orEmpty()
                json.decodeFromString<T>(body)
            }
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "局部多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "降雨"
        66, 67 -> "冻雨"
        71, 73, 75 -> "降雪"
        77 -> "米雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "天气状况未知"
    }

    @Serializable
    private data class GeocodingResponse(
        val results: List<GeoResultDto> = emptyList(),
    )

    @Serializable
    private data class GeoResultDto(
        val name: String,
        val country: String? = null,
        val admin1: String? = null,
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    private data class OpenMeteoResponse(
        val current: CurrentWeatherDto,
    )

    @Serializable
    private data class CurrentWeatherDto(
        val time: String? = null,
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Double? = null,
        val precipitation: Double? = null,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    )

    private data class GeoLocation(
        val name: String,
        val admin1: String?,
        val country: String?,
        val latitude: Double,
        val longitude: Double,
    )

    private companion object {
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val REQUEST_TIMEOUT_SECONDS = 12L
        const val MAX_CITY_CHARS = 80
        const val MAX_RESPONSE_BYTES = 500_000L
    }
}
