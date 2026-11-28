package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.data.ParsedRecipeDraft
import com.example.data.RecipeDocumentService
import com.example.data.SavedExportFile
import com.example.model.Recipe
import com.example.model.RecipeType
import com.example.viewmodel.RecipeImportUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val MIME_PDF = "application/pdf"
private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

@Composable
fun ImportRecipeSourceDialog(
    onFilePicked: (Uri) -> Unit,
    showBarrio67Option: Boolean,
    onLoadBarrio67Book: () -> Unit,
    barrioBookImportBusy: Boolean,
    onFeedback: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onDismiss()
            onFilePicked(uri)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onDismiss()
            onFilePicked(uri)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val savedUriStr = pendingCameraUriString
        val savedPath = pendingCameraFilePath
        pendingCameraUriString = null
        pendingCameraFilePath = null

        val fileHasData = savedPath != null && File(savedPath).let { it.exists() && it.length() > 0L }
        val uri = savedUriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri != null && (success || fileHasData) && fileHasData) {
            onDismiss()
            onFilePicked(uri)
        } else {
            savedPath?.let { runCatching { File(it).delete() } }
        }
    }

    fun launchCamera() {
        runCatching {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "recipe_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUriString = uri.toString()
            pendingCameraFilePath = file.absolutePath
            takePictureLauncher.launch(uri)
        }.onFailure { err ->
            pendingCameraUriString = null
            pendingCameraFilePath = null
            onFeedback(err.localizedMessage ?: "Could not open camera")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else onFeedback("Camera permission denied. Pick a photo from gallery or upload Excel/Word/PDF.")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.UploadFile,
                contentDescription = "Upload Recipes",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Upload Recipes") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Choose how you want to import recipes or sub-recipes. Everything is read locally on your device first and matched to your stock automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (showBarrio67Option) {
                    ImportFormatOptionCard(
                        icon = Icons.Default.MenuBook,
                        title = if (barrioBookImportBusy) "Loading Barrio 67 Recipe Book..." else "Barrio 67 — Omega POS Book",
                        subtitle = "187 menu dishes, 72 production sub-recipes & 265 raw POS ingredients",
                        testTag = "import_barrio67_book_dialog_btn",
                        onClick = {
                            if (!barrioBookImportBusy) {
                                onDismiss()
                                onLoadBarrio67Book()
                            }
                        }
                    )
                }

                ImportFormatOptionCard(
                    icon = Icons.Default.PictureAsPdf,
                    title = "PDF Document (.pdf)",
                    subtitle = "Scan printed or exported PDF recipe books locally on device",
                    testTag = "import_recipe_pdf_btn",
                    onClick = {
                        runCatching {
                            openDocLauncher.launch(arrayOf(MIME_PDF, "application/x-pdf", "application/octet-stream"))
                        }.onFailure { onFeedback("Could not open file picker") }
                    }
                )

                ImportFormatOptionCard(
                    icon = Icons.Default.TableChart,
                    title = "Excel Spreadsheet (.xlsx / .xls / .csv)",
                    subtitle = "Import single or multiple recipes from Excel sheets or ChefsMate exports",
                    testTag = "import_recipe_excel_btn",
                    onClick = {
                        runCatching {
                            openDocLauncher.launch(
                                arrayOf(
                                    MIME_XLSX,
                                    "application/vnd.ms-excel",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/csv",
                                    "text/plain",
                                    "application/octet-stream"
                                )
                            )
                        }.onFailure { onFeedback("Could not open file picker") }
                    }
                )

                ImportFormatOptionCard(
                    icon = Icons.Default.Description,
                    title = "Word Document (.docx)",
                    subtitle = "Import recipe sheets & ingredient tables from Microsoft Word files",
                    testTag = "import_recipe_word_btn",
                    onClick = {
                        runCatching {
                            openDocLauncher.launch(arrayOf(MIME_DOCX, "application/msword", "application/octet-stream"))
                        }.onFailure { onFeedback("Could not open file picker") }
                    }
                )

                OutlinedButton(
                    onClick = {
                        runCatching {
                            openDocLauncher.launch(arrayOf("*/*"))
                        }.onFailure { onFeedback("Could not open file picker") }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("import_recipe_any_file_btn")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse any recipe file", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Browse Any File (PDF / Excel / Word)")
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Photo of Recipe Card or Notebook",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val hasCam = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasCam) launchCamera()
                                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_recipe_camera_btn")
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Take photo", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Camera")
                            }
                            FilledTonalButton(
                                onClick = {
                                    runCatching {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }.onFailure {
                                        openDocLauncher.launch(arrayOf("image/*"))
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_recipe_gallery_btn")
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick photo", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Gallery")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ImportFormatOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ImportRecipesPreviewDialog(
    state: RecipeImportUiState,
    onRetryWithCloudAi: () -> Unit,
    onConfirmImport: (List<ParsedRecipeDraft>) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        RecipeImportUiState.Idle -> Unit

        is RecipeImportUiState.Importing -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Importing Recipes") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            )
        }

        is RecipeImportUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Could Not Read Recipes") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (state.canRetryWithCloudAi) {
                            FilledTonalButton(
                                onClick = onRetryWithCloudAi,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recipe_import_retry_ai_btn")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Read with Cloud AI", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Read with Cloud AI (Arabic / Handwritten)")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            )
        }

        is RecipeImportUiState.Preview -> {
            val selectedMap = remember(state.drafts) {
                mutableStateMapOf<String, Boolean>().apply {
                    state.drafts.forEach { put(it.id, true) }
                }
            }
            val titleEdits = remember(state.drafts) {
                mutableStateMapOf<String, String>().apply {
                    state.drafts.forEach { put(it.id, it.nameEn) }
                }
            }
            val typeEdits = remember(state.drafts) {
                mutableStateMapOf<String, RecipeType>().apply {
                    state.drafts.forEach { put(it.id, it.recipeType) }
                }
            }

            val selectedDrafts = state.drafts.filter { selectedMap[it.id] == true }.map { draft ->
                draft.copy(
                    nameEn = titleEdits[draft.id]?.trim()?.ifBlank { draft.nameEn } ?: draft.nameEn,
                    recipeType = typeEdits[draft.id] ?: draft.recipeType
                )
            }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Review Imported Recipes (${state.drafts.size})") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Source: ${state.sourceLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (state.canRetryWithCloudAi) {
                            OutlinedButton(
                                onClick = onRetryWithCloudAi,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Re-read with Cloud AI", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Handwritten / Arabic? Re-read with Cloud AI")
                            }
                        }

                        state.drafts.forEach { draft ->
                            val isChecked = selectedMap[draft.id] == true
                            val currentType = typeEdits[draft.id] ?: draft.recipeType
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { selectedMap[draft.id] = it }
                                        )
                                        OutlinedTextField(
                                            value = titleEdits[draft.id] ?: draft.nameEn,
                                            onValueChange = { titleEdits[draft.id] = it },
                                            label = { Text("Recipe Name") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = currentType == RecipeType.MENU_ITEM,
                                            onClick = { typeEdits[draft.id] = RecipeType.MENU_ITEM },
                                            label = { Text("Menu Dish") }
                                        )
                                        FilterChip(
                                            selected = currentType == RecipeType.SUB_RECIPE,
                                            onClick = { typeEdits[draft.id] = RecipeType.SUB_RECIPE },
                                            label = { Text("Sub-Recipe") }
                                        )
                                    }

                                    Text(
                                        text = "${draft.lines.size} ingredient(s) • ${draft.matchedIngredientsCount} matched to stock" +
                                            if (draft.menuPrice > 0.0) " • Price ${String.format(Locale.US, "%.2f", draft.menuPrice)}" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )

                                    draft.lines.take(6).forEach { line ->
                                        val isLinked = !line.itemId.startsWith("unlinked_")
                                        Text(
                                            text = "• ${line.itemName}: ${fmtDraftQty(line.quantity)} ${line.unit}" +
                                                if (!isLinked) " (add to stock later to price)" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isLinked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (draft.lines.size > 6) {
                                        Text(
                                            text = "+ ${draft.lines.size - 6} more ingredient(s)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onConfirmImport(selectedDrafts) },
                        enabled = selectedDrafts.isNotEmpty(),
                        modifier = Modifier.testTag("confirm_import_recipes_btn")
                    ) {
                        Text("Import Selected (${selectedDrafts.size})")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            )
        }
    }
}

private enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF Recipe Book (.pdf)", "pdf", MIME_PDF),
    EXCEL("Excel Workbook (.xlsx)", "xlsx", MIME_XLSX)
}

private enum class ExportScope(val label: String) {
    ALL("All Recipes"),
    MENU_ONLY("Menu Dishes"),
    SUB_ONLY("Sub-Recipes")
}

@Composable
fun ExportRecipesDialog(
    singleRecipe: Recipe?,
    allRecipes: List<Recipe>,
    menuRecipes: List<Recipe>,
    subRecipes: List<Recipe>,
    onGeneratePdf: (List<Recipe>) -> ByteArray,
    onGenerateXlsx: (List<Recipe>) -> ByteArray,
    onSaveToCustomFolder: (recipes: List<Recipe>, isPdf: Boolean, suggestedFileName: String) -> Unit,
    onFeedback: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docHelper = remember { RecipeDocumentService() }

    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var exportScope by remember { mutableStateOf(ExportScope.ALL) }
    var busy by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<SavedExportFile?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val targetRecipes = remember(singleRecipe, exportScope, allRecipes, menuRecipes, subRecipes) {
        if (singleRecipe != null) {
            listOf(singleRecipe)
        } else {
            when (exportScope) {
                ExportScope.ALL -> allRecipes
                ExportScope.MENU_ONLY -> menuRecipes
                ExportScope.SUB_ONLY -> subRecipes
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download Recipes",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                if (singleRecipe != null) "Download \"${singleRecipe.nameEn}\""
                else "Download Recipes"
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Export formatted recipes with exact ingredient quantities, plate costs, and profit margins directly to Downloads/ChefsMate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Format",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = format == ExportFormat.PDF,
                        onClick = {
                            format = ExportFormat.PDF
                            savedFile = null
                            errorMessage = null
                        },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("PDF Book") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_format_pdf_chip")
                    )
                    FilterChip(
                        selected = format == ExportFormat.EXCEL,
                        onClick = {
                            format = ExportFormat.EXCEL
                            savedFile = null
                            errorMessage = null
                        },
                        leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Excel (.xlsx)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_format_excel_chip")
                    )
                }

                if (singleRecipe == null) {
                    Text(
                        text = "Include",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = exportScope == ExportScope.ALL,
                            onClick = {
                                exportScope = ExportScope.ALL
                                savedFile = null
                                errorMessage = null
                            },
                            label = { Text("All (${allRecipes.size})") }
                        )
                        FilterChip(
                            selected = exportScope == ExportScope.MENU_ONLY,
                            onClick = {
                                exportScope = ExportScope.MENU_ONLY
                                savedFile = null
                                errorMessage = null
                            },
                            label = { Text("Menu (${menuRecipes.size})") }
                        )
                        FilterChip(
                            selected = exportScope == ExportScope.SUB_ONLY,
                            onClick = {
                                exportScope = ExportScope.SUB_ONLY
                                savedFile = null
                                errorMessage = null
                            },
                            label = { Text("Prep (${subRecipes.size})") }
                        )
                    }
                }

                if (targetRecipes.isEmpty()) {
                    Text(
                        text = "No recipes in this selection yet. Choose another filter above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                savedFile?.let { saved ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_saved_confirmation_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Download complete",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Downloaded Successfully",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "Saved to ${saved.displayLocation}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val openIntent = docHelper.createOpenFileIntent(
                                            shareableUri = saved.shareableUri,
                                            mimeType = saved.mimeType
                                        )
                                        runCatching {
                                            context.startActivity(openIntent)
                                        }.onFailure {
                                            errorMessage = "Saved to ${saved.displayLocation}. No PDF/Excel viewer app found — tap Share to open in another app."
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("open_exported_recipes_btn")
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = "Open file", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open File")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        val shareIntent = docHelper.createShareChooserIntent(
                                            shareableUri = saved.shareableUri,
                                            mimeType = saved.mimeType,
                                            subject = "ChefsMate Recipes (${targetRecipes.size})"
                                        )
                                        runCatching {
                                            context.startActivity(shareIntent)
                                        }.onFailure {
                                            errorMessage = "No app available to share this file."
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("share_saved_recipes_btn")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share file", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 1-Tap Direct Download to Downloads/ChefsMate
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMessage = null
                            val saveResult = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = if (format == ExportFormat.PDF) {
                                        onGeneratePdf(targetRecipes)
                                    } else {
                                        onGenerateXlsx(targetRecipes)
                                    }
                                    val fileName = if (format == ExportFormat.PDF) {
                                        docHelper.suggestedPdfFileName(singleRecipe?.nameEn)
                                    } else {
                                        docHelper.suggestedXlsxFileName(singleRecipe?.nameEn)
                                    }
                                    docHelper.saveToDownloads(
                                        context = context,
                                        fileName = fileName,
                                        mimeType = format.mime,
                                        bytes = bytes
                                    ).getOrThrow()
                                }
                            }
                            busy = false
                            saveResult.onSuccess { saved ->
                                savedFile = saved
                                onFeedback("Saved ${targetRecipes.size} recipe(s) to ${saved.displayLocation}")
                            }.onFailure { err ->
                                val msg = err.localizedMessage ?: "Failed to save ${format.ext.uppercase(Locale.US)} file"
                                errorMessage = msg
                                onFeedback(msg)
                            }
                        }
                    },
                    enabled = !busy && targetRecipes.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_recipes_file_btn")
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generating ${format.ext.uppercase(Locale.US)}...")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = "Download to device", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download ${format.ext.uppercase(Locale.US)} to Downloads")
                    }
                }

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMessage = null
                            val shareResult = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = if (format == ExportFormat.PDF) {
                                        onGeneratePdf(targetRecipes)
                                    } else {
                                        onGenerateXlsx(targetRecipes)
                                    }
                                    val fileName = if (format == ExportFormat.PDF) {
                                        docHelper.suggestedPdfFileName(singleRecipe?.nameEn)
                                    } else {
                                        docHelper.suggestedXlsxFileName(singleRecipe?.nameEn)
                                    }
                                    docHelper.createShareIntent(
                                        context = context,
                                        fileName = fileName,
                                        mimeType = format.mime,
                                        bytes = bytes,
                                        subject = "ChefsMate Recipes (${targetRecipes.size})"
                                    ).getOrThrow()
                                }
                            }
                            busy = false
                            shareResult.onSuccess { chooserIntent ->
                                runCatching {
                                    context.startActivity(chooserIntent)
                                }.onFailure {
                                    val msg = "No app available to share this file"
                                    errorMessage = msg
                                    onFeedback(msg)
                                }
                            }.onFailure { err ->
                                val msg = err.localizedMessage ?: "Failed to prepare recipe export"
                                errorMessage = msg
                                onFeedback(msg)
                            }
                        }
                    },
                    enabled = !busy && targetRecipes.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("share_recipes_file_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share recipes", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share via WhatsApp / Email / Drive")
                }

                OutlinedButton(
                    onClick = {
                        val fileName = if (format == ExportFormat.PDF) {
                            docHelper.suggestedPdfFileName(singleRecipe?.nameEn)
                        } else {
                            docHelper.suggestedXlsxFileName(singleRecipe?.nameEn)
                        }
                        onSaveToCustomFolder(targetRecipes, format == ExportFormat.PDF, fileName)
                    },
                    enabled = !busy && targetRecipes.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("choose_folder_export_btn")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Choose another folder", modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Choose Another Folder...")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun fmtDraftQty(v: Double): String =
    String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
