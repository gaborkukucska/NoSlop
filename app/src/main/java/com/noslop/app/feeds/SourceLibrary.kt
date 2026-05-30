// FILE: app/src/main/java/com/noslop/app/feeds/SourceLibrary.kt
package com.noslop.app.feeds

data class BuiltInSource(
    val id: String,
    val title: String,
    val url: String,
    val feedType: String, // "rss" or "atom"
    val category: String
)

object SourceLibrary {

    val categories = listOf(
        "Technology",
        "Privacy & Security",
        "Self-Hosting",
        "Science",
        "World News",
        "Open Source"
    )

    val sources = listOf(
        // Technology
        BuiltInSource("hn-rss", "Hacker News", "https://news.ycombinator.com/rss", "rss", "Technology"),
        BuiltInSource("ars-rss", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "rss", "Technology"),
        BuiltInSource("verge-rss", "The Verge", "https://www.theverge.com/rss/index.xml", "atom", "Technology"),

        // Privacy & Security
        BuiltInSource("krebs-rss", "Krebs on Security", "https://krebsonsecurity.com/feed/", "rss", "Privacy & Security"),
        BuiltInSource("schneier-rss", "Schneier on Security", "https://www.schneier.com/feed/atom/", "atom", "Privacy & Security"),
        BuiltInSource("eff-rss", "EFF Deeplinks", "https://www.eff.org/rss/updates.xml", "rss", "Privacy & Security"),

        // Self-Hosting
        BuiltInSource("r-selfhosted", "r/selfhosted", "https://old.reddit.com/r/selfhosted.rss", "rss", "Self-Hosting"),
        BuiltInSource("r-homelab", "r/homelab", "https://old.reddit.com/r/homelab.rss", "rss", "Self-Hosting"),

        // Science
        BuiltInSource("nasa-news", "NASA News", "https://www.nasa.gov/news-release/feed/", "rss", "Science"),
        BuiltInSource("physorg", "Phys.org", "https://phys.org/rss-feed/", "rss", "Science"),

        // World News
        BuiltInSource("ap-top", "Associated Press", "https://feeds.apnews.com/rss/TopNews", "rss", "World News"),
        BuiltInSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "rss", "World News"),

        // Open Source
        BuiltInSource("linux-foundation", "Linux Foundation", "https://www.linuxfoundation.org/blog/rss.xml", "rss", "Open Source")
    )

    fun getSourcesForCategory(category: String): List<BuiltInSource> {
        return sources.filter { it.category == category }
    }
}
