package com.xinyu.companion.data.perception

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xinyu.companion.domain.perception.CoarseNetworkLocation
import com.xinyu.companion.domain.perception.CoarseNetworkLocationProvider
import com.xinyu.companion.data.network.readUtf8Within
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AndroidCoarseNetworkLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) : CoarseNetworkLocationProvider {
    private val mutex = Mutex()

    @Volatile
    private var cached: CoarseNetworkLocation? = null

    @Volatile
    private var cachedNetworkKey: String? = null

    override suspend fun current(): CoarseNetworkLocation? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val previous = cached
            val networkKey = currentNetworkKey()
            if (previous != null &&
                networkKey == cachedNetworkKey &&
                now - previous.estimatedAt < CACHE_TTL_MS
            ) {
                return@withLock previous.copy(networkType = currentNetworkType())
            }
            val fetched = runCatching { fetch() }.getOrNull()
            cached = fetched
            cachedNetworkKey = if (fetched == null) null else networkKey
            fetched
        }
    }

    private suspend fun fetch(): CoarseNetworkLocation? {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Accept", "application/json")
            .get()
            .build()
        val body = client.newBuilder()
            .callTimeout(Duration.ofSeconds(8))
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) return null
                response.body?.readUtf8Within(MAX_RESPONSE_BYTES).orEmpty()
            }
        if (body.isBlank()) return null
        val dto = json.decodeFromString<IpWhoResponse>(body)
        if (!dto.success || dto.city.isNullOrBlank()) return null
        return CoarseNetworkLocation(
            networkType = currentNetworkType(),
            city = dto.city,
            region = dto.region,
            country = dto.country,
            confidence = 0.45,
            estimatedAt = System.currentTimeMillis(),
            source = "ipwho.is",
        )
    }

    private fun currentNetworkType(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
            ?: return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun currentNetworkKey(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return "offline"
        return "${network.networkHandle}:${currentNetworkType()}"
    }

    @Serializable
    private data class IpWhoResponse(
        val success: Boolean = false,
        val city: String? = null,
        val region: String? = null,
        val country: String? = null,
    )

    private companion object {
        const val ENDPOINT = "https://ipwho.is/"
        const val CACHE_TTL_MS = 6L * 60L * 60L * 1_000L
        const val MAX_RESPONSE_BYTES = 64_000L
    }
}
