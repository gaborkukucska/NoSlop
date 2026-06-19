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

    /**
     * Categories always included in the pipeline regardless of user selection.
     * Their sources are auto-enabled and they should never appear in the
     * onboarding/settings category picker.
     */
    val alwaysIncludedCategories = listOf("Video Platforms", "Social Clearnet")

    /** Full internal category list — used by the pipeline / recovery logic. */
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

    /** Categories shown to the user for selection (excludes always-included source categories). */
    val selectableCategories = categories.filter { it !in alwaysIncludedCategories }

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

        // Self-Hosting
        BuiltInSource("r-selfhosted", "r/selfhosted", "https://old.reddit.com/r/selfhosted.rss", "rss", "Self-Hosting"),
        BuiltInSource("r-homelab", "r/homelab", "https://old.reddit.com/r/homelab.rss", "rss", "Self-Hosting"),

        // Science
        BuiltInSource("nasa-news", "NASA News", "https://www.nasa.gov/news-release/feed/", "rss", "Science"),
        BuiltInSource("physorg", "Phys.org", "https://phys.org/rss-feed/", "rss", "Science"),
        // NOTE: sciencedaily.com/rss/top.xml intermittently returns non-XML content;
        // replaced with Phys.org breaking news which covers the same topics reliably.
        BuiltInSource("science-news", "Science News", "https://www.sciencenews.org/feed", "rss", "Science"),
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
        // NOTE: rssfeeds.webmd.com was retired; replaced with Medical Xpress general health feed
        BuiltInSource("medicalxpress", "Medical Xpress", "https://medicalxpress.com/rss-feed/", "rss", "Health"),
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
        BuiltInSource("api-vimeo-featured", "Vimeo Featured", "vimeo:featured", "api", "Video Platforms"),
        BuiltInSource("api-wikimedia-featured", "Wikimedia Featured", "wikimedia:featured", "api", "Photography")
    )

    fun getSourcesForCategory(category: String): List<BuiltInSource> {
        return sources.filter { it.category == category }
    }

    /**
     * Curated list of well-known creators, channels, YouTubers, podcasters, subreddits,
     * and content personalities per category. Used to seed the word-cloud suggestion UI
     * on the onboarding "Creator Filter" step and in the Settings preferences screen.
     *
     * These names are passed as search keywords to the API pipeline so the aggregator
     * surfaces content from/about them — e.g. "Linus Tech Tips" → YouTube search,
     * "Lex Fridman" → podcast search.
     */
    val creatorSuggestionsByCategory: Map<String, List<String>> = mapOf(
        "Technology" to listOf(
            "Linus Tech Tips", "Marques Brownlee", "Fireship", "ThePrimeagen",
            "NetworkChuck", "Hacker News", "TechLinked", "Chris Titus Tech",
            "Jeff Geerling", "Wolfgang's Channel", "Dave2D"
        ),
        "Privacy & Security" to listOf(
            "Naomi Brockwell", "Mental Outlaw", "Rob Braxman", "The Hated One",
            "SecurityNow", "Troy Hunt", "Bruce Schneier", "Krebs on Security",
            "Techlore", "The New Oil", "Side of Burritos"
        ),
        "Self-Hosting" to listOf(
            "TechnoTim", "Christian Lempa", "Jeff Geerling", "Wolfgang's Channel",
            "Lawrence Systems", "Craft Computing", "Raid Owl", "DB Tech",
            "Jim's Garage", "Ibracorp", "Anand IAS"
        ),
        "Science" to listOf(
            "Veritasium", "Kurzgesagt", "SciShow", "PBS Space Time",
            "Anton Petrov", "Scott Manley", "Lex Fridman", "Sabine Hossenfelder",
            "Real Engineering", "MinutePhysics", "SEA"
        ),
        "World News" to listOf(
            "Al Jazeera", "BBC World Service", "DW News", "FRANCE 24",
            "Reuters", "Associated Press", "The Guardian", "Axios",
            "Democracy Now", "Tagesschau", "NHK World"
        ),
        "Open Source" to listOf(
            "The Linux Foundation", "Brodie Robertson", "DistroTube", "Luke Smith",
            "Mental Outlaw", "Chris Titus Tech", "Learn Linux TV", "Veronica Explains",
            "Wolfgang's Channel", "Average Linux User", "Linux Experiment"
        ),
        "Video Platforms" to listOf(
            "Corridor Crew", "Wendover Productions", "CGP Grey", "Tom Scott",
            "Johnny Harris", "Neo", "Solar Sands", "PolyMatter",
            "RealLifeLore", "Half as Interesting", "Economics Explained"
        ),
        "Social Clearnet" to listOf(
            "Cory Doctorow", "Electronic Frontier Foundation", "Techdirt",
            "Wired", "The Intercept", "404 Media", "Pluralistic",
            "Slashdot", "Hackaday", "Hacker News"
        ),
        "Lifestyle" to listOf(
            "Matt D'Avella", "Thomas Frank", "Ali Abdaal", "Better Ideas",
            "Mark Manson", "Struthless", "Mike and Matty", "Psych2Go",
            "Nathaniel Drew", "Mike Boyd", "CGP Grey"
        ),
        "Gaming" to listOf(
            "Dunkey", "ACG", "Skill Up", "SkillUp", "Asmongold",
            "penguinz0", "SomeOrdinaryGamers", "NakeyJakey", "MauLer",
            "Girlfriend Reviews", "AngryJoeShow", "Game Maker's Toolkit"
        ),
        "Health" to listOf(
            "Andrew Huberman", "Peter Attia", "Rhonda Patrick",
            "Mike Israetel", "Jeff Nippard", "Paul Saladino", "Mark Hyman",
            "Bryan Johnson", "Layne Norton", "Ben Greenfield"
        ),
        "Automotive" to listOf(
            "Donut Media", "Throttle House", "Savagegeese", "Edd China",
            "Daily Driven Exotics", "Hoovies Garage", "ChrisFix",
            "Scotty Kilmer", "Engineering Explained", "Lemmy Caution"
        ),
        "Art" to listOf(
            "Proko", "Marco Bucci", "James Gurney", "ARTEZA",
            "Draw with Jazza", "David Finch", "Sycra", "Sinix Design",
            "Ctrl+Paint", "Mark Crilley", "Trent Kaniuga"
        ),
        "Photography" to listOf(
            "Peter McKinnon", "Thomas Heaton", "Kai W", "DSLR Video Shooter",
            "Ted Forbes", "Simon d'Entremont", "Matt Granger",
            "Jamie Windsor", "Attilio Ruffo", "Nigel Danson"
        ),
        "Music" to listOf(
            "Rick Beato", "Adam Neely", "Nahre Sol", "12tone",
            "David Bennett Piano", "Charles Cornell", "Adam Ragusea",
            "Sideways", "Set Yourself On Fire", "Classical MPR"
        ),
        "Reddit" to listOf(
            "r/technology", "r/worldnews", "r/science", "r/programming",
            "r/linux", "r/privacy", "r/selfhosted", "r/gaming",
            "r/photography", "r/music", "r/videos"
        )
    )

    /**
     * Returns suggested creator/channel names for a given list of selected categories.
     * Deduplicates and returns a flat list sorted by frequency across all selected categories
     * (those appearing in more selected categories rank first), giving a meaningful word cloud
     * even when the user's category mix is unusual.
     */
    fun getSuggestedCreatorsForCategories(selectedCategories: List<String>): List<String> {
        val freq = mutableMapOf<String, Int>()
        for (cat in selectedCategories) {
            creatorSuggestionsByCategory[cat]?.forEach { creator ->
                freq[creator] = (freq[creator] ?: 0) + 1
            }
        }
        return freq.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }
}