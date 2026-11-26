package com.xinyu.companion.data.notification

import com.xinyu.companion.domain.perception.NotificationSensitivity
import com.xinyu.companion.domain.perception.SanitizedNotificationContent

object NotificationTextSanitizer {
    private val controlChars = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
    private val otpPattern = Regex(
        """(?i)(?:验证码|校验码|动态码|otp|code)\s*[:：]?\s*\d{4,8}""",
    )
    private val standaloneOtpPattern = Regex(
        """(?i)(?<!\d)\d{4,8}(?!\d)(?=\s*(?:为|是|验证码|校验码))""",
    )
    private val phonePattern = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val idPattern = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
    private val bankCardPattern = Regex("""(?<!\d)(?:\d[ -]?){16,19}(?!\d)""")
    private val secretPattern = Regex(
        """(?i)(?:api[_ -]?key|access[_ -]?token|password|密码)\s*[:：=]?\s*\S{6,}""",
    )
    private val addressPattern = Regex(
        """(?:地址|住址)\s*[:：]\s*[^\n，。；;]{4,80}""",
    )
    private val detailedAddressPattern = Regex(
        """[\u4e00-\u9fa5]{2,12}(?:省|市)[\u4e00-\u9fa5]{2,16}(?:区|县)[^\n，。；;]{0,40}\d+号""",
    )

    fun sanitize(title: CharSequence?, text: CharSequence?): SanitizedNotificationContent {
        val originalTitle = title?.toString().orEmpty().trim()
        val originalText = text?.toString().orEmpty().trim()
        val combined = "$originalTitle\n$originalText"
        val containsSensitive = listOf(
            otpPattern,
            standaloneOtpPattern,
            phonePattern,
            idPattern,
            bankCardPattern,
            secretPattern,
            addressPattern,
            detailedAddressPattern,
        ).any { it.containsMatchIn(combined) }

        return SanitizedNotificationContent(
            title = redact(originalTitle),
            text = redact(originalText),
            sensitivity = if (containsSensitive) {
                NotificationSensitivity.SENSITIVE
            } else {
                NotificationSensitivity.NORMAL
            },
        )
    }

    private fun redact(value: String): String {
        if (value.isBlank()) return ""
        return value
            .replace(controlChars, " ")
            .replace(otpPattern, "[验证码已隐藏]")
            .replace(standaloneOtpPattern, "[验证码已隐藏]")
            .replace(phonePattern, "[手机号已隐藏]")
            .replace(idPattern, "[证件号已隐藏]")
            .replace(bankCardPattern, "[卡号已隐藏]")
            .replace(secretPattern, "[凭据已隐藏]")
            .replace(addressPattern, "[地址已隐藏]")
            .replace(detailedAddressPattern, "[地址已隐藏]")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_TEXT_CHARS)
    }

    private const val MAX_TEXT_CHARS = 320
}
