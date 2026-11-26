package com.xinyu.companion.ui.reminder

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class ReminderDateTimeMapperTest {
    @Test
    fun `utc date picker round trip preserves local date regardless of timezone`() {
        val target = LocalDate.of(2026, 12, 31)
        val utcMillis = ReminderDateTimeMapper.localDateToPickerUtcMillis(target)
        val decoded = ReminderDateTimeMapper.pickerUtcMillisToLocalDate(utcMillis)

        assertThat(decoded).isEqualTo(target)

        val shanghai = ZoneId.of("Asia/Shanghai")
        val epoch = ReminderDateTimeMapper.combineLocalDateTime(decoded, 17, 30, shanghai)
        val zoned = Instant.ofEpochMilli(epoch).atZone(shanghai)
        assertThat(zoned.toLocalDate()).isEqualTo(target)
        assertThat(zoned.hour).isEqualTo(17)
        assertThat(zoned.minute).isEqualTo(30)
    }
}
