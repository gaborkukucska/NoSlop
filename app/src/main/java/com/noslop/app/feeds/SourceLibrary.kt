// FILE: app/src/main/java/com/noslop/app/feeds/SourceLibrary.kt
package com.noslop.app.feeds

data class BuiltInSource(
    val id: String,
    val title: String,
    val url: String,
    val feedType: String, // "rss", "atom", or "api"
    val category: String
)

object SourceLibrary {

    val categories = listOf(
        "Technology",
        "Privacy & Security",
        "Self-Hosting",
        "Science",
        "World News",
        "Open Source",
        "Video Platforms",
        "Social Clearnet",
        "Lifestyle",
        "Gaming",
        "Health",
        "Automotive",
        "Art",
        "Photography",
        "Music",
        "Reddit"
    )

    val sources = listOf(
        // Technology
        BuiltInSource("hn-rss", "Hacker News", "https://news.ycombinator.com/rss", "rss", "Technology"),
        BuiltInSource("ars-rss", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "rss", "Technology"),
        BuiltInSource("verge-rss", "The Verge", "https://www.theverge.com/rss/index.xml", "atom", "Technology"),
        BuiltInSource("techcrunch", "TechCrunch", "https://techcrunch.com/feed/", "rss", "Technology"),

        // Privacy & Security
        BuiltInSource("krebs-rss", "Krebs on Security", "https://krebsonsecurity.com/feed/", "rss", "Privacy & Security"),
        BuiltInSource("schneier-rss", "Schneier on Security", "https://www.schneier.com/feed/atom/", "atom", "Privacy & Security"),
        BuiltInSource("eff-rss", "EFF Deeplinks", "https://www.eff.org/rss/updates.xml", "rss", "Privacy & Security"),
        BuiltInSource("threatpost", "Threatpost", "https://threatpost.com/feed/", "rss", "Privacy & Security"),

        // Self-Hosting
        BuiltInSource("r-selfhosted", "r/selfhosted", "https://old.reddit.com/r/selfhosted.rss", "rss", "Self-Hosting"),
        BuiltInSource("r-homelab", "r/homelab", "https://old.reddit.com/r/homelab.rss", "rss", "Self-Hosting"),
        BuiltInSource("selfhosted-hero", "Self-Hosted Hero", "https://selfhosthero.com/rss", "rss", "Self-Hosting"),

        // Science
        BuiltInSource("nasa-news", "NASA News", "https://www.nasa.gov/news-release/feed/", "rss", "Science"),
        BuiltInSource("physorg", "Phys.org", "https://phys.org/rss-feed/", "rss", "Science"),
        BuiltInSource("sci-daily", "ScienceDaily", "https://www.sciencedaily.com/rss/top.xml", "rss", "Science"),
        BuiltInSource("nature", "Nature", "https://www.nature.com/nature.rss", "rss", "Science"),

        // World News
        // NOTE: Associated Press discontinued feeds.apnews.com RSS feeds entirely
        // (see https://github.com/rererecursive/associated-press-rss) — the old
        // "ap-top" source pointed at a dead domain and was removed. World News is
        // still well covered by Al Jazeera, BBC, Reuters, and the API sources below.
        BuiltInSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "rss", "World News"),
        BuiltInSource("bbc-world", "BBC World News", "https://feeds.bbci.co.uk/news/world/rss.xml", "rss", "World News"),
        BuiltInSource("reuters-world", "Reuters World", "https://www.reutersagency.com/feed/?best-topics=world-news&post_type=best", "rss", "World News"),

        // Open Source
        BuiltInSource("linux-foundation", "Linux Foundation", "https://www.linuxfoundation.org/blog/rss.xml", "rss", "Open Source"),
        BuiltInSource("foss-post", "FOSS Post", "https://fosspost.org/feed/", "rss", "Open Source"),
        BuiltInSource("omg-ubuntu", "OMG! Ubuntu!", "https://feeds.feedburner.com/d0od", "rss", "Open Source"),

        // Video Platforms (Dynamically populated via API based on user genres)

        // Social Clearnet
        BuiltInSource("hackaday", "Hackaday", "https://hackaday.com/blog/feed/", "rss", "Social Clearnet"),
        BuiltInSource("wired", "Wired", "https://www.wired.com/feed/rss", "rss", "Social Clearnet"),
        BuiltInSource("slashdot", "Slashdot", "https://rss.slashdot.org/Slashdot/slashdotMain", "rss", "Social Clearnet"),

        // Reddit
        BuiltInSource("r-technology", "r/technology", "https://www.reddit.com/r/technology.rss", "rss", "Reddit"),
        BuiltInSource("r-worldnews", "r/worldnews", "https://www.reddit.com/r/worldnews.rss", "rss", "Reddit"),
        BuiltInSource("r-futurism", "r/futurism", "https://www.reddit.com/r/futurism.rss", "rss", "Reddit"),
        BuiltInSource("r-android", "r/android", "https://www.reddit.com/r/android.rss", "rss", "Reddit"),

        // Lifestyle
        BuiltInSource("lifehacker", "Lifehacker", "https://lifehacker.com/rss", "rss", "Lifestyle"),
        BuiltInSource("apartment-therapy", "Apartment Therapy", "https://www.apartmenttherapy.com/main.rss", "rss", "Lifestyle"),
        BuiltInSource("zen-habits", "Zen Habits", "https://zenhabits.net/feed/", "rss", "Lifestyle"),

        // Gaming
        BuiltInSource("ign", "IGN", "https://feeds.feedburner.com/ign/all", "rss", "Gaming"),
        BuiltInSource("kotaku", "Kotaku", "https://kotaku.com/rss", "rss", "Gaming"),
        BuiltInSource("eurogamer", "Eurogamer", "https://www.eurogamer.net/feed", "rss", "Gaming"),
        BuiltInSource("pcgamer", "PC Gamer", "https://www.pcgamer.com/rss/", "rss", "Gaming"),

        // Health
        BuiltInSource("healthline", "Healthline", "https://www.healthline.com/feed", "rss", "Health"),
        BuiltInSource("webmd", "WebMD", "https://rssfeeds.webmd.com/rss/rss.aspx?RSSSource=RSS_PUBLIC", "rss", "Health"),
        BuiltInSource("mayo-clinic", "Mayo Clinic", "https://www.mayoclinic.org/rss/all-health-information-topics", "rss", "Health"),

        // Automotive
        BuiltInSource("top-gear", "Top Gear", "https://www.topgear.com/feed", "rss", "Automotive"),
        BuiltInSource("car-and-driver", "Car and Driver", "https://www.caranddriver.com/rss/all.xml/", "rss", "Automotive"),
        BuiltInSource("motor-trend", "MotorTrend", "https://www.motortrend.com/f/motortrend.xml", "rss", "Automotive"),

        // Art
        BuiltInSource("hi-fructose", "Hi-Fructose", "https://hifructose.com/feed/", "rss", "Art"),
        BuiltInSource("juxtapoz", "Juxtapoz", "https://www.juxtapoz.com/feed/", "rss", "Art"),
        BuiltInSource("colossal", "Colossal", "https://www.thisiscolossal.com/feed/", "rss", "Art"),

        // Photography
        BuiltInSource("500px-popular", "500px Popular", "https://500px.com/popular.rss", "rss", "Photography"),
        BuiltInSource("flickr-explore", "Flickr Explore", "https://www.flickr.com/services/feeds/explore/", "rss", "Photography"),
        BuiltInSource("petapixel", "PetaPixel", "https://petapixel.com/feed/", "rss", "Photography"),

        // Music
        BuiltInSource("pitchfork", "Pitchfork", "https://pitchfork.com/rss/all/", "rss", "Music"),
        BuiltInSource("rolling-stone", "Rolling Stone", "https://www.rollingstone.com/feed/", "rss", "Music"),
        BuiltInSource("nme", "NME", "https://www.nme.com/feed", "rss", "Music"),

        // ──── API-backed sources (feedType = "api") ────
        // url field = API service identifier, not a URL
        // No-auth: Reddit, Internet Archive, NASA (work immediately)
        // Optional-auth: YouTube, Pexels, NewsAPI, Guardian, Vimeo, Podcast Index (user configures own key)
        BuiltInSource("api-yt-trending", "YouTube Trending", "youtube:trending", "api", "Video Platforms"),
        BuiltInSource("api-yt-search", "YouTube Search", "youtube:search", "api", "Video Platforms"),
        BuiltInSource("api-reddit-hot", "Reddit Hot Posts", "reddit:multi", "api", "Reddit"),
        BuiltInSource("api-pexels-photo", "Pexels Photos", "pexels:photos", "api", "Art"),
        BuiltInSource("api-pexels-video", "Pexels Videos", "pexels:videos", "api", "Video Platforms"),
        BuiltInSource("api-archive-video", "Internet Archive Video", "archive:video", "api", "Video Platforms"),
        BuiltInSource("api-archive-audio", "Internet Archive Audio", "archive:audio", "api", "Music"),
        BuiltInSource("api-jamendo-music", "Jamendo Free Music", "jamendo:music", "api", "Music"),
        BuiltInSource("api-podcast-trending", "Trending Podcasts", "podcastindex:trending", "api", "Music"),
        BuiltInSource("api-newsapi-headlines", "Top Headlines", "newsapi:headlines", "api", "World News"),
        BuiltInSource("api-guardian", "The Guardian", "guardian:search", "api", "World News"),
        BuiltInSource("api-nasa-apod", "NASA Picture of the Day", "nasa:apod", "api", "Science"),
        BuiltInSource("api-nasa-library", "NASA Image Library", "nasa:library", "api", "Science"),
        BuiltInSource("api-vimeo-featured", "Vimeo Featured", "vimeo:featured", "api", "Video Platforms")
    )

    fun getSourcesForCategory(category: String): List<BuiltInSource> {
        return sources.filter { it.category == category }
    }
}