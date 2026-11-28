package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.data.LocalOcrAndDocumentParser
import com.example.i18n.AppStrings
import com.example.model.FlaggedScanItem
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.ScannedInvoiceLine
import com.example.model.UnitType
import com.example.ui.components.formatMoney
import com.example.ui.theme.CairoArabicFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState
import com.example.viewmodel.ScanUiState
import java.io.File
import java.util.Locale

@Composable
fun ScannerScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    onScanUri: (Uri) -> Unit,
    onScanError: (String) -> Unit,
    onScanText: (String) -> Unit,
    onRetryWithCloudAi: () -> Unit,
    onUpdateScannedLine: (Int, ScannedInvoiceLine) -> Unit,
    onRemoveScannedLine: (Int) -> Unit,
    onConvertAllPricesToUsd: (Double) -> Unit,
    onCommitScannedLines: (List<ScannedInvoiceLine>) -> Unit,
    onResetScan: () -> Unit,
    onApproveFlagged: (FlaggedScanItem, String, String, IngredientCategory, InventoryItem?) -> Unit,
    onDismissFlagged: (String) -> Unit
) {
    val context = LocalContext.current
    val currency = uiState.profile?.currencySymbol ?: "$"
    val isScanning = uiState.scanState is ScanUiState.Scanning

    // Pre-warm on-device ML Kit OCR engine so first scan runs instantly
    LaunchedEffect(Unit) {
        LocalOcrAndDocumentParser.warmUp()
    }

    // rememberSaveable preserves capture state if the system camera rotates or recreates the Activity
    var rawInvoiceText by rememberSaveable { mutableStateOf("") }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPermissionMessage by remember { mutableStateOf<String?>(null) }
    var reviewTarget by remember { mutableStateOf<FlaggedScanItem?>(null) }
    var editLineTarget by remember { mutableStateOf<Pair<Int, ScannedInvoiceLine>?>(null) }
    var exchangeDivisorText by rememberSaveable { mutableStateOf("89500") }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val savedUriStr = pendingCameraUriString
        val savedPath = pendingCameraFilePath
        pendingCameraUriString = null
        pendingCameraFilePath = null

        val fileHasData = savedPath != null && File(savedPath).let { it.exists() && it.length() > 0L }
        val uri = savedUriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }

        when {
            uri != null && (success || fileHasData) -> {
                if (savedPath != null && !fileHasData) {
                    onScanError("Camera returned an empty photo. Please capture the invoice again.")
                } else {
                    cameraPermissionMessage = null
                    onScanUri(uri)
                }
            }
            success && uri == null -> {
                onScanError("Captured photo reference was lost. Please take the photo again.")
            }
            else -> {
                // User cancelled the camera cleanly
                savedPath?.let { runCatching { File(it).delete() } }
            }
        }
    }

    fun launchCameraCapture() {
        runCatching {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "invoice_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUriString = uri.toString()
            pendingCameraFilePath = file.absolutePath
            takePictureLauncher.launch(uri)
        }.onFailure { err ->
            pendingCameraUriString = null
            pendingCameraFilePath = null
            onScanError(
                err.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Could not open camera on this device. Try Photo Gallery or Upload PDF instead."
            )
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionMessage = null
            launchCameraCapture()
        } else {
            cameraPermissionMessage = "Camera permission denied. You can still upload an invoice photo, PDF file, or paste text below."
        }
    }

    // Zero-permission Android Photo Picker per Google Play policy
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            cameraPermissionMessage = null
            onScanUri(uri)
        }
    }

    // Zero-permission Storage Access Framework document picker for PDF & image invoice files
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            cameraPermissionMessage = null
            onScanUri(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scanner_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Minimal Editorial Header + Capture Actions
        item(key = "scanner_header") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.scanInvoiceTitle,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Local instant scan · Photos, PDF, Excel, Word",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Instant Local Scan",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Instant",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

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
                            enabled = !isScanning,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("camera_scan_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = strings.cameraScan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.cameraScan, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                runCatching {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }.onFailure {
                                    documentPickerLauncher.launch(arrayOf("image/*", "application/pdf"))
                                }
                            },
                            enabled = !isScanning,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("gallery_scan_button")
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = strings.galleryScan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.galleryScan, maxLines = 1)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "image/*",
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/csv",
                                        "text/plain"
                                    )
                                )
                            }.onFailure { err ->
                                onScanError(err.localizedMessage ?: "Could not open file picker.")
                            }
                        },
                        enabled = !isScanning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("upload_pdf_invoice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = "Upload PDF, Excel, Word, or Image File",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Upload Invoice (PDF / Excel / Word / Photo)")
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
                        enabled = rawInvoiceText.isNotBlank() && !isScanning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("analyze_invoice_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = "Scan Text Locally", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Text Instantly (Local)")
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
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            scan.preview?.let { thumb ->
                                InvoicePreviewThumb(bitmap = thumb)
                            }
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Text(
                                text = scan.statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
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
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                scan.preview?.let { thumb ->
                                    InvoicePreviewThumb(bitmap = thumb)
                                }
                                Text(
                                    text = scan.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onResetScan) { Text("Clear") }
                            }
                            if (scan.rawExtractedText.isNotBlank()) {
                                FilledTonalButton(
                                    onClick = {
                                        rawInvoiceText = scan.rawExtractedText
                                        onResetScan()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("edit_detected_ocr_text_btn")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Detected Text Locally", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Edit Detected Text Locally")
                                }
                            }
                            if (scan.canRetryWithCloudAi) {
                                FilledTonalButton(
                                    onClick = onRetryWithCloudAi,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("retry_cloud_ai_error_btn")
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Retry with Cloud AI", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry with Cloud AI (Arabic / Handwritten)")
                                }
                            }
                        }
                    }
                }
            }

            is ScanUiState.Results -> {
                val hasNonUsdLine = scan.lines.any { it.detectedCurrency != "$" }
                item(key = "scan_results_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                scan.preview?.let { thumb ->
                                    InvoicePreviewThumb(bitmap = thumb)
                                }
                                Column {
                                    Text(
                                        text = "Review & Edit (${scan.lines.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val speedLabel = buildString {
                                        append(if (scan.usedLocalScan) "Local on-device scan" else "Cloud AI scan")
                                        scan.elapsedMs?.let { ms ->
                                            val formatted = if (ms < 1000L) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)
                                            append(" • $formatted")
                                        }
                                    }
                                    Text(
                                        text = speedLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

                        // Multi-currency USD converter (essential for LBP / EUR / AED / SAR invoices)
                        Surface(
                            color = if (hasNonUsdLine) {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (hasNonUsdLine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CurrencyExchange,
                                        contentDescription = "Convert currency to USD",
                                        tint = if (hasNonUsdLine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (hasNonUsdLine) {
                                            "Non-$ Currency Detected — Convert All Prices to $"
                                        } else {
                                            "Invoice in another currency? Convert all prices to $"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = exchangeDivisorText == "89500",
                                        onClick = { exchangeDivisorText = "89500" },
                                        label = { Text("LBP ÷ 89,500") }
                                    )
                                    FilterChip(
                                        selected = exchangeDivisorText == "0.92",
                                        onClick = { exchangeDivisorText = "0.92" },
                                        label = { Text("EUR ÷ 0.92") }
                                    )
                                    FilterChip(
                                        selected = exchangeDivisorText == "3.67",
                                        onClick = { exchangeDivisorText = "3.67" },
                                        label = { Text("AED ÷ 3.67") }
                                    )
                                    FilterChip(
                                        selected = exchangeDivisorText == "3.75",
                                        onClick = { exchangeDivisorText = "3.75" },
                                        label = { Text("SAR ÷ 3.75") }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = exchangeDivisorText,
                                        onValueChange = { exchangeDivisorText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                        label = { Text("Rate (Divisor to $)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("currency_divisor_input")
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            val div = exchangeDivisorText.toDoubleOrNull()
                                            if (div != null && div > 0.0) {
                                                onConvertAllPricesToUsd(div)
                                            }
                                        },
                                        enabled = (exchangeDivisorText.toDoubleOrNull() ?: 0.0) > 0.0,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("convert_all_usd_btn")
                                    ) {
                                        Icon(Icons.Default.AttachMoney, contentDescription = "Convert to USD", modifier = Modifier.size(16.dp))
                                        Text("Convert All")
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Tap any item below to edit Name (match exact POS/Stock name), Quantity, Unit, or Price in $ before importing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (scan.canRetryWithCloudAi) {
                            OutlinedButton(
                                onClick = onRetryWithCloudAi,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("retry_cloud_ai_results_btn")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Retry with Cloud AI", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Handwritten or Arabic receipt? Retry with Cloud AI")
                            }
                        }
                    }
                }
                itemsIndexed(scan.lines) { index, line ->
                    ScannedLineCard(
                        line = line,
                        currency = currency,
                        index = index,
                        onEditClick = { editLineTarget = index to line },
                        onDeleteClick = { onRemoveScannedLine(index) }
                    )
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

    editLineTarget?.let { (lineIndex, targetLine) ->
        EditScannedLineDialog(
            line = targetLine,
            inventory = uiState.inventory,
            recipes = uiState.recipes,
            currency = currency,
            strings = strings,
            onDismiss = { editLineTarget = null },
            onSave = { updated ->
                onUpdateScannedLine(lineIndex, updated)
                editLineTarget = null
            }
        )
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
private fun InvoicePreviewThumb(bitmap: Bitmap) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = "Invoice preview",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun ScannedLineCard(
    line: ScannedInvoiceLine,
    currency: String,
    index: Int,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val isNonUsd = line.detectedCurrency != "$"
    val accent = when {
        isNonUsd || line.needsManualReview -> MaterialTheme.colorScheme.tertiary
        line.matchedInventoryId != null -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditClick)
            .testTag("scanned_line_card_$index")
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
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = line.nameEn.ifBlank { line.rawLabel },
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = CairoArabicFamily),
                        fontWeight = FontWeight.Bold
                    )
                    if (line.rawLabel.isNotBlank() && !line.rawLabel.equals(line.nameEn, ignoreCase = true)) {
                        Text(
                            text = "Invoice text: ${line.rawLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (line.matchedInventoryId != null) Icons.Default.MergeType else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (line.needsManualReview) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = when {
                                line.matchedInventoryId != null -> buildString {
                                    append("Matched POS/Stock: ")
                                    append(line.nameEn)
                                    if (line.nameAr.isNotBlank()) append(" / ${line.nameAr}")
                                }
                                line.needsManualReview -> "Needs Review: ${line.reviewReason}"
                                else -> "New Item (exact invoice name) — tap to link POS name"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (line.needsManualReview) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (isNonUsd) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Currency: ${line.detectedCurrency} — Edit or convert to $",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${line.quantity} ${line.unit.symbol}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isNonUsd) {
                            "${String.format(Locale.US, "%,.2f", line.totalCost)} ${line.detectedCurrency}"
                        } else {
                            formatMoney(line.totalCost, currency)
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = if (isNonUsd) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("edit_scanned_line_btn_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit scanned item",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("delete_scanned_line_btn_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Remove scanned item",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditScannedLineDialog(
    line: ScannedInvoiceLine,
    inventory: List<InventoryItem>,
    recipes: List<Recipe>,
    currency: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (ScannedInvoiceLine) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var matchedInventoryId by remember(line) { mutableStateOf(line.matchedInventoryId) }
    var nameEn by remember(line) { mutableStateOf(line.nameEn.ifBlank { line.rawLabel }) }
    var nameAr by remember(line) { mutableStateOf(line.nameAr) }
    var quantityText by remember(line) {
        mutableStateOf(
            String.format(Locale.US, "%.3f", line.quantity).trimEnd('0').trimEnd('.')
        )
    }
    var unit by remember(line) { mutableStateOf(line.unit) }
    var totalCostText by remember(line) {
        mutableStateOf(
            String.format(Locale.US, "%.2f", line.totalCost)
        )
    }
    var category by remember(line) { mutableStateOf(line.category) }

    val filteredInventory = remember(searchQuery, inventory) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) inventory.take(12)
        else inventory.filter {
            it.nameEn.lowercase().contains(q) || it.nameAr.contains(q)
        }.take(12)
    }

    val filteredRecipes = remember(searchQuery, recipes) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) recipes.take(8)
        else recipes.filter {
            it.nameEn.lowercase().contains(q) ||
                it.nameAr.contains(q) ||
                it.posSku.orEmpty().lowercase().contains(q)
        }.take(8)
    }

    val parsedQty = quantityText.toDoubleOrNull() ?: 0.0
    val parsedPrice = totalCostText.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Scanned Invoice Item",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Edit Scanned Item") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Raw invoice line: \"${line.rawLabel}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Match directly to existing Stock or POS/Recipe names so stock stays accurate
                if (inventory.isNotEmpty() || recipes.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Match Existing POS / Stock / Recipe Name") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_line_pos_search_input")
                    )

                    if (filteredInventory.isNotEmpty()) {
                        Text(
                            text = "Existing Stock Items (tap to link & avoid duplicates):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = matchedInventoryId == null,
                                onClick = { matchedInventoryId = null },
                                label = { Text("Custom / New") }
                            )
                            filteredInventory.forEach { item ->
                                FilterChip(
                                    selected = matchedInventoryId == item.id,
                                    onClick = {
                                        matchedInventoryId = item.id
                                        nameEn = item.nameEn
                                        nameAr = item.nameAr
                                        category = item.categoryEnum
                                        unit = item.unitEnum
                                    },
                                    label = { Text("${item.nameEn} (${item.unit})") }
                                )
                            }
                        }
                    }

                    if (filteredRecipes.isNotEmpty()) {
                        Text(
                            text = "POS / Recipe Names (tap to use exact POS name):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            filteredRecipes.forEach { recipe ->
                                FilterChip(
                                    selected = nameEn.equals(recipe.nameEn, ignoreCase = true),
                                    onClick = {
                                        nameEn = recipe.nameEn
                                        if (recipe.nameAr.isNotBlank()) nameAr = recipe.nameAr
                                        val matchingInv = inventory.firstOrNull {
                                            it.nameEn.equals(recipe.nameEn, ignoreCase = true)
                                        }
                                        matchedInventoryId = matchingInv?.id
                                    },
                                    label = {
                                        Text(
                                            if (!recipe.posSku.isNullOrBlank()) "${recipe.nameEn} [${recipe.posSku}]"
                                            else recipe.nameEn
                                        )
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                OutlinedTextField(
                    value = nameEn,
                    onValueChange = {
                        nameEn = it
                        // If user manually renames away from the linked item, unlink so we don't overwrite the wrong stock item
                        val linked = inventory.firstOrNull { inv -> inv.id == matchedInventoryId }
                        if (linked != null && !linked.nameEn.equals(it.trim(), ignoreCase = true)) {
                            matchedInventoryId = null
                        }
                    },
                    label = { Text("Item Name (English / POS Name) *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_line_name_input")
                )

                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it },
                    label = { Text("Arabic Name (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Quantity *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("edit_line_qty_input")
                    )
                    OutlinedTextField(
                        value = totalCostText,
                        onValueChange = { totalCostText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Total Price ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("edit_line_price_input")
                    )
                }

                // Inline 1-tap LBP -> USD helper for this single line
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (parsedQty > 0.0) {
                            "Unit cost: ${formatMoney(parsedPrice / parsedQty, currency)} / ${unit.symbol}"
                        } else {
                            "Enter accurate quantity to prevent fake overstock"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val raw = totalCostText.toDoubleOrNull() ?: 0.0
                            if (raw > 0.0) {
                                totalCostText = String.format(Locale.US, "%.2f", raw / 89500.0)
                            }
                        },
                        modifier = Modifier.testTag("edit_line_lbp_to_usd_btn")
                    ) {
                        Text("LBP ÷ 89.5k → $")
                    }
                }

                Text("Unit", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UnitType.entries.forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u.symbol) }
                        )
                    }
                }

                Text("Category", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedEn = nameEn.trim()
                    val canonical = trimmedEn.lowercase()
                        .replace(Regex("[^a-z0-9\\u0600-\\u06FF]+"), "_")
                        .trim('_')
                        .ifBlank { line.canonicalKey }
                    onSave(
                        line.copy(
                            nameEn = trimmedEn,
                            nameAr = nameAr.trim(),
                            canonicalKey = canonical,
                            category = category,
                            quantity = parsedQty.coerceAtLeast(0.001),
                            unit = unit,
                            totalCost = parsedPrice.coerceAtLeast(0.0),
                            detectedCurrency = "$",
                            matchedInventoryId = matchedInventoryId,
                            needsManualReview = false,
                            reviewReason = ""
                        )
                    )
                },
                enabled = nameEn.isNotBlank() && parsedQty > 0.0,
                modifier = Modifier.testTag("save_edited_scanned_line_btn")
            ) {
                Text("Save Line")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
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
