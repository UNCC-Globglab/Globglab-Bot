package com.dudebehinddude.util

/**
 * Converts an integer to its ordinal string representation (e.g., 1st, 2nd, 3rd, 4th).
 *
 * @return The ordinal string representation of the integer.
 */
fun Int.toOrdinal(): String {
    val suffix = when {
        this % 100 in 11..13 -> "th"
        this % 10 == 1 -> "st"
        this % 10 == 2 -> "nd"
        this % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$this$suffix"
}