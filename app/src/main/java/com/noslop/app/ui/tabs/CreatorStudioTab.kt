// FILE: app/src/main/java/com/noslop/app/ui/tabs/CreatorStudioTab.kt
package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

data class StudioItem(
    val id: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val mediaType: String = "video",
    val thumbnailUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorStudioTab(
    viewModel: NoSlopViewModel,
    onBack: (() -> Unit)? = null
) {
    // The queue starts empty: items arrive from Home Hub AI auto-tagging. (The two hard-coded demo
    // cards that used to seed it were removed - publishing one broadcast fake content to the mesh.)
    var queuedItems by remember { mutableStateOf(emptyList<StudioItem>()) }

    var isRefreshing by remember { mutableStateOf(false) }
    var showCreatorIdSheet by remember { mutableStateOf(false) }
    var showBurnConfirmDialog by remember { mutableStateOf(false) }
    var burnableIdentity by remember { mutableStateOf<com.noslop.app.crypto.CryptoService.IdentityKeys?>(null) }
    val coroutineScope = rememberCoroutineScope()
    // D10d: the creator identity shows the Studio fields, and the personal ones where those are blank.
    val creatorProfile by viewModel.creatorProfile.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val localHandle by viewModel.localHandle.collectAsState()
    val shownProfile = com.noslop.app.data.resolveSecondaryProfile(true, creatorProfile, userProfile, localHandle)
    val creatorHandle = shownProfile.handle
    var showProfileEditor by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    // D10d: backing up the creator identity: the passphrase first, then where to save the file.
    var showBackupPassphrase by remember { mutableStateOf(false) }
    var pendingBackupPassphrase by remember { mutableStateOf<String?>(null) }
    val isExporting by viewModel.isBackupExporting.collectAsState()

    // D10e: importing a creator identity here replaces this node's own, after a warning.
    var showImportWarning by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importedCreator by remember { mutableStateOf<com.noslop.app.data.CreatorRestoreResult.Restored?>(null) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImportUri = uri }
    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.exportCreatorIdentityToUri(context, passphrase, uri) { summary, error ->
                val text = if (summary != null) {
                    com.noslop.app.util.LanguageManager.translate("Creator identity backed up: {contacts} contacts, {posts} posts, {media} media files.")
                        .replace("{contacts}", summary.contacts.toString())
                        .replace("{posts}", summary.posts.toString())
                        .replace("{media}", summary.mediaFiles.toString())
                } else {
                    com.noslop.app.util.LanguageManager.translate("Creator identity backup failed: {reason}").replace("{reason}", error ?: "")
                }
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // A full-screen dialog over the studio, so the editor always gets the whole screen whatever
    // container Creator Studio is shown in. (As an in-place screen it inherited the host's unbounded
    // height and the weighted form collapsed to nothing, leaving only the Save button.)
    if (showProfileEditor) {
        CreatorProfileEditor(
            current = creatorProfile,
            onSave = { updated ->
                viewModel.updateCreatorProfile(updated)
                showProfileEditor = false
            },
            onBack = { showProfileEditor = false }
        )
    }

    // Creator Studio has two hosts: a full-screen settings screen (onBack != null, bounded height)
    // and an item inside the Settings LazyColumn (onBack == null, unbounded height). Nothing here may
    // use weight(), and only the full-screen host scrolls itself - a nested vertical scroll inside a
    // LazyColumn item would crash, and the LazyColumn already scrolls the embedded studio.
    val studioModifier = if (onBack != null) {
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    Column(
        modifier = studioModifier
            .background(PrimaryBlack)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
                    }
                }
                Text("Creator Studio 🎬".tr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
            }

            IconButton(onClick = { isRefreshing = true }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Queue".tr, tint = AccentGreen)
            }
        }

        // Creator Node Status Badge
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Podcasts, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Creator Node Status".tr, fontSize = 12.sp, color = TextMuted)
                        Text("Active & Publishing Ready".tr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextLight)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                burnableIdentity = viewModel.ensureBurnableIdentity()
                                showCreatorIdSheet = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Creator ID 🪪".tr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { showBurnConfirmDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DestructiveRed),
                        border = BorderStroke(1.dp, DestructiveRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(14.dp), tint = DestructiveRed)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Burn ID 🔥".tr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AccentGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            "AI Queue: {count}".tr.replace("{count}", queuedItems.size.toString()),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        CreatorProfileCard(
            studio = creatorProfile,
            shown = shownProfile,
            onEdit = { showProfileEditor = true }
        )

        CreatorBackupCard(
            isExporting = isExporting,
            isImporting = isImporting,
            onBackup = { showBackupPassphrase = true },
            onImport = { showImportWarning = true }
        )

        Text(
            "AI Approval Queue".tr,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextLight,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (queuedItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Approval Queue Clear!".tr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextLight)
                    Text("No pending media from Home Hub AI auto-tagging.".tr, fontSize = 12.sp, color = TextMuted)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                queuedItems.forEach { item ->
                    key(item.id) {
                        StudioItemCard(
                            item = item,
                            onPublish = { publishedItem ->
                                val tagsStr = if (publishedItem.tags.isNotEmpty()) "\n\n" + publishedItem.tags.joinToString(" ") { "#$it" } else ""
                                val fullPostContent = (publishedItem.title + "\n\n" + publishedItem.description + tagsStr).trim()
                                viewModel.composeAndBroadcastPost(
                                    content = fullPostContent,
                                    mediaMetadata = null,
                                    privacy = "public"
                                )
                                queuedItems = queuedItems.filter { it.id != publishedItem.id }
                                android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Published '{title}' to Mesh!").replace("{title}", publishedItem.title), android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onDiscard = { discardedId ->
                                queuedItems = queuedItems.filter { it.id != discardedId }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showImportWarning) {
        AlertDialog(
            onDismissRequest = { showImportWarning = false },
            containerColor = SurfaceDark,
            title = { Text("Import Creator Identity?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "The creator identity in the file replaces this node's creator identity, with its keys, Studio profile, followers, posts and media. The current creator identity is removed, and its followers lose it for good.".tr,
                        color = TextMuted
                    )
                    TextButton(onClick = {
                        showImportWarning = false
                        showBackupPassphrase = true
                    }) {
                        Text("Back Up First".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportWarning = false
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                ) { Text("Choose File".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showImportWarning = false }) { Text("Cancel".tr, color = AccentGreen) }
            }
        )
    }

    pendingImportUri?.let { uri ->
        com.noslop.app.ui.components.CreatorPassphraseDialog(
            title = "Import Creator Identity".tr,
            message = "Enter the passphrase the file was saved with.".tr,
            confirmTwice = false,
            confirmLabel = "Import and Replace".tr,
            onConfirm = { passphrase ->
                pendingImportUri = null
                isImporting = true
                viewModel.restoreCreatorIdentityFromUri(context, passphrase, uri, replaceExisting = true) { result ->
                    isImporting = false
                    when (result) {
                        is com.noslop.app.data.CreatorRestoreResult.Restored -> importedCreator = result
                        is com.noslop.app.data.CreatorRestoreResult.Failed -> android.widget.Toast.makeText(
                            context,
                            com.noslop.app.util.LanguageManager.translate("Creator identity not restored: {reason}").replace("{reason}", result.reason),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        // Not returned when replacing; nothing was changed.
                        com.noslop.app.data.CreatorRestoreResult.ConfirmReplace -> Unit
                    }
                }
            },
            onDismiss = { pendingImportUri = null }
        )
    }

    importedCreator?.let { imported ->
        com.noslop.app.ui.components.CreatorRestoreResultDialog(result = imported, onDismiss = { importedCreator = null })
    }

    if (showBackupPassphrase) {
        com.noslop.app.ui.components.CreatorPassphraseDialog(
            title = "Back Up Creator Identity".tr,
            message = "The file holds your creator keys, Studio profile, followers, posts and their media. Anyone with the file and its passphrase can act as your creator identity.".tr,
            confirmTwice = true,
            confirmLabel = "Choose Where to Save".tr,
            onConfirm = { passphrase ->
                showBackupPassphrase = false
                pendingBackupPassphrase = passphrase
                val safeName = creatorHandle.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifBlank { "creator" }
                backupLauncher.launch("noslop-creator-$safeName.${com.noslop.app.data.CreatorIdentityArchive.FILE_EXTENSION}")
            },
            onDismiss = { showBackupPassphrase = false }
        )
    }

    if (showBurnConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBurnConfirmDialog = false },
            containerColor = SurfaceDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = DestructiveRed, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Burn Creator Identity?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "Burning your Creator Identity will permanently purge your secondary keypair from this device, unregister its Tor hidden service, broadcast a USER_EXIT to followers, and generate a brand-new Creator ID. ".tr +
                        "All current followers connected via your old Creator ID will permanently lose connectivity. Are you sure?".tr,
                        color = TextMuted
                    )
                    // D10d: point to the backup before the identity is gone for good.
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "A burned creator identity cannot be brought back. Back it up first if you may want it again.".tr,
                        color = DestructiveRed,
                        fontSize = 13.sp
                    )
                    TextButton(onClick = {
                        showBurnConfirmDialog = false
                        showBackupPassphrase = true
                    }) {
                        Text("Back Up First".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBurnConfirmDialog = false
                        viewModel.burnCreatorIdentity()
                        android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Creator Identity burned. New identity generated."), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                ) {
                    Text("Burn & Regenerate".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBurnConfirmDialog = false }) {
                    Text("Cancel".tr, color = AccentGreen)
                }
            }
        )
    }

    if (showCreatorIdSheet && burnableIdentity != null) {
        com.noslop.app.ui.QRShareSheet(
            handle = creatorHandle,
            localKeys = burnableIdentity!!,
            title = "Creator Contact Card".tr,
            subtitle = "Fans and peers can scan this QR code to connect with your Creator Node. This connection uses your severable secondary Tor address, keeping your personal identity private.".tr,
            isCreator = true,
            onDismiss = { showCreatorIdSheet = false }
        )
    }
}

/**
 * D04: the creator identity's profile as followers see it, with an edit button. D10d: a field left
 * blank on the Studio page shows the personal one, and the card says which fields those are.
 */
@Composable
private fun CreatorProfileCard(
    studio: com.noslop.app.data.UserProfile,
    shown: com.noslop.app.data.ResolvedProfile,
    onEdit: () -> Unit
) {
    val personalFields = listOfNotNull(
        "name".tr.takeIf { studio.displayName.isBlank() },
        "picture".tr.takeIf { studio.avatarB64.isNullOrBlank() },
        "bio".tr.takeIf { studio.bio.isBlank() }
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CreatorAvatar(avatarB64 = shown.avatarB64, size = 44)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Creator Profile".tr, fontSize = 12.sp, color = TextMuted)
                Text(shown.handle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextLight)
                shown.bio?.let { Text(it, fontSize = 11.sp, color = TextMuted, maxLines = 2) }
            }
            OutlinedButton(
                onClick = onEdit,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit".tr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            if (personalFields.isEmpty()) {
                "Followers see this profile. Your personal profile is not shown to them.".tr
            } else {
                "Uses your personal {fields}. Set them here to keep your creator identity apart from your personal profile.".tr
                    .replace("{fields}", personalFields.joinToString(", "))
            },
            fontSize = 11.sp,
            color = TextMuted,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun CreatorAvatar(avatarB64: String?, size: Int) {
    val bitmap = remember(avatarB64) {
        if (avatarB64.isNullOrBlank()) null else try {
            val bytes = android.util.Base64.decode(avatarB64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) { null }
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(PrimaryBlack),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = "Creator Avatar".tr,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Podcasts, contentDescription = null, tint = TextMuted, modifier = Modifier.size((size / 2).dp))
        }
    }
}

/**
 * D04: edits the creator profile (picture, name, bio). Starts from the creator profile only, never
 * from the personal one, so nothing personal is carried over by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorProfileEditor(
    current: com.noslop.app.data.UserProfile,
    onSave: (com.noslop.app.data.UserProfile) -> Unit,
    onBack: () -> Unit
) {
    var displayName by remember { mutableStateOf(current.displayName) }
    var bio by remember { mutableStateOf(current.bio) }
    var avatarB64 by remember { mutableStateOf(current.avatarB64) }
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        cropUri = uri
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onBack,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
    Surface(modifier = Modifier.fillMaxSize(), color = PrimaryBlack) {
    if (cropUri != null) {
        com.noslop.app.ui.components.AvatarCropper(
            uri = cropUri!!,
            onCropSuccess = { b64 ->
                avatarB64 = b64
                cropUri = null
            },
            onCancel = { cropUri = null }
        )
    } else {
    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
            }
            Text(
                text = "Edit Creator Profile".tr,
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.clickable { launcher.launch("image/*") }) {
                CreatorAvatar(avatarB64 = avatarB64, size = 80)
            }
            if (avatarB64 != null) {
                TextButton(onClick = { avatarB64 = null }) {
                    Text("Remove Picture".tr, color = DestructiveRed)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it.take(40) },
                label = { Text("Creator Name".tr) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("Bio".tr) },
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Leave a field empty to use your personal one. Anyone who sees both profiles can match the fields you leave empty.".tr,
                fontSize = 12.sp,
                color = TextMuted
            )
        }

        Button(
            onClick = {
                onSave(current.copy(displayName = displayName, bio = bio, avatarB64 = avatarB64))
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
        ) {
            Text("Save Creator Profile".tr, fontWeight = FontWeight.Bold)
        }
    }
    }
    }
    }
}

@Composable
private fun StudioItemCard(
    item: StudioItem,
    onPublish: (StudioItem) -> Unit,
    onDiscard: (String) -> Unit
) {
    var editableTitle by remember(item.id) { mutableStateOf(item.title) }
    var editableDescription by remember(item.id) { mutableStateOf(item.description) }
    var editableTags by remember(item.id) { mutableStateOf(item.tags.joinToString(", ")) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (item.mediaType == "video") Icons.Default.Videocam else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(item.mediaType.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                }
                IconButton(onClick = { onDiscard(item.id) }) {
                    Icon(Icons.Default.Close, contentDescription = "Discard".tr, tint = DestructiveRed, modifier = Modifier.size(18.dp))
                }
            }

            OutlinedTextField(
                value = editableTitle,
                onValueChange = { editableTitle = it },
                label = { Text("Title".tr) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            OutlinedTextField(
                value = editableDescription,
                onValueChange = { editableDescription = it },
                label = { Text("Description".tr) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            OutlinedTextField(
                value = editableTags,
                onValueChange = { editableTags = it },
                label = { Text("Tags (comma separated)".tr) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val updatedTags = editableTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onPublish(item.copy(title = editableTitle, description = editableDescription, tags = updatedTags))
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Publish to Mesh 🚀".tr, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * D10d: backs up the creator identity (keys, Studio profile, followers, posts and media) to a file.
 * D10e: and imports one, replacing this node's creator identity.
 */
@Composable
private fun CreatorBackupCard(isExporting: Boolean, isImporting: Boolean, onBackup: () -> Unit, onImport: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Creator Identity Backup".tr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextLight)
            Text(
                "Save your creator keys, Studio profile, followers, posts and media to one file protected by a passphrase. Import it here on a creator node, or restore it in Settings, Creator Node, on a node that is not a creator yet.".tr,
                fontSize = 11.sp,
                color = TextMuted,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            OutlinedButton(
                onClick = onBackup,
                enabled = !isExporting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isExporting) "Saving...".tr else "Back Up Creator Identity".tr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onImport,
                enabled = !isImporting && !isExporting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DestructiveRed),
                border = BorderStroke(1.dp, DestructiveRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isImporting) "Importing...".tr else "Import Creator Identity".tr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
