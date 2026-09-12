fun main() {
    fun parseRelativeTime(publishedTimeText: String?): Long {
        if (publishedTimeText == null) return System.currentTimeMillis()
        val now = System.currentTimeMillis()
        try {
            val parts = publishedTimeText.trim().split(" ")
            if (parts.size >= 2) {
                val amount = parts[0].toLongOrNull() ?: return now
                val unit = parts[1].lowercase()
                val multiplier = when {
                    unit.startsWith("second") -> 1000L
                    unit.startsWith("minute") -> 60_000L
                    unit.startsWith("hour") -> 3_600_000L
                    unit.startsWith("day") -> 86_400_000L
                    unit.startsWith("week") -> 604_800_000L
                    unit.startsWith("month") -> 2_592_000_000L // 30 days
                    unit.startsWith("year") -> 31_536_000_000L // 365 days
                    else -> 0L
                }
                if (multiplier > 0) {
                    return now - (amount * multiplier)
                }
            }
        } catch (e: Exception) {
        }
        return now
    }

    println(parseRelativeTime("19 minutes ago"))
    println(parseRelativeTime("2 years ago"))
    println(parseRelativeTime("11 months ago"))
    println(parseRelativeTime("3 weeks ago"))
    println(System.currentTimeMillis())
}
