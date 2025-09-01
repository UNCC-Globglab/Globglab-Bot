package com.dudebehinddude.util

import java.time.ZoneId

fun getTimezone(): ZoneId {
    return ZoneId.of("America/New_York")
}
