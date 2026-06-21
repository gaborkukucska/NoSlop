import os
import sys

def apply_patch(filepath, old_content, new_content, name):
    if not os.path.exists(filepath):
        print(f"❌ File not found: {filepath}")
        sys.exit(1)
        
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
        
    if old_content in content:
        content = content.replace(old_content, new_content)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"✅ Successfully patched {name}")
    elif new_content in content:
        print(f"⚠️ {name} is already patched.")
    else:
        print(f"❌ Failed to patch {name}: Could not find the exact code block to replace. Did the file change?")
        sys.exit(1)


# 1. Patch InvidiousApiClient.kt (Add the channel search method)
api_path = "app/src/main/java/com/noslop/app/feeds/api/InvidiousApiClient.kt"

api_old = """        Logger.error(TAG, "All Invidious instances failed for trending", null)
        return emptyList()
    }

    private fun parseVideoArray(array: JsonArray, sourceId: String): List<FeedItem> {"""

api_new = """        Logger.error(TAG, "All Invidious instances failed for trending", null)
        return emptyList()
    }

    suspend fun searchChannels(query: String): List<String> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val instances = getInstances()

        for (instance in instances) {
            val url = "$instance/api/v1/search?q=$encodedQuery&type=channel"
            try {
                Logger.info(TAG, "Trying Invidious instance for channel search: $instance")
                val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body == null) {
                        response.close()
                        continue
                    }
                    val array = gson.fromJson(body, JsonArray::class.java)
                    val channels = mutableListOf<String>()
                    for (element in array) {
                        try {
                            val v = element.asJsonObject
                            val author = v.get("author")?.asString
                            if (author != null && author.isNotBlank()) {
                                channels.add(author)
                            }
                        } catch (e: Exception) {
                            // Skip malformed
                        }
                    }
                    Logger.info(TAG, "Channel search successful via $instance. Fetched ${channels.size} channels")
                    markInstanceOk(instance)
                    return channels.take(3)
                } else {
                    response.close()
                    Logger.warn(TAG, "Instance $instance returned HTTP ${response.code}")
                    markInstanceFailed(instance)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
                markInstanceFailed(instance)
            }
        }

        Logger.error(TAG, "All Invidious instances failed for channel search", null)
        return emptyList()
    }

    private fun parseVideoArray(array: JsonArray, sourceId: String): List<FeedItem> {"""
apply_patch(api_path, api_old, api_new, "InvidiousApiClient.kt")


# 2. Patch OnboardingScreen.kt
onboarding_path = "app/src/main/java/com/noslop/app/ui/OnboardingScreen.kt"

onboarding_state_old = """    // Parse the current keyword text into a set for chip highlighting
    val currentKeywords = remember(creatorKeywords) {
        creatorKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    Column("""

onboarding_state_new = """    // Parse the current keyword text into a set for chip highlighting
    val currentKeywords = remember(creatorKeywords) {
        creatorKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    var channelSearchQuery by remember { mutableStateOf("") }
    var searchedChannels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearchingChannels by remember { mutableStateOf(false) }

    LaunchedEffect(channelSearchQuery) {
        if (channelSearchQuery.isBlank()) {
            searchedChannels = emptyList()
            isSearchingChannels = false
            return@LaunchedEffect
        }
        isSearchingChannels = true
        kotlinx.coroutines.delay(600) // Debounce typing
        try {
            searchedChannels = com.noslop.app.feeds.api.InvidiousApiClient.searchChannels(channelSearchQuery)
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.error("ONBOARDING", "Channel search failed: ${e.message}")
        } finally {
            isSearchingChannels = false
        }
    }
    
    val combinedSuggestions = remember(suggestions, searchedChannels) {
        (searchedChannels + suggestions).distinct()
    }

    Column("""
apply_patch(onboarding_path, onboarding_state_old, onboarding_state_new, "OnboardingScreen.kt (State)")

onboarding_ui_old = """        // Word-cloud: show suggestions above the text field so users pick from the cloud first
        if (suggestions.isNotEmpty()) {
            Text(
                text = "SUGGESTED FOR YOUR INTERESTS",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, bottom = 6.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.forEach { creator ->"""

onboarding_ui_new = """        // Word-cloud: show suggestions above the text field so users pick from the cloud first
        OutlinedTextField(
            value = channelSearchQuery,
            onValueChange = { channelSearchQuery = it },
            label = { Text("Search channel names...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
            trailingIcon = {
                if (isSearchingChannels) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
                } else if (channelSearchQuery.isNotBlank()) {
                    IconButton(onClick = { channelSearchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = BorderSubtle,
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight,
                focusedLabelColor = AccentGreen,
                unfocusedLabelColor = TextMuted
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (combinedSuggestions.isNotEmpty()) {
            Text(
                text = "SUGGESTED CHANNELS & CREATORS",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, bottom = 6.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        combinedSuggestions.forEach { creator ->"""
apply_patch(onboarding_path, onboarding_ui_old, onboarding_ui_new, "OnboardingScreen.kt (UI)")


# 3. Patch ContentPreferencesScreen.kt (Settings Screen)
prefs_path = "app/src/main/java/com/noslop/app/ui/ContentPreferencesScreen.kt"

prefs_old = """            // Creator word-cloud suggestion chips (derived from current selected interests)
            // Shown ABOVE the text field so users pick from suggestions first
            run {
                val suggestions = SourceLibrary.getSuggestedCreatorsForCategories(localInterests)
                if (suggestions.isNotEmpty()) {
                    item {
                        Text(
                            "SUGGESTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            suggestions.forEach { creator ->
                                val currentSet = creatorKeywords.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .toSet()
                                val isSelected = currentSet.contains(creator)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val updated = if (isSelected) currentSet - creator else currentSet + creator
                                        creatorKeywords = updated.joinToString(", ")
                                    },
                                    label = {
                                        Text(
                                            text = creator,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = PrimaryBlack,
                                        labelColor = TextLight,
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = AccentGreen
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) AccentGreen else BorderSubtle,
                                        selectedBorderColor = AccentGreen
                                    )
                                )
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }
            }"""

prefs_new = """            // Creator word-cloud suggestion chips (derived from current selected interests)
            // Shown ABOVE the text field so users pick from suggestions first
            item {
                var channelSearchQuery by remember { mutableStateOf("") }
                var searchedChannels by remember { mutableStateOf<List<String>>(emptyList()) }
                var isSearchingChannels by remember { mutableStateOf(false) }

                LaunchedEffect(channelSearchQuery) {
                    if (channelSearchQuery.isBlank()) {
                        searchedChannels = emptyList()
                        isSearchingChannels = false
                        return@LaunchedEffect
                    }
                    isSearchingChannels = true
                    kotlinx.coroutines.delay(600) // Debounce typing
                    try {
                        searchedChannels = com.noslop.app.feeds.api.InvidiousApiClient.searchChannels(channelSearchQuery)
                    } catch (e: Exception) {
                        com.noslop.app.debug.Logger.error("SETTINGS", "Channel search failed: ${e.message}")
                    } finally {
                        isSearchingChannels = false
                    }
                }
                
                val suggestions = SourceLibrary.getSuggestedCreatorsForCategories(localInterests)
                val combinedSuggestions = (searchedChannels + suggestions).distinct()

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = channelSearchQuery,
                        onValueChange = { channelSearchQuery = it },
                        label = { Text("Search channel names...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                        trailingIcon = {
                            if (isSearchingChannels) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
                            } else if (channelSearchQuery.isNotBlank()) {
                                IconButton(onClick = { channelSearchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedLabelColor = AccentGreen,
                            unfocusedLabelColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    if (combinedSuggestions.isNotEmpty()) {
                        Text(
                            "SUGGESTED CHANNELS & CREATORS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            combinedSuggestions.forEach { creator ->
                                val currentSet = creatorKeywords.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .toSet()
                                val isSelected = currentSet.contains(creator)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val updated = if (isSelected) currentSet - creator else currentSet + creator
                                        creatorKeywords = updated.joinToString(", ")
                                    },
                                    label = {
                                        Text(
                                            text = creator,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = PrimaryBlack,
                                        labelColor = TextLight,
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = AccentGreen
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) AccentGreen else BorderSubtle,
                                        selectedBorderColor = AccentGreen
                                    )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }"""
apply_patch(prefs_path, prefs_old, prefs_new, "ContentPreferencesScreen.kt")

print("--- Creator Channel Search integration complete! ---")
