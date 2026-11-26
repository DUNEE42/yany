package com.xinyu.companion.domain.perception

data class WeatherSnapshot(
    val city: String,
    val region: String?,
    val country: String?,
    val condition: String,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double?,
    val humidityPercent: Int?,
    val precipitationMm: Double?,
    val windSpeedKph: Double?,
    val observedAt: String?,
    val source: String = "Open-Meteo",
) {
    fun conciseText(): String = buildString {
        append(city)
        if (!region.isNullOrBlank() && region != city) append("（$region）")
        append("：$condition，")
        append(formatOneDecimal(temperatureCelsius))
        append("°C")
        apparentTemperatureCelsius?.let {
            append("，体感 ")
            append(formatOneDecimal(it))
            append("°C")
        }
        humidityPercent?.let { append("，湿度 $it%") }
        windSpeedKph?.let {
            append("，风速 ")
            append(formatOneDecimal(it))
            append(" km/h")
        }
    }

    private fun formatOneDecimal(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)
}

interface WeatherProvider {
    suspend fun current(city: String): Result<WeatherSnapshot>
}

class WeatherException(message: String) : IllegalStateException(message)
