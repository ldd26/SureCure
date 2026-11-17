package com.mlbb.surecure.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatScanDate(
    instant: Instant,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val formatter = DateTimeFormatter
        .ofPattern("MMMM d, yyyy", Locale.getDefault())
        .withLocale(Locale.getDefault())


    return formatter.format(instant.atZone(zoneId))
}