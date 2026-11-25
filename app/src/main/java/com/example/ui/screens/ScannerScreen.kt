package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.i18n.AppStrings
import com.example.model.FlaggedScanItem
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.ScannedInvoiceLine
import com.example.ui.components.formatMoney
import com.example.ui.theme.CairoArabicFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState
import com.example.viewmodel.ScanUiState
import java.io.File

@Composable
fun ScannerScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    onScanBitmap: (Bitmap) -> Unit,
    onScanText: (String) -> Unit,
    onCommitScannedLines: (List<ScannedInvoiceLine>) -> Unit,
    onResetScan: () -> Unit,
    onApproveFlagged: (FlaggedScanItem, String, String, IngredientCategory, InventoryItem?) -> Unit,
    onDismissFlagged: (String) -> Unit
) {
    val context = LocalContext.current
    val currency = uiState.profile?.currencySymbol ?: "$"

    var rawInvoiceText by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraPermissionMessage by remember { mutableStateOf<String?>(null) }
    var reviewTarget by remember { mutableStateOf<FlaggedScanItem?>(null) }

    fun decodeUriToBitmap(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            decodeUriToBitmap(uri)?.let(onScanBitmap)
        }
    }

    fun launchCameraCapture() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "invoice_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionMessage = null
            launchCameraCapture()
        } else {
            cameraPermissionMessage = "Camera permission denied. You can still pick an invoice image or paste text below."
        }
    }

    // Zero-permission Android Photo Picker per Google Play policy
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            decodeUriToBitmap(uri)?.let(onScanBitmap)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scanner_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Capture Actions Card
        item(key = "scanner_header") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = strings.scanInvoiceTitle,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = strings.scanInvoiceTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Reads Arabic & English labels automatically, merges equivalents (e.g. دجاج + Chicken), and flags unknown supplier codes for human review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val hasCam = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasCam) launchCameraCapture()
                                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("camera_scan_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = strings.cameraScan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.cameraScan)
                        }
                        FilledTonalButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("gallery_scan_button")
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = strings.galleryScan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.galleryScan)
                        }
                    }

                    if (!cameraPermissionMessage.isNullOrBlank()) {
                        Text(
                            text = cameraPermissionMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    OutlinedTextField(
                        value = rawInvoiceText,
                        onValueChange = { rawInvoiceText = it },
                        label = { Text(strings.pasteInvoiceText) },
                        placeholder = { Text("2kg دجاج $20\n1kg Tomatoes $3.50\n1 box SKU-99X $14") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("invoice_text_input")
                    )

                    Button(
                        onClick = { onScanText(rawInvoiceText) },
                        enabled = rawInvoiceText.isNotBlank() && uiState.scanState !is ScanUiState.Scanning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("analyze_invoice_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = strings.analyzeWithAi, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.analyzeWithAi)
                    }
                }
            }
        }

        // Active AI Scan State
        when (val scan = uiState.scanState) {
            is ScanUiState.Scanning -> {
                item(key = "scan_loading") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Text("Analyzing Arabic & English invoice lines with Gemini AI...")
                        }
                    }
                }
            }

            is ScanUiState.Error -> {
                item(key = "scan_error") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = scan.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onResetScan) { Text("Clear") }
                        }
                    }
                }
            }

            is ScanUiState.Results -> {
                item(key = "scan_results_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Extracted Lines (${scan.lines.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onResetScan) { Text(strings.cancel) }
                            Button(
                                onClick = {
                                    onCommitScannedLines(scan.lines)
                                    rawInvoiceText = ""
                                },
                                modifier = Modifier.testTag("commit_scan_results_button")
                            ) {
                                Text("Import All")
                            }
                        }
                    }
                }
                items(scan.lines) { line ->
                    ScannedLineCard(line = line, currency = currency)
                }
            }

            ScanUiState.Idle -> Unit
        }

        // Manual Human Review Queue (Flagged Unknown Items)
        item(key = "flagged_queue_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = strings.flaggedReviewQueue,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "${strings.flaggedReviewQueue} (${uiState.pendingFlaggedScans.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (uiState.pendingFlaggedScans.isEmpty()) {
            item(key = "empty_review_queue") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No unrecognized invoice items pending manual review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(uiState.pendingFlaggedScans, key = { it.id }) { flagged ->
                FlaggedReviewCard(
                    flagged = flagged,
                    currency = currency,
                    strings = strings,
                    canApprove = uiState.activePermissions.canApproveScans,
                    onReviewClick = { reviewTarget = flagged },
                    onDismissClick = { onDismissFlagged(flagged.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    reviewTarget?.let { target ->
        ManualReviewResolveDialog(
            flagged = target,
            inventory = uiState.inventory,
            strings = strings,
            onDismiss = { reviewTarget = null },
            onApprove = { en, ar, cat, existing ->
                onApproveFlagged(target, en, ar, cat, existing)
                reviewTarget = null
            }
        )
    }
}

@Composable
private fun ScannedLineCard(
    line: ScannedInvoiceLine,
    currency: String
) {
    val accent = if (line.needsManualReview) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.rawLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = CairoArabicFamily),
                        fontWeight = FontWeight.Bold
                    )
                    if (!line.needsManualReview) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (line.matchedInventoryId != null) Icons.Default.MergeType else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = buildString {
                                    append(if (line.matchedInventoryId != null) "Merges into: " else "New Unified: ")
                                    append(line.nameEn)
                                    if (line.nameAr.isNotBlank()) append(" / ${line.nameAr}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    } else {
                        Text(
                            text = "Flagged for Review: ${line.reviewReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${line.quantity} ${line.unit.symbol}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatMoney(line.totalCost, currency),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun FlaggedReviewCard(
    flagged: FlaggedScanItem,
    currency: String,
    strings: AppStrings,
    canApprove: Boolean,
    onReviewClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.testTag("flagged_card_${flagged.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = flagged.rawLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = CairoArabicFamily),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = flagged.reviewReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${flagged.quantity} ${flagged.unit} • ${formatMoney(flagged.totalCost, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReviewClick,
                    enabled = canApprove,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("approve_flagged_btn_${flagged.id}")
                ) {
                    Text(strings.approveAndMerge)
                }
                OutlinedButton(
                    onClick = onDismissClick,
                    enabled = canApprove,
                    modifier = Modifier.testTag("dismiss_flagged_btn_${flagged.id}")
                ) {
                    Icon(Icons.Default.Close, contentDescription = strings.dismiss, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.dismiss)
                }
            }
            if (!canApprove) {
                Text(
                    text = "Current staff role does not have permission to approve flagged scans.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ManualReviewResolveDialog(
    flagged: FlaggedScanItem,
    inventory: List<InventoryItem>,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onApprove: (String, String, IngredientCategory, InventoryItem?) -> Unit
) {
    var linkedExisting by remember { mutableStateOf<InventoryItem?>(null) }
    var nameEn by remember { mutableStateOf(flagged.suggestedNameEn.ifBlank { flagged.rawLabel }) }
    var nameAr by remember { mutableStateOf(flagged.suggestedNameAr) }
    var category by remember { mutableStateOf(IngredientCategory.OTHER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Ingredient Review") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Scanned Label: \"${flagged.rawLabel}\" (${flagged.quantity} ${flagged.unit})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (inventory.isNotEmpty()) {
                    Text(
                        text = "Link to existing ingredient (adds alias permanently):",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = linkedExisting == null,
                            onClick = { linkedExisting = null },
                            label = { Text("Create New") }
                        )
                        inventory.take(12).forEach { item ->
                            FilterChip(
                                selected = linkedExisting?.id == item.id,
                                onClick = {
                                    linkedExisting = item
                                    nameEn = item.nameEn
                                    nameAr = item.nameAr
                                    category = item.categoryEnum
                                },
                                label = { Text(item.nameEn) }
                            )
                        }
                    }
                }

                if (linkedExisting == null) {
                    OutlinedTextField(
                        value = nameEn,
                        onValueChange = { nameEn = it },
                        label = { Text("Confirmed Name (English)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nameAr,
                        onValueChange = { nameAr = it },
                        label = { Text("Arabic Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IngredientCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat.name.replace('_', ' ')) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApprove(nameEn, nameAr, category, linkedExisting) },
                enabled = linkedExisting != null || nameEn.isNotBlank()
            ) {
                Text(strings.approveAndMerge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
