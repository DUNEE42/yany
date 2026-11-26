package com.xinyu.companion.data.network

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer

internal fun ResponseBody.readUtf8Within(maxBytes: Long): String {
    if (maxBytes <= 0) throw IOException("响应大小限制无效")
    val contentLength = contentLength()
    if (contentLength > maxBytes) throw IOException("响应内容过大")

    val source = source()
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val remainingWithOverflowCheck = maxBytes - total + 1
        val read = source.read(buffer, minOf(READ_CHUNK_BYTES, remainingWithOverflowCheck))
        if (read == -1L) break
        if (read == 0L) continue
        total += read
        if (total > maxBytes) throw IOException("响应内容过大")
    }
    return buffer.readString(Charsets.UTF_8)
}

private const val READ_CHUNK_BYTES = 8_192L
