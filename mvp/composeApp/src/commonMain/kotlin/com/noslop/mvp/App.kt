package com.noslop.mvp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.noslop.mvp.ui.theme.AccentGreen
import com.noslop.mvp.ui.theme.PrimaryBlack
import com.noslop.mvp.ui.theme.SurfaceDark
import com.noslop.mvp.ui.theme.TextLight
import com.noslop.mvp.ui.theme.TextMuted
import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.feeds.BackgroundScheduler
import com.noslop.mvp.ui.media.AudioPlayer
import androidx.compose.ui.layout.ContentScale
import com.noslop.mvp.ui.media.Base64Image
import com.noslop.mvp.ui.media.BlurredImageBackground
import com.noslop.mvp.ui.media.NetworkImage
import com.noslop.mvp.ui.media.SegmentedArticleReader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import com.noslop.mvp.ui.theme.MyApplicationTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

import com.noslop.mvp.ui.onboarding.OnboardingScreen
import com.noslop.mvp.ui.settings.SettingsScreen
import com.noslop.mvp.ui.settings.ContentPreferencesScreen
import com.noslop.mvp.data.SettingsRepository
import com.noslop.mvp.data.PreferencesRepository
import com.noslop.mvp.ui.tabs.ChatScreen
import com.noslop.mvp.ui.tabs.LogsViewerScreen
import com.noslop.mvp.ui.tabs.ApiKeysScreen
import com.noslop.mvp.ui.tabs.NotificationsScreen

/** Root MVP UI: three tabs — Identity, Feed, and Mesh (live hub connection). */
@Composable
fun App() {
    MyApplicationTheme {
        var handle by remember { mutableStateOf(HandleStore.load()) }
        var showOnboarding by remember { mutableStateOf(handle == "anon") }

        if (showOnboarding) {
            OnboardingScreen(onComplete = {
                handle = HandleStore.load()
                showOnboarding = false
            })
        } else {
            var tab by remember { mutableStateOf(0) }
            // Mesh state + the live connection live here (above the tab switch) so they survive tab changes —
            // switching to Feed and back keeps the scanned hub + the open link instead of tearing it down.
            val meshScope = rememberCoroutineScope()
            val mesh = remember { MeshUiState(meshScope) }
            // Start dialing the saved hub at launch (any tab) so the Tor circuit pre-builds in the background.
            LaunchedEffect(Unit) { mesh.autoConnectOnce() }
            Scaffold { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Box(modifier = Modifier.weight(1f)) {
                        when (tab) {
                            0 -> FeedScreen(mesh)
                            1 -> MeshScreen(mesh)
                            2 -> DmScreen(mesh)          // our working E2E leaf-to-leaf DMs (not the ChatScreen stub)
                            3 -> IdentityScreen()
                            else -> SettingsTabWrapper()
                        }
                    }
                    NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Home, "Feed") }, label = { Text("Feed") }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Share, "Mesh") }, label = { Text("HaiNet") }
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Chat, "Chat") }, label = { Text("DMs") }
                        )
                        NavigationBarItem(
                            selected = tab == 3, onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Person, "Identity") }, label = { Text("Identity") }
                        )
                        NavigationBarItem(
                            selected = tab == 4, onClick = { tab = 4 },
                            icon = { Icon(Icons.Filled.Settings, "Settings") }, label = { Text("Settings") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityScreen() {
    // Handle + keypair both persist (NSUserDefaults / SharedPreferences and Keychain / Keystore).
    var handle by remember { mutableStateOf(HandleStore.load()) }
    var identity by remember { mutableStateOf(loadIdentity(handle)) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NoSlop Identity", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Persisted on this device", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        OutlinedTextField(
            value = handle,
            onValueChange = { new ->
                val clean = new.filter { it.isLetterOrDigit() || it == '_' }.take(20)
                handle = clean
                HandleStore.save(clean)
                identity = identity.copy(handle = clean.ifBlank { "anon" })
            },
            label = { Text("Handle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Display name", "${identity.handle}.${identity.tripcode}")
                Field("Tripcode", identity.tripcode)
                Field("Onion", identity.onionAddress)
                Field("Public key", identity.publicKeyHex)
                val signOk = remember { Ed25519SelfTest.run() }
                Text(
                    if (signOk) "Ed25519 sign/verify ✓ (RFC 8032 conformant)" else "Ed25519 self-test failed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (signOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val dmOk = remember { DmSelfTest.run() }
                Text(
                    if (dmOk) "DM crypto ✓ (X25519 · SHA3-256 · ChaCha20-Poly1305)" else "DM crypto self-test failed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (dmOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val storage = remember { runStorageDemo(identity) }
                Text(
                    storage,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (storage.startsWith("SQLDelight ✓")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                var mesh by remember { mutableStateOf("Mesh self-test running…") }
                LaunchedEffect(Unit) { mesh = MeshSelfTest.run() }
                Text(
                    mesh,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (mesh.startsWith("Mesh ✓")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                if (!identity.isRealKeypair) {
                    Text(
                        "⚠︎ ephemeral fallback key — secure storage not wired here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Button(onClick = { identity = regenerateIdentity(handle.ifBlank { "anon" }) }) {
            Text("Regenerate keypair")
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FeedScreen(mesh: MeshUiState) {
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val repo = remember { FeedRepository() }
    var stories by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Feed curation (the "NoSlop" core). Persisted raw text; applied live.
    val saved = remember { SavedFilters.loadTexts() }
    var mutedText by remember { mutableStateOf(saved.first) }
    var requiredText by remember { mutableStateOf(saved.second) }
    var showFilters by remember { mutableStateOf(false) }
    var backgroundSyncEnabled by remember { mutableStateOf(false) }
    var commentsForPost by remember { mutableStateOf<String?>(null) }   // mesh post whose comments are open
    val scheduler = remember { BackgroundScheduler() }

    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { repo.loadFeed() }
                .onSuccess { stories = it }
                .onFailure { error = it.message ?: "Failed to load feed" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() } // auto-load on first open

    val filters = FeedFilters.from(mutedText, requiredText)
    // Mesh posts (mine + received from the network) lead the feed, then the curated clearnet items.
    val meshItems = mesh.received.map { it.toFeedItem() }
    val shown = meshItems + filters.apply(stories)

    // Full-screen vertical pager (one item per screen, swipe up) — the Android NoSlop feed feel. Each page
    // renders its media inline (video/audio/image/article) with a metadata overlay; curation filters apply live.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading && stories.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null && stories.isEmpty() ->
                Text("Error: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            shown.isEmpty() ->
                Text(if (stories.isEmpty()) "No items yet" else "All ${stories.size} filtered out",
                    color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> {
                val pager = rememberPagerState { shown.size }
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    val item = shown[page]
                    val isMesh = item.sourceId == "mesh"
                    FeedMediaCard(
                        item, isVisible = page == pager.currentPage,
                        commentCount = if (isMesh) mesh.commentsFor(item.id).size else 0,
                        score = if (isMesh) (mesh.postScore[item.id] ?: 0) else 0,
                        myVote = if (isMesh) mesh.myVote[item.id] else null,
                        onVote = if (isMesh) ({ vt -> mesh.vote(item.id, vt) }) else null,
                        onOpenComments = if (isMesh) ({ commentsForPost = item.id }) else null,
                    )
                }
            }
        }

        // Translucent top controls over the media: refresh, curation toggle, auto-sync, filter count.
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { load() }, enabled = !loading) { Text(if (loading) "…" else "↻") }
                Button(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "Hide" else "🧹") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Auto", fontSize = 12.sp, color = Color.White)
                    Switch(checked = backgroundSyncEnabled, onCheckedChange = {
                        backgroundSyncEnabled = it
                        if (it) scheduler.scheduleFeedSync(1) else scheduler.cancelFeedSync()
                    })
                }
                if (stories.isNotEmpty()) Text("${shown.size}/${stories.size}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            }
            if (showFilters) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            mutedText, { mutedText = it; SavedFilters.saveTexts(it, requiredText) },
                            label = { Text("Mute words (comma-separated)") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
                        )
                        OutlinedTextField(
                            requiredText, { requiredText = it; SavedFilters.saveTexts(mutedText, it) },
                            label = { Text("Must-have words (optional)") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
                        )
                        Text("Hide anything with a mute word; with must-have words set, show only matches.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }

    // Comments sheet for the mesh post whose 💬 was tapped.
    commentsForPost?.let { pid ->
        CommentsSheet(mesh, pid, onDismiss = { commentsForPost = null })
    }
}

/** One full-screen feed page: media routed by type, with a bottom gradient metadata overlay (Android parity).
 *  Mesh posts also get a comments affordance ([onOpenComments]). */
@Composable
private fun FeedMediaCard(
    item: FeedItem, isVisible: Boolean,
    commentCount: Int = 0, score: Int = 0, myVote: String? = null,
    onVote: ((String) -> Unit)? = null, onOpenComments: (() -> Unit)? = null,
) {
    val url = item.mediaUrl
    val thumb = item.thumbnailB64
    // A mesh media post has no playable url yet (download is 5b) but carries an in-band base64 thumbnail —
    // render it as an image card (no fetch) instead of falling back to the text article view.
    val kind = when {
        url != null -> feedKind(item, url)
        thumb != null -> FeedKind.IMAGE
        else -> FeedKind.ARTICLE
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            url != null && kind == FeedKind.VIDEO ->
                com.noslop.mvp.ui.media.VideoPlayer(url = url, isVisible = isVisible, modifier = Modifier.fillMaxSize())
            url != null && kind == FeedKind.AUDIO -> AudioPlayer(url = url, isVisible = isVisible)
            url != null && kind == FeedKind.IMAGE ->
                BlurredImageBackground(url = url, imageContent = { u, cs, m -> NetworkImage(u, cs, m) })
            url == null && thumb != null ->
                Base64Image(thumb, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else -> ArticleView(item)
        }
        // Video/audio/image fill the screen and get the bottom overlay; the article reader has its own header.
        if (kind != FeedKind.ARTICLE) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(sourceLabel(item).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                item.author?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f)) }
                Text(stripFeedHtml(item.title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 3)
            }
        }
        // Engagement rail — mesh posts only, any render kind. Anchored bottom-end so it clears the overlay.
        if (onOpenComments != null) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onVote != null) {
                    androidx.compose.material3.Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("▲", fontSize = 18.sp, color = if (myVote == "upvote") AccentGreen else Color.White,
                                modifier = Modifier.clickable { onVote("upvote") })
                            Text("$score", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("▼", fontSize = 18.sp, color = if (myVote == "downvote") Color(0xFFFF5555) else Color.White,
                                modifier = Modifier.clickable { onVote("downvote") })
                        }
                    }
                }
                androidx.compose.material3.Surface(onClick = onOpenComments, color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(20.dp)) {
                    Text(
                        if (commentCount > 0) "💬 $commentCount" else "💬",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** A modal comments thread for a mesh post — top-level comments + one level of replies, with a composer.
 *  Wire-compatible with Android: comments are signed CommentPayloads relayed over the mesh (the bridge both ways). */
@Composable
private fun CommentsSheet(mesh: MeshUiState, postId: String, onDismiss: () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var draft by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<CommentPayload?>(null) }   // set ⇒ composer posts a reply
    val all = mesh.commentsFor(postId)
    val topLevel = all.filter { it.parentCommentId == null }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.85f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().background(PrimaryBlack).padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Comments (${all.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextLight, modifier = Modifier.weight(1f))
                    Text("✕", color = TextMuted, modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                }
                if (!mesh.connected) Text("Connect on HaiNet to post.", fontSize = 11.sp, color = TextMuted)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (topLevel.isEmpty()) item {
                        Text("No comments yet — be the first.", fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    items(topLevel) { c ->
                        CommentRow(mesh, c, indent = 0, onReply = { replyingTo = c })
                        all.filter { it.parentCommentId == c.comment.id }.forEach { reply ->
                            CommentRow(mesh, reply, indent = 1, onReply = { replyingTo = c })   // replies thread under the top-level parent
                        }
                    }
                }
                replyingTo?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Replying to ${it.comment.authorName}", fontSize = 11.sp, color = AccentGreen, modifier = Modifier.weight(1f))
                        Text("cancel", fontSize = 11.sp, color = TextMuted, modifier = Modifier.clickable { replyingTo = null }.padding(4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        draft, { draft = it }, placeholder = { Text(if (replyingTo != null) "Reply…" else "Add a comment…") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { mesh.sendComment(postId, draft, replyingTo?.comment?.id); draft = ""; replyingTo = null; focus.clearFocus() },
                        enabled = mesh.connected && draft.isNotBlank(),
                    ) { Text("Post") }
                }
            }
        }
    }
}

/** One comment (or reply, indented), with up/down votes and emoji reactions (mesh-relayed, Android-interop). */
@Composable
private fun CommentRow(mesh: MeshUiState, c: CommentPayload, indent: Int, onReply: () -> Unit) {
    val cid = c.comment.id
    var showPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = (indent * 20).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.comment.authorName.ifBlank { c.comment.authorId.take(8) }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
            Text(hhmm(c.comment.timestamp), fontSize = 10.sp, color = TextMuted)
        }
        Text(c.comment.content, fontSize = 14.sp, color = TextLight)
        // Engagement: ▲ score ▼ vote, react toggle, and (top-level only) reply.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("▲", fontSize = 13.sp, color = if (mesh.myCommentVote[cid] == "upvote") AccentGreen else TextMuted,
                    modifier = Modifier.clickable { mesh.voteComment(cid, "upvote") })
                Text("${mesh.commentScore[cid] ?: 0}", fontSize = 12.sp, color = TextLight)
                Text("▼", fontSize = 13.sp, color = if (mesh.myCommentVote[cid] == "downvote") Color(0xFFFF5555) else TextMuted,
                    modifier = Modifier.clickable { mesh.voteComment(cid, "downvote") })
            }
            Text("☺+", fontSize = 12.sp, color = TextMuted, modifier = Modifier.clickable { showPicker = !showPicker })
            if (indent == 0) Text("Reply", fontSize = 11.sp, color = TextMuted, modifier = Modifier.clickable { onReply() })
        }
        val reacts = mesh.commentReactionsFor(cid)
        if (reacts.isNotEmpty()) {
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                reacts.forEach { (emoji, n) ->
                    val mine = mesh.myCommentReactions["$cid|$emoji"] == true
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (mine) AccentGreen.copy(alpha = 0.25f) else SurfaceDark)
                        .clickable { mesh.reactToComment(cid, emoji) }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("$emoji $n", fontSize = 11.sp, color = TextLight)
                    }
                }
            }
        }
        if (showPicker) {
            Row(Modifier.padding(top = 3.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceDark).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                mesh.reactionEmojis.forEach { emoji ->
                    Text(emoji, fontSize = 18.sp, modifier = Modifier.clickable { mesh.reactToComment(cid, emoji); showPicker = false })
                }
            }
        }
    }
}

/** Article reader: paginated text + hero image, for items with no playable media. */
@Composable
private fun ArticleView(item: FeedItem) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    SegmentedArticleReader(
        content = stripFeedHtml(item.fullContent ?: item.excerpt ?: item.title),
        title = stripFeedHtml(item.title),
        author = item.author,
        sourceLabel = sourceLabel(item),
        thumbnailUrl = item.thumbnailUrl,
        articleUrl = item.url,
        onOpenArticle = { url -> runCatching { uriHandler.openUri(url) } },   // read the full article in the browser
        imageContent = { u, cs, m -> NetworkImage(u, cs, m) },
    )
}

private enum class FeedKind { VIDEO, AUDIO, IMAGE, ARTICLE }

/** Route a feed item to a renderer the way the Android FeedCard does — by mediaType then URL extension. */
private fun feedKind(item: FeedItem, url: String): FeedKind {
    val u = url.lowercase()
    return when {
        item.mediaType == "video" || u.contains(".mp4") || u.contains(".mkv") || u.contains(".m3u8") ||
            u.contains("youtube") || u.contains("vimeo") || u.contains("archive.org/embed") -> FeedKind.VIDEO
        item.mediaType == "audio" || u.contains(".mp3") || u.contains(".wav") || u.contains(".m4a") ||
            u.contains(".aac") || u.contains(".ogg") || u.contains(".flac") -> FeedKind.AUDIO
        item.mediaType == "image" || u.contains(".jpg") || u.contains(".jpeg") || u.contains(".png") ||
            u.contains(".webp") || u.contains(".gif") -> FeedKind.IMAGE
        else -> FeedKind.ARTICLE
    }
}

private fun sourceLabel(item: FeedItem): String = (item.apiSource ?: item.sourceId).replace('_', ' ')

/**
 * Render a mesh social post as a feed item. SECURITY: a post comes from an untrusted peer, so we must NOT
 * auto-fetch any URL it carries over the clearnet HTTP client — that would leak the viewer's real IP to an
 * attacker-chosen server on scroll (Tor deanonymization). So `mediaUrl`/`thumbnailUrl` (which render
 * eagerly via NetworkImage/VideoPlayer) are left null; the post renders as text. `url` is kept only for the
 * user-initiated "open link" tap (their explicit choice, like tapping any link), never auto-loaded.
 */
private fun PostPayload.toFeedItem(): FeedItem = FeedItem(
    id = id,
    sourceId = "mesh",
    title = (clearnetTitle ?: content).trim().lineSequence().firstOrNull()?.take(100).orEmpty().ifBlank { content.take(100) },
    url = clearnetUrl,
    author = authorName,
    excerpt = content,
    thumbnailUrl = null,   // do NOT eagerly fetch a post-supplied image over clearnet (deanon vector)
    publishedAt = timestamp,
    fullContent = content,
    mediaUrl = null,       // ditto for media — no auto clearnet fetch from an untrusted post
    // The post's own thumbnail travels in-band (base64) — safe to show with no fetch (5a). Full media
    // download (5b) comes later; for now an Android media post at least renders its preview.
    thumbnailB64 = mediaMetadata?.thumbnailB64,
    apiSource = "mesh",
)

/** Strip HTML tags + decode common entities for display (FeedParser keeps fullContent raw; Android strips at
 *  render too — FeedCard.kt). Collapses whitespace so the article reader shows clean text, not markup. */
private fun stripFeedHtml(s: String): String =
    s.replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&#x27;", "'").replace("&#x2F;", "/").replace("&nbsp;", " ")
        // Common named entities that show up raw in feeds (smart quotes, dashes, ellipsis).
        .replace("&rsquo;", "’").replace("&lsquo;", "‘").replace("&rdquo;", "”").replace("&ldquo;", "“")
        .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…").replace("&trade;", "™")
        // Numeric entities (&#8217; etc.) — the "encoding errors" in article text.
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).let { if (it in 0..0xFFFF) it.toChar().toString() else m.value } }
        .replace(Regex("&#([0-9]+);")) { m -> m.groupValues[1].toInt().let { if (it in 0..0xFFFF) it.toChar().toString() else m.value } }
        .replace(Regex("\\s+"), " ").trim()

/**
 * Live mesh: dial a HUB and gossip through it. Connecting opens a real TCP link to an always-on hub
 * (ADR-002); posting broadcasts to the hub, which relays to other connected nodes, and posts from others
 * arrive here. This is an iPhone participating in the mesh via a hub it can't be itself.
 */
/**
 * Mesh UI state + the live [MeshClient], held above the tab switch (see [App]) so the scanned hub and the
 * open link survive switching tabs. One client for the app's lifetime; the transport read-loops run on the
 * app-scoped [scope], so the connection isn't torn down when the Mesh tab is hidden.
 */
private class MeshUiState(private val scope: CoroutineScope) {
    private val identity = loadIdentity(HandleStore.load())
    private val client = MeshClient(identity, MeshStoreProvider.get(), scope)
    val received = mutableStateListOf<PostPayload>()
    val savedNodes = mutableStateListOf<MeshInvite>()      // persisted hubs, newest first (see SavedNodes)
    val contacts = mutableStateListOf<MeshContact>()       // DM contacts (in-memory for now)
    val dmLog = mutableStateListOf<DmEntry>()              // DM history this session, newest first
    // Reactions: aggregated counts keyed "postId|emoji", plus whether *I* reacted (for the toggle/highlight).
    val reactionCounts = mutableStateMapOf<String, Int>()
    val myReactions = mutableStateMapOf<String, Boolean>()
    private val reactionSeen = mutableSetOf<String>()      // "postId|emoji|authorId" — dedup across gossip echoes
    val reactionEmojis = listOf("👍", "❤️", "😂", "🔥")
    val comments = mutableStateListOf<CommentPayload>()    // comments received/sent this session (all posts)
    // Votes: per-post net score (up − down), deduped per author, plus my own vote (for toggle/highlight).
    private val votesByAuthor = mutableMapOf<String, MutableMap<String, String>>()  // postId -> authorId -> voteType
    val postScore = mutableStateMapOf<String, Int>()
    val myVote = mutableStateMapOf<String, String>()
    // Comment reactions: aggregated counts keyed "commentId|emoji", plus whether *I* reacted (toggle/highlight).
    val commentReactionCounts = mutableStateMapOf<String, Int>()
    val myCommentReactions = mutableStateMapOf<String, Boolean>()
    private val commentReactionSeen = mutableSetOf<String>()    // "commentId|emoji|authorId" — dedup across echoes
    // Comment votes: per-comment net score, deduped per author, plus my own vote.
    private val commentVotesByAuthor = mutableMapOf<String, MutableMap<String, String>>()  // commentId -> authorId -> voteType
    val commentScore = mutableStateMapOf<String, Int>()
    val myCommentVote = mutableStateMapOf<String, String>()
    // Chat (DM) reactions: aggregated counts keyed "messageId|emoji", plus whether *I* reacted.
    val chatReactionCounts = mutableStateMapOf<String, Int>()
    val myChatReactions = mutableStateMapOf<String, Boolean>()
    private val chatReactionSeen = mutableSetOf<String>()       // "messageId|emoji|authorId" — dedup across echoes
    var host by mutableStateOf("127.0.0.1")
    var port by mutableStateOf("9876")
    var viaTor by mutableStateOf(false)
    var status by mutableStateOf("Not connected")
    var connected by mutableStateOf(false)
    var draft by mutableStateOf("Hello from my iPhone 👋")
    var dmDraft by mutableStateOf("")
    var dmReplyingTo by mutableStateOf<DmEntry?>(null)   // the message the composer is replying to (null = normal send)
    var selectedContact by mutableStateOf<MeshContact?>(null)
    private var autoTried = false
    var connecting by mutableStateOf(false)        // observable: a connect attempt is in flight
    private var activeInvite: MeshInvite? = null   // what the reconnect monitor re-dials
    private var connectJob: kotlinx.coroutines.Job? = null  // in-flight dial, cancelled when retargeting
    private var monitorStarted = false

    val linkCount: Int get() = client.linkCount

    init {
        client.onPost = { post -> scope.launch { if (received.none { it.id == post.id }) received.add(0, post) } }
        client.onDm = { fromNodeId, fromEncPub, text, messageId, replyTo ->
            scope.launch {
                // Learn the sender as a contact (so the user can reply) if we don't have them yet — persisted.
                if (contacts.none { it.nodeId == fromNodeId }) {
                    val c = MeshContact("${fromNodeId.take(8)}…", fromNodeId, fromEncPub)
                    SavedContacts.add(c); reloadContacts()
                    if (selectedContact == null) selectedContact = c
                }
                val name = contacts.firstOrNull { it.nodeId == fromNodeId }?.handle ?: fromNodeId.take(8)
                // Key the entry on the wire message id so a CHAT_REACTION (which references it) lines up.
                if (dmLog.none { it.id == messageId }) {
                    dmLog.add(0, DmEntry(id = messageId, peerNodeId = fromNodeId, from = name, text = text, mine = false, timestamp = nowMillis(), replyToId = replyTo))
                }
            }
        }
        // For Android DMs (which omit the sender's enc-key in the payload), resolve it from a stored contact.
        client.resolveEncPub = { id -> SavedContacts.list().firstOrNull { it.nodeId == id }?.encPublicKeyX509B64 }
        client.onReaction = { r -> scope.launch { applyReaction(r.postId, r.reactionType, r.authorId, r.action != "remove") } }
        client.onComment = { c -> scope.launch { if (comments.none { it.comment.id == c.comment.id }) comments.add(c) } }
        client.onVote = { v -> scope.launch { applyVote(v.postId, v.authorId, v.voteType, v.action != "remove") } }
        client.onCommentReaction = { r -> scope.launch { applyCommentReaction(r.commentId, r.reactionType, r.authorId, r.action != "remove") } }
        client.onCommentVote = { v -> scope.launch { applyCommentVote(v.commentId, v.authorId, v.voteType, v.action != "remove") } }
        client.onChatReaction = { r -> scope.launch { applyChatReaction(r.messageId, r.reactionType, r.authorId, r.action != "remove") } }
        // Incoming handshake: add/learn the peer as a contact; for a request, auto-accept (reply USER_HANDSHAKE).
        client.onHandshake = { type, hs ->
            scope.launch {
                val contact = MeshContact(
                    handle = hs.fromUsername.ifBlank { hs.fromUserId.take(8) },
                    nodeId = NodeId.androidToHex(hs.fromUserId),
                    encPublicKeyX509B64 = hs.fromEncryptionPublicKey ?: "",
                    onionAddress = hs.fromHomeNode.takeIf { it.isNotBlank() },
                )
                if (contact.encPublicKeyX509B64.isNotBlank() && contacts.none { it.nodeId == contact.nodeId }) {
                    SavedContacts.add(contact); reloadContacts()
                }
                // Either direction of a handshake means the peer is mutually linked — stop re-sending requests.
                SavedContacts.markAccepted(contact.nodeId); reloadContacts()
                if (type == "CONNECTION_REQUEST") {
                    activeInvite?.host?.let { home -> client.sendUserHandshake(contact, home) }  // accept them back
                    status = "🤝 ${contact.handle} connected — added to contacts"
                } else {
                    status = "🤝 ${contact.handle} accepted your request ✓"
                }
            }
        }
        reloadSaved()
        reloadContacts()
        // Prefill the fields with the most recent saved hub so the UI shows what auto-connect will dial.
        savedNodes.firstOrNull()?.let { host = it.host; port = it.port.toString(); viaTor = it.tor }
    }

    private fun reloadSaved() { savedNodes.clear(); savedNodes.addAll(SavedNodes.list()) }

    /**
     * On first open, auto-reconnect to the most recent saved hub — so a paired phone never has to re-scan.
     * No-op if nothing's saved yet. Additional saved hubs are reachable by tapping them in the list.
     */
    fun autoConnectOnce() {
        if (autoTried) return
        autoTried = true
        val recent = savedNodes.firstOrNull()
        if (recent == null) { status = "No saved hub yet — scan a node QR to connect"; return }
        connect(recent, save = false)
    }

    /** Connect using whatever's in the host/port/Tor fields (the manual "Connect" button). */
    fun connectNow() {
        // Tolerate a full invite URI pasted into the host field instead of scanning.
        val pasted = MeshInvite.parse(host.trim())
        val invite = pasted ?: MeshInvite(host.trim(), port.toIntOrNull() ?: 9876, viaTor)
        connect(invite, save = true)
    }

    /** Connect to a specific saved/scanned hub. Persists it on success when [save]. */
    fun connect(invite: MeshInvite, save: Boolean) {
        activeInvite = invite
        host = invite.host; port = invite.port.toString(); viaTor = invite.tor
        startReconnectMonitor()
        connectJob?.cancel()                   // cancel any in-flight dial (e.g. to a now-stale hub) and retarget
        connecting = true
        connectJob = scope.launch {
            try {
                if (invite.tor) {
                    if (!TorService.isAvailable) { status = "Tor not available on this device"; return@launch }
                    status = "Starting Tor…"
                    TorService.start()
                    // Tor's SOCKS rejects until it's bootstrapped + the onion circuit is built, so retry the
                    // connection through SOCKS until it succeeds — no fragile control-port readiness gate.
                    var waited = 0
                    while (waited < 300_000) {
                        if (activeInvite != invite) { status = "Disconnected"; return@launch } // cancelled by disconnect / new dial
                        val sp = TorService.socksPort()
                        val prog = TorService.bootstrapProgress()
                        val phase = when { prog in 1..99 -> "bootstrapping $prog%"; prog >= 100 -> "building circuit"; else -> "starting Tor" }
                        status = "Connecting via Tor… $phase (${waited / 1000}s)"
                        if (sp > 0) {
                            val ok = runCatching { client.connect(invite.host, invite.port, SocksProxy("127.0.0.1", sp)) }
                                .map { client.linkCount > 0 }.getOrDefault(false)
                            if (ok) { onLinked(invite, save, "over Tor"); return@launch }
                        }
                        delay(4000); waited += 4000
                    }
                    status = "Couldn't reach the hub over Tor (5 min) — will keep retrying. tor: ${TorService.status()}"
                    return@launch
                }
                status = "Connecting…"
                runCatching { client.connect(invite.host, invite.port, null) }
                    .onSuccess { if (client.linkCount > 0) onLinked(invite, save, "") else { connected = false; status = "No link" } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; status = "Error: ${it.message}" }
            } finally {
                if (activeInvite == invite) connecting = false   // only the latest dial owns the flag
            }
        }
    }

    /**
     * Keep the link alive: poll for drops and auto-redial the active hub, so a paired phone stays connected
     * across Tor circuit churn / app resumes without the user touching anything. Eager — the first connect
     * starts it, then it runs for the app's lifetime.
     */
    private fun startReconnectMonitor() {
        if (monitorStarted) return
        monitorStarted = true
        scope.launch {
            while (true) {
                delay(6000)
                if (client.linkCount == 0) {
                    if (connected) connected = false      // reflect a dropped link in the UI
                    val inv = activeInvite
                    if (inv != null && !connecting) { status = "Link dropped — reconnecting…"; connect(inv, save = false) }
                }
            }
        }
    }

    private fun onLinked(invite: MeshInvite, save: Boolean, how: String) {
        connected = true
        status = "Connected ✓ ${how.ifBlank { "" }}— ${client.linkCount} link(s)".replace("  ", " ")
        if (save) { SavedNodes.add(invite.toUri()); reloadSaved() }
        // Re-handshake every Android contact through the (now-connected) bridge: dial + signed CONNECTION_REQUEST.
        contacts.filter { it.onionAddress != null }.forEach { handshakeAndroidContact(it) }
    }

    /** User-initiated disconnect: stop auto-reconnect and drop the link, keeping the client reusable. */
    fun disconnect() {
        activeInvite = null            // stop the reconnect monitor from re-dialing
        connectJob?.cancel()           // cancel any in-flight dial
        connecting = false
        scope.launch {
            client.disconnectLinks()
            connected = false
            status = "Disconnected"
        }
    }

    fun removeNode(invite: MeshInvite) { SavedNodes.remove(invite); reloadSaved() }

    /** Pin a saved hub as the launch default by moving it to the front (what autoConnectOnce dials). */
    fun setDefaultHub(invite: MeshInvite) { SavedNodes.add(invite.toUri()); reloadSaved() }

    fun onScan(scanned: String?) {
        if (scanned == null) { status = "Scan cancelled"; return }
        // A hub invite (noslop://…) connects; a contact card ({…}) is added for DMs.
        MeshInvite.parse(scanned)?.let { invite ->
            status = "Scanned ${if (invite.tor) "onion" else invite.host} — connecting…"
            connect(invite, save = true); return
        }
        MeshContact.parse(scanned)?.let { contact -> addContact(contact); return }
        status = "Not a NoSlop code"
    }

    private fun reloadContacts() { contacts.clear(); contacts.addAll(SavedContacts.list()) }

    /** My shareable contact card as a QR string — Android-compatible shape so both iOS *and* Android can scan it. */
    /**
     * My shareable card. An iOS leaf has no onion, so advertise the BRIDGE onion as my reachable address —
     * then a peer (e.g. Gabby) who scans this dials the bridge, which relays to my phone (inbound path).
     * Falls back to the saved Tor hub if not currently connected.
     */
    fun myContactQr(): String {
        val bridgeOnion = activeInvite?.takeIf { it.tor }?.host ?: savedNodes.firstOrNull { it.tor }?.host
        val card = client.contactCard()
        return (if (bridgeOnion != null) card.copy(onionAddress = bridgeOnion) else card).toAndroidQrJson()
    }
    fun myHandle(): String = client.contactCard().handle

    fun addContact(contact: MeshContact) {
        SavedContacts.add(contact); reloadContacts()
        selectedContact = contacts.firstOrNull { it.nodeId == contact.nodeId }
        status = "Added contact ${contact.handle}"
        if (connected) handshakeAndroidContact(contact)
    }

    /**
     * Bring an Android contact online through the bridge: tell the bridge to dial them, then send a signed
     * CONNECTION_REQUEST (the handshake Android needs before accepting DMs). Retried a few times because the
     * bridge's dial to their onion takes a little while — the request must arrive after their link is up.
     * `fromHomeNode` is the bridge's onion (what we're connected through), so their replies route back to us.
     */
    private val handshaking = mutableSetOf<String>()   // contacts with a handshake loop in flight (no stacking)

    private fun handshakeAndroidContact(contact: MeshContact) {
        val onion = contact.onionAddress ?: return
        val homeNode = activeInvite?.host ?: return       // the bridge onion we're connected through
        // Already handshake-complete (persisted), or a loop is already running for them — don't re-spam.
        if (contact.accepted || !handshaking.add(contact.nodeId)) return
        // Be patient instead of bursting: a peer can be offline for days. If we sent a request within the retry
        // window, stay quiet (across reconnects/relaunches) — we'll try again only after it lapses or they accept.
        val last = contact.lastHandshakeAt
        if (last != null && nowMillis() - last < HANDSHAKE_RETRY_MS) { handshaking.remove(contact.nodeId); return }
        scope.launch {
            try {
                client.requestDial(contact)
                // The bridge needs a few seconds to dial the peer's onion; send before it's linked and the request
                // is dropped (0 peers). So wait, send once, then a single backup to cover a slow Tor dial — and stop.
                delay(20_000)
                repeat(2) { attempt ->
                    if (contacts.firstOrNull { it.nodeId == contact.nodeId }?.accepted == true) return@launch
                    val ok = client.sendConnectionRequest(contact, homeNode)
                    if (!ok) { status = "Can't sign handshake (identity key)"; return@launch }
                    if (attempt == 0) { SavedContacts.recordHandshake(contact.nodeId, nowMillis()); reloadContacts() }
                    if (attempt == 0) delay(30_000)        // backup send only if still unaccepted after a slow dial
                }
            } finally {
                handshaking.remove(contact.nodeId)
            }
        }
    }

    private companion object { const val HANDSHAKE_RETRY_MS = 3L * 24 * 60 * 60 * 1000 }  // ~3 days between retries

    /** Give a contact a meaningful name (keyed by node id; survives relaunches). */
    fun renameContact(contact: MeshContact, newName: String) {
        val renamed = contact.copy(handle = newName.trim().ifBlank { contact.handle })
        SavedContacts.add(renamed); reloadContacts()
        if (selectedContact?.nodeId == contact.nodeId) selectedContact = renamed
    }

    fun removeContact(contact: MeshContact) {
        SavedContacts.remove(contact); reloadContacts()
        if (selectedContact?.nodeId == contact.nodeId) selectedContact = contacts.firstOrNull()
    }

    fun sendDm() {
        val to = selectedContact ?: return
        val text = dmDraft.trim()
        if (text.isEmpty()) return
        val replyTo = dmReplyingTo?.takeIf { it.peerNodeId == to.nodeId }?.id   // only thread within the open chat
        scope.launch {
            val msgId = client.sendDm(to, text, replyTo)   // wire message id — key the local entry on it for chat reactions
            if (msgId != null) {
                dmLog.add(0, DmEntry(id = msgId, peerNodeId = to.nodeId, from = to.handle, text = text, mine = true, timestamp = nowMillis(), replyToId = replyTo))
                dmDraft = ""; dmReplyingTo = null
            } else status = "DM failed (no link or bad key)"
        }
    }

    /** Look up a DM by its wire id (for rendering a reply's quoted message). */
    fun dmById(id: String?): DmEntry? = id?.let { mid -> dmLog.firstOrNull { it.id == mid } }

    /** Fold one chat reaction (ours or a peer's) into the aggregated counts, deduped by (message, emoji, author). */
    private fun applyChatReaction(messageId: String, emoji: String, authorId: String, add: Boolean) {
        val key = "$messageId|$emoji"
        val dedup = "$key|$authorId"
        if (add) {
            if (chatReactionSeen.add(dedup)) chatReactionCounts[key] = (chatReactionCounts[key] ?: 0) + 1
        } else {
            if (chatReactionSeen.remove(dedup)) chatReactionCounts[key] = ((chatReactionCounts[key] ?: 1) - 1).coerceAtLeast(0)
        }
        if (authorId == client.androidId) myChatReactions[key] = add
    }

    /** Emojis reacted on a DM message, with counts (>0), for the bubble's reaction row (any emoji a peer sent). */
    fun chatReactionsFor(messageId: String): List<Pair<String, Int>> {
        val prefix = "$messageId|"
        return chatReactionCounts.entries.filter { it.key.startsWith(prefix) && it.value > 0 }
            .map { it.key.removePrefix(prefix) to it.value }
    }

    /** Toggle my reaction on a DM message: broadcast a signed CHAT_REACTION add/remove and reflect it locally. */
    fun reactToDm(messageId: String, emoji: String) {
        scope.launch {
            val on = !(myChatReactions["$messageId|$emoji"] ?: false)
            if (client.sendChatReaction(messageId, emoji, add = on)) applyChatReaction(messageId, emoji, client.androidId, on)
            else status = "Reaction failed (identity key)"
        }
    }

    fun publish() {
        scope.launch {
            val post = client.publish(draft)
            if (received.none { it.id == post.id }) received.add(0, post)   // show my own post too
            status = "Posted ✓ — ${client.linkCount} link(s)"
        }
    }

    /** Fold one reaction (ours or a peer's) into the aggregated counts, deduped by (post, emoji, author). */
    private fun applyReaction(postId: String, type: String, authorId: String, add: Boolean) {
        val key = "$postId|$type"
        val dedup = "$key|$authorId"
        if (add) {
            if (reactionSeen.add(dedup)) reactionCounts[key] = (reactionCounts[key] ?: 0) + 1
        } else {
            if (reactionSeen.remove(dedup)) reactionCounts[key] = ((reactionCounts[key] ?: 1) - 1).coerceAtLeast(0)
        }
        if (authorId == client.androidId) myReactions[key] = add
    }

    /** Toggle my reaction on a post: broadcast a signed add/remove and reflect it locally right away. */
    fun react(post: PostPayload, type: String) {
        scope.launch {
            val on = !(myReactions["${post.id}|$type"] ?: false)
            if (client.sendReaction(post.id, type, add = on)) applyReaction(post.id, type, client.androidId, on)
            else status = "Reaction failed (identity key)"
        }
    }

    /** Fold a vote into the per-post tally, deduped by author (one vote per author; a new one replaces it). */
    private fun applyVote(postId: String, authorId: String, voteType: String, add: Boolean) {
        val authors = votesByAuthor.getOrPut(postId) { mutableMapOf() }
        if (add) authors[authorId] = voteType else if (authors[authorId] == voteType) authors.remove(authorId)
        postScore[postId] = authors.values.count { it == "upvote" } - authors.values.count { it == "downvote" }
        if (authorId == client.androidId) { if (add) myVote[postId] = voteType else myVote.remove(postId) }
    }

    /** Up/down vote a post; tapping my current vote retracts it, switching replaces it. Broadcast + echo. */
    fun vote(postId: String, voteType: String) {
        scope.launch {
            val prev = myVote[postId]
            if (prev == voteType) {
                if (client.sendVote(postId, voteType, add = false)) applyVote(postId, client.androidId, voteType, false)
            } else {
                if (prev != null && client.sendVote(postId, prev, add = false)) applyVote(postId, client.androidId, prev, false)
                if (client.sendVote(postId, voteType, add = true)) applyVote(postId, client.androidId, voteType, true)
                else status = "Vote failed (identity key)"
            }
        }
    }

    /** Comments on [postId], oldest first. */
    fun commentsFor(postId: String): List<CommentPayload> =
        comments.filter { it.postId == postId }.sortedBy { it.comment.timestamp }

    /** Post a comment (or threaded reply) on [postId]; broadcast signed + echo locally. */
    fun sendComment(postId: String, text: String, parentCommentId: String? = null) {
        val content = text.trim()
        if (content.isEmpty()) return
        scope.launch {
            val c = client.sendComment(postId, content, parentCommentId)
            if (c == null) status = "Comment failed (identity key)"
            else if (comments.none { it.comment.id == c.comment.id }) comments.add(c)
        }
    }

    /** Fold one comment reaction (ours or a peer's) into the aggregated counts, deduped by (comment, emoji, author). */
    private fun applyCommentReaction(commentId: String, emoji: String, authorId: String, add: Boolean) {
        val key = "$commentId|$emoji"
        val dedup = "$key|$authorId"
        if (add) {
            if (commentReactionSeen.add(dedup)) commentReactionCounts[key] = (commentReactionCounts[key] ?: 0) + 1
        } else {
            if (commentReactionSeen.remove(dedup)) commentReactionCounts[key] = ((commentReactionCounts[key] ?: 1) - 1).coerceAtLeast(0)
        }
        if (authorId == client.androidId) myCommentReactions[key] = add
    }

    /** Emojis reacted on a comment, with counts (>0), for the comment row's reaction strip (any emoji a peer sent). */
    fun commentReactionsFor(commentId: String): List<Pair<String, Int>> {
        val prefix = "$commentId|"
        return commentReactionCounts.entries.filter { it.key.startsWith(prefix) && it.value > 0 }
            .map { it.key.removePrefix(prefix) to it.value }
    }

    /** Toggle my reaction on a comment: broadcast signed COMMENT_REACTION add/remove + reflect locally. */
    fun reactToComment(commentId: String, emoji: String) {
        scope.launch {
            val on = !(myCommentReactions["$commentId|$emoji"] ?: false)
            if (client.sendCommentReaction(commentId, emoji, add = on)) applyCommentReaction(commentId, emoji, client.androidId, on)
            else status = "Reaction failed (identity key)"
        }
    }

    /** Fold a comment vote into the per-comment tally, deduped by author (one vote per author; a new one replaces it). */
    private fun applyCommentVote(commentId: String, authorId: String, voteType: String, add: Boolean) {
        val authors = commentVotesByAuthor.getOrPut(commentId) { mutableMapOf() }
        if (add) authors[authorId] = voteType else if (authors[authorId] == voteType) authors.remove(authorId)
        commentScore[commentId] = authors.values.count { it == "upvote" } - authors.values.count { it == "downvote" }
        if (authorId == client.androidId) { if (add) myCommentVote[commentId] = voteType else myCommentVote.remove(commentId) }
    }

    /** Up/down vote a comment; tapping my current vote retracts it, switching replaces it. Broadcast + echo. */
    fun voteComment(commentId: String, voteType: String) {
        scope.launch {
            val prev = myCommentVote[commentId]
            if (prev == voteType) {
                if (client.sendCommentVote(commentId, voteType, add = false)) applyCommentVote(commentId, client.androidId, voteType, false)
            } else {
                if (prev != null && client.sendCommentVote(commentId, prev, add = false)) applyCommentVote(commentId, client.androidId, prev, false)
                if (client.sendCommentVote(commentId, voteType, add = true)) applyCommentVote(commentId, client.androidId, voteType, true)
                else status = "Vote failed (identity key)"
            }
        }
    }
}

/** One line of DM history for the UI. [mine] = we sent it. */
data class DmEntry(
    val id: String,
    val peerNodeId: String,   // the other party's node id — keys the conversation thread
    val from: String,
    val text: String,
    val mine: Boolean,
    val timestamp: Long,
    val replyToId: String? = null,   // id of the message this one replies to (Android `replyTo`), or null
)

/**
 * Live mesh: dial a HUB and gossip through it. Connecting opens a real TCP link to an always-on hub
 * (ADR-002); posting broadcasts to the hub, which relays to other connected nodes, and posts from others
 * arrive here. Stateless view over [MeshUiState] so the connection persists across tab switches.
 */
@Composable
private fun MeshScreen(state: MeshUiState) {
    LaunchedEffect(Unit) { state.autoConnectOnce() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mesh — connect to a HUB", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (QrScanner.isAvailable) {
            // Always enabled — you can add/switch hubs while already connected (no need to disconnect first).
            Button(
                onClick = { focus.clearFocus(); QrScanner.scan { scanned -> state.onScan(scanned) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📷  Scan node QR to connect") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state.host, { state.host = it }, label = { Text("Hub host / onion URI") }, singleLine = true, modifier = Modifier.weight(2f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            )
            OutlinedTextField(
                state.port, { state.port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = state.viaTor, onCheckedChange = { state.viaTor = it })
            Text("Via Tor (for .onion hubs, from anywhere)", fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { focus.clearFocus(); state.connectNow() }, enabled = !state.connected && !state.connecting, modifier = Modifier.weight(1f)) {
                Text(if (state.connected) "Connected" else if (state.connecting) "Connecting…" else "Connect to hub")
            }
            if (state.connected || state.connecting) {
                Button(onClick = { focus.clearFocus(); state.disconnect() }) { Text("Disconnect") }
            }
        }
        Text(
            state.status,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.status.startsWith("Connected")) MaterialTheme.colorScheme.primary
            else if (state.status.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )

        // Saved hubs — persisted across launches. The first (★) is the launch default that autoConnectOnce
        // dials; "Default" pins another there. Any can be tapped to (re)connect. So a paired phone never re-scans.
        if (state.savedNodes.isNotEmpty()) {
            Text("Saved hubs (${state.savedNodes.size}) — ★ is the launch default", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            state.savedNodes.forEachIndexed { index, node ->
                val isDefault = index == 0
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (isDefault) "★ " else "") + (if (node.tor) "🧅 " else "🌐 ") + node.host.let { if (it.length > 20) it.take(9) + "…" + it.takeLast(7) else it },
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            )
                            Text(":${node.port}${if (node.tor) " · Tor" else " · LAN"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        if (!isDefault) Button(onClick = { state.setDefaultHub(node) }) { Text("★") }
                        Button(onClick = { state.connect(node, save = true) }) { Text("Connect") }
                        Button(onClick = { state.removeNode(node) }) { Text("✕") }
                    }
                }
            }
        }
        OutlinedTextField(state.draft, { state.draft = it }, label = { Text("Post to the mesh") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { state.publish() }, enabled = state.connected) { Text("Broadcast post") }

        Text("Received from mesh (${state.received.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.received) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.authorName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(p.content, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.reactionEmojis.forEach { emoji ->
                                val key = "${p.id}|$emoji"
                                val count = state.reactionCounts[key] ?: 0
                                val mine = state.myReactions[key] == true
                                Text(
                                    text = if (count > 0) "$emoji $count" else emoji,
                                    fontSize = 13.sp,
                                    color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clickable(enabled = state.connected) { state.react(p, emoji) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Direct messages: end-to-end encrypted leaf-to-leaf DMs relayed by the (blind) hub. Scan a peer's contact QR
 * to add them, pick a contact, and send. Incoming DMs are decrypted locally and auto-add the sender as a
 * contact so you can reply. Shares [MeshUiState] (hence the live link) with the Mesh tab.
 */
@Composable
private fun DmScreen(state: MeshUiState) {
    // Android-style two-pane flow: a conversation/contact list, and (when one is picked) a chat thread.
    val sel = state.selectedContact
    if (sel == null) DmContactList(state) else DmThread(state, sel)
}

/** The DMs landing list — my contact QR + scan, and each contact as an avatar row that opens its thread. */
@Composable
private fun DmContactList(state: MeshUiState) {
    var showMyQr by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().background(PrimaryBlack).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Direct messages", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextLight, modifier = Modifier.weight(1f))
                Button(onClick = { showMyQr = !showMyQr }) { Text(if (showMyQr) "Hide ID" else "My ID") }
                if (QrScanner.isAvailable) Button(onClick = { QrScanner.scan { s -> state.onScan(s) } }) { Text("＋ Add") }
                Button(onClick = {
                    val raw = clipboard.getText()?.text
                    if (raw.isNullOrBlank()) state.status = "Clipboard empty"
                    else {
                        // A shared message wraps the code in friendly text; pull out the {…} contact blob if present.
                        val i = raw.indexOf('{'); val j = raw.lastIndexOf('}')
                        state.onScan(if (i in 0 until j) raw.substring(i, j + 1) else raw.trim())
                    }
                }) { Text("Paste") }
            }
            Text(
                if (state.connected) "🔒 Linked — DMs are end-to-end encrypted; the hub only relays ciphertext."
                else "Connect to a hub on the HaiNet tab first.",
                fontSize = 12.sp, color = if (state.connected) AccentGreen else TextMuted,
            )
        }
        if (showMyQr) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("I am: ${state.myHandle()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextLight)
                    // White backdrop so the (dark-module) QR is high-contrast + scannable on the black theme.
                    Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Image(io.github.alexzhirkevich.qrose.rememberQrCodePainter(state.myContactQr()), "My contact QR", modifier = Modifier.size(220.dp))
                    }
                    Text("Have someone scan this to add you as a DM contact.", fontSize = 11.sp, color = TextMuted)
                    // Clean human message; the data rides in the attached QR image (no raw JSON in the text).
                    val shareMsg = "Add me on NoSlop — ${state.myHandle()} 👋\nScan the attached QR code to connect."
                    if (Sharer.isAvailable) {
                        Button(onClick = { Sharer.share(shareMsg, state.myContactQr()) }, modifier = Modifier.fillMaxWidth()) {
                            Text("📤  Share my contact")
                        }
                    }
                    Button(onClick = { clipboard.setText(AnnotatedString(state.myContactQr())) }, modifier = Modifier.fillMaxWidth()) {
                        Text("📋  Copy code")
                    }
                }
            }
        }
        if (state.contacts.isEmpty()) item {
            Text("No contacts yet — tap ＋ Add to scan a peer's QR.", fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
        } else items(state.contacts) { c ->
            val last = state.dmLog.firstOrNull { it.peerNodeId == c.nodeId }   // dmLog is newest-first
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceDark)
                    .clickable { state.selectedContact = c }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(c.handle, online = state.connected)
                Column(Modifier.weight(1f)) {
                    Text(c.handle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextLight, maxLines = 1)
                    Text(
                        last?.let { (if (it.mine) "You: " else "") + it.text } ?: "${c.nodeId.take(10)}…",
                        fontSize = 12.sp, color = TextMuted, maxLines = 1,
                    )
                }
                Text("✕", color = TextMuted, modifier = Modifier.clickable { state.removeContact(c) }.padding(8.dp))
            }
        }
    }
}

/** One conversation thread — Android ChatThreadScreen look: E2EE header, message bubbles, long-press reactions. */
@Composable
private fun DmThread(state: MeshUiState, peer: MeshContact) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var renaming by remember(peer.nodeId) { mutableStateOf<String?>(null) }
    // dmLog is newest-first; this thread shows just this peer's messages, oldest→newest for a chat feel.
    val thread = state.dmLog.filter { it.peerNodeId == peer.nodeId }.asReversed()
    val listState = rememberLazyListState()
    LaunchedEffect(thread.size) { if (thread.isNotEmpty()) listState.animateScrollToItem(thread.size - 1) }

    Column(Modifier.fillMaxSize().background(PrimaryBlack)) {
        // Header: back + lock + peer + E2EE status.
        Row(Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = AccentGreen, modifier = Modifier.clickable { state.dmReplyingTo = null; state.selectedContact = null }.padding(4.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Lock, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.handle, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextLight, maxLines = 1)
                Text(if (state.connected) "Direct E2EE session active" else "Offline — messages queue at the hub", fontSize = 11.sp, color = if (state.connected) AccentGreen else TextMuted)
            }
            Text("✎", color = TextMuted, modifier = Modifier.clickable { renaming = if (renaming == null) peer.handle else null }.padding(8.dp))
        }
        if (renaming != null) {
            Row(Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(renaming!!, { renaming = it }, label = { Text("Rename") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { state.renameContact(peer, renaming ?: ""); renaming = null; focus.clearFocus() }) { Text("Save") }
            }
        }

        // Messages.
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(thread) { e -> MessageBubble(e, state) }
        }

        // Reply banner: shown when composing a reply to a message in THIS chat; cancel clears it.
        state.dmReplyingTo?.takeIf { it.peerNodeId == peer.nodeId }?.let { target ->
            Row(Modifier.fillMaxWidth().background(SurfaceDark.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(28.dp).background(AccentGreen))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Replying to ${if (target.mine) "yourself" else peer.handle}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                    Text(target.text, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                }
                Text("✕", color = TextMuted, modifier = Modifier.clickable { state.dmReplyingTo = null }.padding(6.dp))
            }
        }

        // Input.
        Row(Modifier.fillMaxWidth().background(SurfaceDark).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state.dmDraft, { state.dmDraft = it }, placeholder = { Text(if (state.dmReplyingTo?.peerNodeId == peer.nodeId) "Reply…" else "Message ${peer.handle}…") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { state.sendDm(); focus.clearFocus() }),
            )
            Button(onClick = { state.sendDm(); focus.clearFocus() }, enabled = state.connected && state.dmDraft.isNotBlank()) { Text("Send") }
        }
    }
}

/** A single message bubble: mine = right/green, theirs = left/dark; long-press for reactions + reply. A reply
 *  shows a quoted snippet of the message it answers (Android `replyTo`). */
@Composable
private fun MessageBubble(e: DmEntry, state: MeshUiState) {
    var showPicker by remember { mutableStateOf(false) }
    val mine = e.mine
    val quoted = state.dmById(e.replyToId)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            // Reply quote: a trimmed preview of the answered message, attributed to who sent it.
            if (e.replyToId != null) {
                Row(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceDark.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Box(Modifier.width(2.dp).height(28.dp).background(AccentGreen))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(if (quoted?.mine == true) "You" else (quoted?.from ?: "message"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                        Text(quoted?.text ?: "(message unavailable)", fontSize = 11.sp, color = TextMuted, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (mine) AccentGreen else SurfaceDark)
                    .pointerInput(e.id) { detectTapGestures(onLongPress = { showPicker = true }) }
                    .padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp),
            ) {
                Text(e.text, color = if (mine) PrimaryBlack else TextLight, fontSize = 14.sp)
                Text(hhmm(e.timestamp), color = if (mine) PrimaryBlack.copy(alpha = 0.6f) else TextMuted, fontSize = 9.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
            }
            val reacts = state.chatReactionsFor(e.id)
            if (reacts.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reacts.forEach { (emoji, n) ->
                        val mineR = state.myChatReactions["${e.id}|$emoji"] == true
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (mineR) AccentGreen.copy(alpha = 0.25f) else SurfaceDark)
                            .clickable { state.reactToDm(e.id, emoji) }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("$emoji $n", fontSize = 12.sp, color = TextLight)
                        }
                    }
                }
            }
            if (showPicker) {
                Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("👍", "❤️", "😂", "😮", "😢", "😡").forEach { emoji ->
                        Text(emoji, fontSize = 20.sp, modifier = Modifier.clickable { state.reactToDm(e.id, emoji); showPicker = false })
                    }
                    Text("↩︎", fontSize = 18.sp, color = AccentGreen, modifier = Modifier.clickable { state.dmReplyingTo = e; showPicker = false })
                    Icon(Icons.Filled.Close, "Close", tint = TextMuted, modifier = Modifier.size(20.dp).clickable { showPicker = false })
                }
            }
        }
    }
}

/** Circular monogram avatar with an online dot — stands in for Android's PeerItem avatar. */
@Composable
private fun Avatar(name: String, online: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(AccentGreen.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull()?.uppercase() ?: "?", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        if (online) Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(SurfaceDark).padding(2.dp).clip(CircleShape).background(AccentGreen))
    }
}

/** HH:mm for a message timestamp (local time), via kotlinx-datetime. */
private fun hhmm(ts: Long): String {
    val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(ts).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}

@Composable
private fun SettingsTabWrapper() {
    var currentRoute by remember { mutableStateOf("settings") }
    val settingsRepo = remember { SettingsRepository() }
    val prefsRepo = remember { PreferencesRepository() }

    when (currentRoute) {
        "preferences" -> ContentPreferencesScreen(
            repository = prefsRepo,
            onBack = { currentRoute = "settings" }
        )
        "logs" -> LogsViewerScreen(onBack = { currentRoute = "settings" })
        "apikeys" -> ApiKeysScreen(onBack = { currentRoute = "settings" })
        "notifications" -> NotificationsScreen(onBack = { currentRoute = "settings" })
        else -> {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { currentRoute = "notifications" }) { Text("Notifications") }
                    Button(onClick = { currentRoute = "logs" }) { Text("System Logs") }
                    Button(onClick = { currentRoute = "apikeys" }) { Text("API Keys") }
                }
                SettingsScreen(
                    repository = settingsRepo,
                    onNavigateToPreferences = { currentRoute = "preferences" }
                )
            }
        }
    }
}
