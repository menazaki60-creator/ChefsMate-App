package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Blender
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.ParsedRecipeDraft
import com.example.data.RecipeDocumentService
import com.example.domain.MarginHealth
import com.example.domain.PlateProfitabilityReport
import com.example.domain.RecipeCostEngine
import com.example.domain.SubRecipeCostSummary
import com.example.i18n.AppStrings
import com.example.model.AppLanguage
import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
import com.example.model.RecipeLineSource
import com.example.model.RecipeType
import com.example.model.UnitType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KpiTile
import com.example.ui.components.MinimalSegmentedControl
import com.example.ui.components.formatMoney
import com.example.ui.components.formatQtyWithSmartUnit
import com.example.ui.theme.CairoArabicFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState
import com.example.viewmodel.RecipeImportUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun RecipesScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    isExpanded: Boolean,
    onSaveRecipe: (
        existingId: String?,
        nameEn: String,
        nameAr: String,
        recipeType: RecipeType,
        servings: Int,
        yieldQuantity: Double,
        yieldUnit: UnitType,
        menuPrice: Double,
        desiredProfitMarginPct: Double,
        posSku: String,
        category: String,
        lines: List<RecipeIngredientLine>
    ) -> Unit,
    onDeleteRecipe: (String) -> Unit,
    onImportRecipesUri: (Uri) -> Unit,
    onRetryRecipeImportWithAi: () -> Unit,
    onCommitImportedRecipes: (List<ParsedRecipeDraft>) -> Unit,
    onResetRecipeImport: () -> Unit,
    onImportBarrio67Book: () -> Unit,
    onGenerateRecipesPdf: (List<Recipe>) -> ByteArray,
    onGenerateRecipesXlsx: (List<Recipe>) -> ByteArray,
    onFeedbackMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docHelper = remember { RecipeDocumentService() }
    val currency = uiState.profile?.currencySymbol ?: "$"
    val lang = uiState.preferences.language
    val canEdit = uiState.activePermissions.canEditRecipes

    var activeSection by remember { mutableStateOf(RecipeType.MENU_ITEM) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("ALL") }
    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }
    var creatingType by remember { mutableStateOf<RecipeType?>(null) }
    var showImportSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var exportSingleRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportSingleRecipe = remember(exportSingleRecipeId, uiState.recipes) {
        exportSingleRecipeId?.let { id -> uiState.recipes.firstOrNull { it.id == id } }
    }

    val availableMenuCategories = remember(uiState.menuRecipes) {
        uiState.menuRecipes
            .mapNotNull { it.category?.trim()?.takeIf { c -> c.isNotEmpty() } }
            .distinct()
            .sorted()
    }

    val filteredMenuRecipes = remember(uiState.menuRecipes, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        uiState.menuRecipes.filter { recipe ->
            val catMatch = selectedCategory == "ALL" ||
                recipe.category?.trim().equals(selectedCategory, ignoreCase = true)
            val queryMatch = q.isEmpty() ||
                recipe.nameEn.lowercase().contains(q) ||
                recipe.nameAr.lowercase().contains(q) ||
                (recipe.posSku?.lowercase()?.contains(q) == true) ||
                (recipe.category?.lowercase()?.contains(q) == true) ||
                recipe.parsedLines().any { it.itemName.lowercase().contains(q) }
            catMatch && queryMatch
        }.sortedBy { it.nameEn.lowercase() }
    }

    val filteredSubRecipes = remember(uiState.subRecipes, searchQuery) {
        val q = searchQuery.trim().lowercase()
        uiState.subRecipes.filter { sub ->
            q.isEmpty() ||
                sub.nameEn.lowercase().contains(q) ||
                sub.nameAr.lowercase().contains(q) ||
                sub.parsedLines().any { it.itemName.lowercase().contains(q) }
        }.sortedBy { it.nameEn.lowercase() }
    }

    val showBarrio67Banner = uiState.canAccessBarrio67Book &&
        canEdit &&
        (uiState.menuRecipes.size < 180 || uiState.subRecipes.size < 70)

    // Hoisted at RecipesScreen level so SAF folder picker callback is never lost if dialog closes
    var pendingCustomExportRecipes by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var pendingCustomExportIsPdf by remember { mutableStateOf(true) }

    val customSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val recipesToSave = pendingCustomExportRecipes
        val isPdf = pendingCustomExportIsPdf
        if (uri != null && recipesToSave.isNotEmpty()) {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = if (isPdf) onGenerateRecipesPdf(recipesToSave) else onGenerateRecipesXlsx(recipesToSave)
                        docHelper.writeBytesToUri(context, uri, bytes).getOrThrow()
                    }
                }
                res.onSuccess {
                    val label = if (isPdf) "PDF" else "Excel (.xlsx)"
                    onFeedbackMessage("Saved ${recipesToSave.size} recipe(s) as $label")
                    showExportDialog = false
                    exportSingleRecipeId = null
                }.onFailure { err ->
                    onFeedbackMessage(err.localizedMessage ?: "Failed to save file to selected folder")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("recipes_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Minimal Editorial Header: Title + Primary Create & Document Upload/Export Bar
        item(key = "recipes_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = strings.recipesTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Dishes, sub-recipes, PDF/Excel import & live costing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { creatingType = RecipeType.MENU_ITEM },
                        enabled = canEdit,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("new_recipe_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = strings.newRecipe, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.newRecipe, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { creatingType = RecipeType.SUB_RECIPE },
                        enabled = canEdit,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("new_sub_recipe_button")
                    ) {
                        Icon(Icons.Default.Blender, contentDescription = strings.newSubRecipe, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.newSubRecipe, maxLines = 1)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (canEdit) showImportSourceDialog = true },
                        enabled = canEdit,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("upload_recipes_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = "Upload Recipes (PDF, Excel, Word, Photo)",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Upload PDF / Excel", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            if (uiState.recipes.isNotEmpty()) {
                                exportSingleRecipeId = null
                                showExportDialog = true
                            }
                        },
                        enabled = uiState.recipes.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("download_recipes_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export Recipes (PDF / Excel)",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export PDF / Excel", maxLines = 1)
                    }
                }
            }
        }

        // KPI Summary Strip
        item(key = "recipes_kpis") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KpiTile(
                    label = "Avg Plate Margin",
                    value = "${String.format(Locale.US, "%.1f", uiState.averageRecipeMarginPct)}%",
                    accent = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Default.TrendingUp,
                    sublabel = "${uiState.menuRecipes.size} selling dishes",
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    label = strings.subRecipesTab,
                    value = "${uiState.subRecipes.size}",
                    accent = MaterialTheme.colorScheme.tertiary,
                    icon = Icons.Default.Blender,
                    sublabel = "${uiState.inventory.size} raw items priced",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Barrio 67 Omega POS Bundled Recipe Book Quick-Load Card
        if (showBarrio67Banner) {
            item(key = "barrio67_book_banner") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("barrio67_book_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = "Barrio 67 Recipe Book",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(9.dp).size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Barrio 67 — Omega POS Recipe Book",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "187 Menu Dishes • 72 Sub-Recipes • 265 POS Ingredients ready to load",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(
                            onClick = onImportBarrio67Book,
                            enabled = !uiState.barrioBookImportBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("load_barrio67_book_btn")
                        ) {
                            if (uiState.barrioBookImportBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Importing Barrio 67 Recipes...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (uiState.recipes.isEmpty()) "Load Barrio 67 Recipe Book"
                                    else "Sync Missing Barrio 67 Recipes"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sleek Minimal Segmented Switcher: Menu Dishes vs Sub-Recipes
        item(key = "recipes_section_tabs") {
            MinimalSegmentedControl(
                options = listOf(
                    "${strings.menuDishesTab} (${uiState.menuRecipes.size})",
                    "${strings.subRecipesTab} (${uiState.subRecipes.size})"
                ),
                selectedIndex = if (activeSection == RecipeType.MENU_ITEM) 0 else 1,
                onSelect = { idx ->
                    activeSection = if (idx == 0) RecipeType.MENU_ITEM else RecipeType.SUB_RECIPE
                },
                testTags = listOf("tab_menu_dishes", "tab_sub_recipes")
            )
        }

        // Minimal Search Bar + Category Filter Chips (when recipes exist)
        if (uiState.recipes.isNotEmpty()) {
            item(key = "recipes_search_filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                if (activeSection == RecipeType.MENU_ITEM) "Search dishes, categories, or POS ingredients..."
                                else "Search sub-recipes or ingredients..."
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search recipes", modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recipes_search_input")
                    )

                    if (activeSection == RecipeType.MENU_ITEM && availableMenuCategories.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedCategory == "ALL",
                                onClick = { selectedCategory = "ALL" },
                                label = { Text("All (${uiState.menuRecipes.size})") }
                            )
                            availableMenuCategories.forEach { cat ->
                                val count = uiState.menuRecipes.count { it.category?.trim().equals(cat, ignoreCase = true) }
                                FilterChip(
                                    selected = selectedCategory.equals(cat, ignoreCase = true),
                                    onClick = { selectedCategory = cat },
                                    label = { Text("$cat ($count)") }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (activeSection == RecipeType.MENU_ITEM) {
            if (uiState.menuRecipes.isEmpty()) {
                item(key = "empty_recipes") {
                    EmptyStatePanel(
                        icon = Icons.Default.MenuBook,
                        title = strings.emptyRecipesTitle,
                        hint = strings.emptyRecipesHint,
                        actionLabel = strings.newRecipe,
                        onAction = { if (canEdit) creatingType = RecipeType.MENU_ITEM }
                    )
                }
            } else if (filteredMenuRecipes.isEmpty()) {
                item(key = "empty_filtered_recipes") {
                    EmptyStatePanel(
                        icon = Icons.Default.Search,
                        title = "No matching dishes",
                        hint = "Try clearing your search or switching category filter.",
                        actionLabel = "Clear Filters",
                        onAction = {
                            searchQuery = ""
                            selectedCategory = "ALL"
                        }
                    )
                }
            } else if (isExpanded) {
                val chunked = filteredMenuRecipes.chunked(2)
                items(chunked, key = { pair -> "menu_" + pair.joinToString("_") { it.id } }) { rowRecipes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowRecipes.forEach { recipe ->
                            val report = uiState.recipeReports[recipe.id]
                                ?: RecipeCostEngine.evaluateRecipe(recipe, uiState.inventory, uiState.recipes)
                            RecipeProfitCard(
                                recipe = recipe,
                                report = report,
                                currency = currency,
                                language = lang,
                                strings = strings,
                                canEdit = canEdit,
                                onEdit = { editingRecipe = recipe },
                                onExport = {
                                    exportSingleRecipeId = recipe.id
                                    showExportDialog = true
                                },
                                onDelete = { onDeleteRecipe(recipe.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowRecipes.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                items(filteredMenuRecipes, key = { "menu_${it.id}" }) { recipe ->
                    val report = uiState.recipeReports[recipe.id]
                        ?: RecipeCostEngine.evaluateRecipe(recipe, uiState.inventory, uiState.recipes)
                    RecipeProfitCard(
                        recipe = recipe,
                        report = report,
                        currency = currency,
                        language = lang,
                        strings = strings,
                        canEdit = canEdit,
                        onEdit = { editingRecipe = recipe },
                        onExport = {
                            exportSingleRecipeId = recipe.id
                            showExportDialog = true
                        },
                        onDelete = { onDeleteRecipe(recipe.id) }
                    )
                }
            }
        } else {
            // Production Sub-Recipes (Sauces / Stocks / Prep — Not Selling Items)
            if (uiState.subRecipes.isEmpty()) {
                item(key = "empty_sub_recipes") {
                    EmptyStatePanel(
                        icon = Icons.Default.Blender,
                        title = "No production sub-recipes yet",
                        hint = "Create sauces, stocks, or marinades (e.g. Garlic Sauce 1000 mL) to use as ingredients inside menu dishes. They are prep batches, not selling items.",
                        actionLabel = strings.newSubRecipe,
                        onAction = { if (canEdit) creatingType = RecipeType.SUB_RECIPE }
                    )
                }
            } else if (filteredSubRecipes.isEmpty()) {
                item(key = "empty_filtered_sub_recipes") {
                    EmptyStatePanel(
                        icon = Icons.Default.Search,
                        title = "No matching sub-recipes",
                        hint = "Try clearing your search query.",
                        actionLabel = "Clear Search",
                        onAction = { searchQuery = "" }
                    )
                }
            } else if (isExpanded) {
                val chunked = filteredSubRecipes.chunked(2)
                items(chunked, key = { pair -> "sub_" + pair.joinToString("_") { it.id } }) { rowSubs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowSubs.forEach { sub ->
                            val summary = uiState.subRecipeReports[sub.id]
                                ?: RecipeCostEngine.evaluateSubRecipe(sub, uiState.inventory, uiState.recipes)
                            SubRecipeProductionCard(
                                subRecipe = sub,
                                summary = summary,
                                currency = currency,
                                language = lang,
                                strings = strings,
                                canEdit = canEdit,
                                onEdit = { editingRecipe = sub },
                                onExport = {
                                    exportSingleRecipeId = sub.id
                                    showExportDialog = true
                                },
                                onDelete = { onDeleteRecipe(sub.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowSubs.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                items(filteredSubRecipes, key = { "sub_${it.id}" }) { sub ->
                    val summary = uiState.subRecipeReports[sub.id]
                        ?: RecipeCostEngine.evaluateSubRecipe(sub, uiState.inventory, uiState.recipes)
                    SubRecipeProductionCard(
                        subRecipe = sub,
                        summary = summary,
                        currency = currency,
                        language = lang,
                        strings = strings,
                        canEdit = canEdit,
                        onEdit = { editingRecipe = sub },
                        onExport = {
                            exportSingleRecipeId = sub.id
                            showExportDialog = true
                        },
                        onDelete = { onDeleteRecipe(sub.id) }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    creatingType?.let { initialType ->
        RecipeBuilderDialog(
            existingRecipe = null,
            initialType = initialType,
            inventory = uiState.inventory,
            allRecipes = uiState.recipes,
            currency = currency,
            defaultTargetFoodCostPct = uiState.profile?.targetFoodCostPct ?: 30.0,
            strings = strings,
            onDismiss = { creatingType = null },
            onSave = { nameEn, nameAr, rType, servings, yieldQty, yieldUnit, price, margin, sku, cat, lines ->
                onSaveRecipe(null, nameEn, nameAr, rType, servings, yieldQty, yieldUnit, price, margin, sku, cat, lines)
                activeSection = rType
                creatingType = null
            }
        )
    }

    editingRecipe?.let { target ->
        RecipeBuilderDialog(
            existingRecipe = target,
            initialType = target.recipeTypeEnum,
            inventory = uiState.inventory,
            allRecipes = uiState.recipes,
            currency = currency,
            defaultTargetFoodCostPct = target.targetFoodCostPct,
            strings = strings,
            onDismiss = { editingRecipe = null },
            onSave = { nameEn, nameAr, rType, servings, yieldQty, yieldUnit, price, margin, sku, cat, lines ->
                onSaveRecipe(target.id, nameEn, nameAr, rType, servings, yieldQty, yieldUnit, price, margin, sku, cat, lines)
                activeSection = rType
                editingRecipe = null
            }
        )
    }

    if (showImportSourceDialog) {
        ImportRecipeSourceDialog(
            onFilePicked = onImportRecipesUri,
            showBarrio67Option = uiState.canAccessBarrio67Book,
            onLoadBarrio67Book = onImportBarrio67Book,
            barrioBookImportBusy = uiState.barrioBookImportBusy,
            onFeedback = onFeedbackMessage,
            onDismiss = { showImportSourceDialog = false }
        )
    }

    if (uiState.recipeImportState !is RecipeImportUiState.Idle) {
        ImportRecipesPreviewDialog(
            state = uiState.recipeImportState,
            onRetryWithCloudAi = onRetryRecipeImportWithAi,
            onConfirmImport = onCommitImportedRecipes,
            onDismiss = onResetRecipeImport
        )
    }

    if (showExportDialog) {
        ExportRecipesDialog(
            singleRecipe = exportSingleRecipe,
            allRecipes = uiState.recipes,
            menuRecipes = uiState.menuRecipes,
            subRecipes = uiState.subRecipes,
            onGeneratePdf = onGenerateRecipesPdf,
            onGenerateXlsx = onGenerateRecipesXlsx,
            onSaveToCustomFolder = { recipesToSave, isPdf, suggestedName ->
                pendingCustomExportRecipes = recipesToSave
                pendingCustomExportIsPdf = isPdf
                runCatching {
                    customSaveLauncher.launch(suggestedName)
                }.onFailure { err ->
                    onFeedbackMessage(err.localizedMessage ?: "Could not open folder picker")
                }
            },
            onFeedback = onFeedbackMessage,
            onDismiss = {
                showExportDialog = false
                exportSingleRecipeId = null
            }
        )
    }
}

@Composable
private fun SubRecipeProductionCard(
    subRecipe: Recipe,
    summary: SubRecipeCostSummary,
    currency: String,
    language: AppLanguage,
    strings: AppStrings,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sub_recipe_card_${subRecipe.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (language == AppLanguage.AR && subRecipe.nameAr.isNotBlank()) subRecipe.nameAr else subRecipe.nameEn,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = if (language == AppLanguage.AR) CairoArabicFamily else MaterialTheme.typography.titleMedium.fontFamily
                        ),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Used in ${summary.usedInDishCount} dish(es)  •  Yields ${formatQty(summary.yieldQuantity)} ${summary.yieldUnit.symbol}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = strings.productionNotForSale,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Available Production Stock on hand banner
            val isProdEmpty = subRecipe.productionStock <= 0.0001
            val isProdLow = subRecipe.lowProductionStock
            val prodBannerBg = when {
                isProdEmpty || isProdLow -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            }
            val prodBannerFg = when {
                isProdEmpty || isProdLow -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            val batchesReady = if (summary.yieldQuantity > 0.0001) {
                subRecipe.productionStock / summary.yieldQuantity
            } else 0.0
            Surface(
                color = prodBannerBg,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${strings.availableQty} ${strings.productionStockTab}",
                        style = MaterialTheme.typography.labelMedium,
                        color = prodBannerFg
                    )
                    Text(
                        text = "${formatQtyWithSmartUnit(subRecipe.productionStock, summary.yieldUnit)} (${String.format(Locale.US, "%.1f", batchesReady)}x)",
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                        fontWeight = FontWeight.Bold,
                        color = prodBannerFg
                    )
                }
            }

            // 3-Metric Production Batch Strip (No Selling Price / No Margin)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RecipeMetric(
                    label = strings.batchYield,
                    value = "${formatQty(summary.yieldQuantity)} ${summary.yieldUnit.symbol}",
                    color = MaterialTheme.colorScheme.onSurface
                )
                RecipeMetric(
                    label = strings.batchCost,
                    value = formatMoney(summary.totalWasteAdjustedBatchCost, currency),
                    color = MaterialTheme.colorScheme.primary
                )
                RecipeMetric(
                    label = if (summary.costPer100SmallUnit != null && summary.smallUnitLabel != null) {
                        "Cost / ${summary.smallUnitLabel}"
                    } else {
                        "Cost / ${summary.yieldUnit.symbol}"
                    },
                    value = if (summary.costPer100SmallUnit != null) {
                        formatMoney(summary.costPer100SmallUnit, currency)
                    } else {
                        formatMoney(summary.costPerYieldUnit, currency)
                    },
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Ingredient breakdown
            if (summary.lineBreakdowns.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    summary.lineBreakdowns.forEach { bd ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (bd.isSubRecipe) {
                                    SubRecipePill()
                                }
                                Text(
                                    text = "${bd.line.itemName} (${formatQty(bd.line.quantity)} ${bd.line.unit})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${formatMoney(bd.wasteAdjustedLineCost, currency)} (${String.format(Locale.US, "%.0f", bd.shareOfPlatePercent)}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onExport,
                    modifier = Modifier.testTag("export_sub_${subRecipe.id}")
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download PDF or Excel", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PDF / Excel")
                }
                if (canEdit) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit sub-recipe", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit Batch")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete sub-recipe",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubRecipePill() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = "PREP",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun RecipeProfitCard(
    recipe: Recipe,
    report: PlateProfitabilityReport,
    currency: String,
    language: AppLanguage,
    strings: AppStrings,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val healthColor = when (report.marginHealth) {
        MarginHealth.HEALTHY -> MaterialTheme.colorScheme.secondary
        MarginHealth.TIGHT -> MaterialTheme.colorScheme.tertiary
        MarginHealth.UNDERPRICED -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("recipe_card_${recipe.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (language == AppLanguage.AR && recipe.nameAr.isNotBlank()) recipe.nameAr else recipe.nameEn,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = if (language == AppLanguage.AR) CairoArabicFamily else MaterialTheme.typography.titleMedium.fontFamily
                        ),
                        fontWeight = FontWeight.Bold
                    )
                    val meta = buildString {
                        if (!recipe.category.isNullOrBlank()) {
                            append(recipe.category)
                            append("  •  ")
                        }
                        append("${recipe.servings} serving(s)")
                        if (!recipe.posSku.isNullOrBlank() && !recipe.posSku.equals(recipe.nameEn, ignoreCase = true)) {
                            append("  •  SKU: ${recipe.posSku}")
                        }
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = healthColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, healthColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "${String.format(Locale.US, "%.1f", report.actualProfitMarginPercent)}% Margin",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = healthColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 4-Metric Cost & Pricing Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RecipeMetric(
                    label = strings.costPerPlate,
                    value = formatMoney(report.wasteAdjustedCostPerPlate, currency),
                    color = MaterialTheme.colorScheme.onSurface
                )
                RecipeMetric(
                    label = strings.minSellingPrice,
                    value = formatMoney(report.minimumSellingPrice, currency),
                    color = MaterialTheme.colorScheme.primary
                )
                RecipeMetric(
                    label = "Menu Price",
                    value = formatMoney(report.currentMenuPrice, currency),
                    color = healthColor
                )
                RecipeMetric(
                    label = strings.profitPerPlate,
                    value = formatMoney(report.actualProfitPerPlate, currency),
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (report.wasteImpactPerPlate > 0.01) {
                Text(
                    text = "Includes +${formatMoney(report.wasteImpactPerPlate, currency)}/plate waste adjustment (Raw: ${formatMoney(report.rawCostPerPlate, currency)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Ingredient & Sub-Recipe cost breakdown
            if (report.lineBreakdowns.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    report.lineBreakdowns.forEach { bd ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (bd.isSubRecipe) {
                                    SubRecipePill()
                                }
                                Text(
                                    text = "${bd.line.itemName} (${formatQty(bd.line.quantity)} ${bd.line.unit})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${formatMoney(bd.wasteAdjustedLineCost, currency)} (${String.format(Locale.US, "%.0f", bd.shareOfPlatePercent)}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onExport,
                    modifier = Modifier.testTag("export_recipe_${recipe.id}")
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download PDF or Excel", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PDF / Excel")
                }
                if (canEdit) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit recipe", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit & Price")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete recipe",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeMetric(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun RecipeBuilderDialog(
    existingRecipe: Recipe?,
    initialType: RecipeType,
    inventory: List<InventoryItem>,
    allRecipes: List<Recipe>,
    currency: String,
    defaultTargetFoodCostPct: Double,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (
        nameEn: String,
        nameAr: String,
        recipeType: RecipeType,
        servings: Int,
        yieldQuantity: Double,
        yieldUnit: UnitType,
        menuPrice: Double,
        desiredProfitMarginPct: Double,
        posSku: String,
        category: String,
        lines: List<RecipeIngredientLine>
    ) -> Unit
) {
    var recipeType by remember { mutableStateOf(existingRecipe?.recipeTypeEnum ?: initialType) }
    var nameEn by remember { mutableStateOf(existingRecipe?.nameEn ?: "") }
    var nameAr by remember { mutableStateOf(existingRecipe?.nameAr ?: "") }
    var servingsText by remember { mutableStateOf((existingRecipe?.servings ?: 1).toString()) }
    var yieldQtyText by remember {
        mutableStateOf(
            existingRecipe?.yieldQuantity?.takeIf { it > 0 }?.let { formatQty(it) } ?: "1000"
        )
    }
    var yieldUnit by remember {
        mutableStateOf(
            existingRecipe?.yieldUnitEnum ?: UnitType.ML
        )
    }
    var menuPriceText by remember {
        mutableStateOf(
            existingRecipe?.menuPrice?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        )
    }
    var desiredMarginPct by remember {
        mutableDoubleStateOf((100.0 - defaultTargetFoodCostPct).coerceIn(30.0, 88.0))
    }
    var posSku by remember { mutableStateOf(existingRecipe?.posSku ?: "") }

    val lines = remember {
        mutableStateListOf<RecipeIngredientLine>().apply {
            existingRecipe?.parsedLines()?.let { addAll(it) }
        }
    }

    // Source selector when adding lines: Raw Inventory vs Production Sub-Recipes
    var lineSourceTab by remember { mutableStateOf(RecipeLineSource.INVENTORY) }
    val selectableSubRecipes = remember(allRecipes, existingRecipe?.id) {
        allRecipes.filter { candidate ->
            candidate.recipeTypeEnum == RecipeType.SUB_RECIPE &&
                !RecipeCostEngine.wouldCreateCycle(existingRecipe?.id, candidate.id, allRecipes)
        }
    }

    var selectedInventoryItem by remember { mutableStateOf(inventory.firstOrNull()) }
    var selectedSubRecipe by remember { mutableStateOf(selectableSubRecipes.firstOrNull()) }
    var lineQtyText by remember { mutableStateOf("100") }
    var lineUnit by remember { mutableStateOf(UnitType.G) }

    val liveReport = RecipeCostEngine.calculate(
        lines = lines,
        servings = servingsText.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        menuPrice = menuPriceText.toDoubleOrNull() ?: 0.0,
        desiredProfitMarginPercent = desiredMarginPct,
        inventory = inventory,
        allRecipes = allRecipes,
        currentRecipeId = existingRecipe?.id
    )

    val liveSubSummary = RecipeCostEngine.evaluateSubRecipe(
        lines = lines,
        yieldQuantity = yieldQtyText.toDoubleOrNull()?.coerceAtLeast(0.01) ?: 1.0,
        yieldUnit = yieldUnit,
        inventory = inventory,
        allRecipes = allRecipes,
        currentSubRecipeId = existingRecipe?.id
    )

    val isSubRecipe = recipeType == RecipeType.SUB_RECIPE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    existingRecipe != null -> "Edit ${existingRecipe.nameEn}"
                    isSubRecipe -> strings.newSubRecipe
                    else -> strings.newRecipe
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Recipe Type Switcher: Menu Dish vs Production Sub-Recipe
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !isSubRecipe,
                        onClick = { recipeType = RecipeType.MENU_ITEM },
                        label = { Text("Menu Dish") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("type_menu_dish_chip")
                    )
                    FilterChip(
                        selected = isSubRecipe,
                        onClick = { recipeType = RecipeType.SUB_RECIPE },
                        label = { Text("Sub-Recipe (Prep)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("type_sub_recipe_chip")
                    )
                }

                if (isSubRecipe) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Production recipe (sauces, stocks, marinades). Not sold directly — add it as an ingredient inside menu dishes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text(if (isSubRecipe) "Sub-Recipe Name (e.g. Garlic Sauce)" else "Dish Name (English)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recipe_name_input")
                )
                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it },
                    label = { Text(if (isSubRecipe) "اسم الصوص / التحضير (العربية، اختياري)" else "Dish Name (العربية, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isSubRecipe) {
                    // Batch Yield & Unit (Sub-Recipe)
                    OutlinedTextField(
                        value = yieldQtyText,
                        onValueChange = { yieldQtyText = it },
                        label = { Text("${strings.batchYield} (e.g. 1000 mL or 2 kg)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_recipe_yield_input")
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UnitType.entries.forEach { u ->
                            FilterChip(
                                selected = yieldUnit == u,
                                onClick = { yieldUnit = u },
                                label = { Text(u.symbol) }
                            )
                        }
                    }
                } else {
                    // Servings & POS SKU (Selling Menu Dish)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = servingsText,
                            onValueChange = { servingsText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Servings") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = posSku,
                            onValueChange = { posSku = it },
                            label = { Text("POS SKU (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Add Ingredients (Raw Inventory OR Production Sub-Recipes)
                Text(
                    text = "Add Ingredients & Sauces",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = lineSourceTab == RecipeLineSource.INVENTORY,
                        onClick = { lineSourceTab = RecipeLineSource.INVENTORY },
                        label = { Text("Raw Stock (${inventory.size})") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("picker_raw_stock_chip")
                    )
                    FilterChip(
                        selected = lineSourceTab == RecipeLineSource.SUB_RECIPE,
                        onClick = {
                            lineSourceTab = RecipeLineSource.SUB_RECIPE
                            if (selectedSubRecipe == null) {
                                selectedSubRecipe = selectableSubRecipes.firstOrNull()
                            }
                            selectedSubRecipe?.let { sub ->
                                lineUnit = when (sub.yieldUnitEnum) {
                                    UnitType.L -> UnitType.ML
                                    UnitType.KG -> UnitType.G
                                    else -> sub.yieldUnitEnum
                                }
                            }
                        },
                        label = { Text("Sub-Recipes (${selectableSubRecipes.size})") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("picker_sub_recipes_chip")
                    )
                }

                if (lineSourceTab == RecipeLineSource.INVENTORY) {
                    if (inventory.isEmpty()) {
                        Text(
                            text = "Add ingredients in the Stock tab first (or tap Basic Ingredients) to pull live pricing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            inventory.forEach { item ->
                                FilterChip(
                                    selected = selectedInventoryItem?.id == item.id,
                                    onClick = {
                                        selectedInventoryItem = item
                                        lineUnit = when (item.unitEnum) {
                                            UnitType.KG -> UnitType.G
                                            UnitType.L -> UnitType.ML
                                            else -> item.unitEnum
                                        }
                                    },
                                    label = {
                                        Text("${item.nameEn} (${formatMoney(item.effectiveNetUnitCost, currency)}/${item.unit})")
                                    }
                                )
                            }
                        }
                    }
                } else {
                    if (selectableSubRecipes.isEmpty()) {
                        Text(
                            text = "No production sub-recipes yet. Create a sauce or prep batch first, then add it to any dish.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectableSubRecipes.forEach { sub ->
                                val subSummary = RecipeCostEngine.evaluateSubRecipe(sub, inventory, allRecipes)
                                val rateLabel = RecipeCostEngine.formatSubRecipeRateLabel(subSummary, currency)
                                FilterChip(
                                    selected = selectedSubRecipe?.id == sub.id,
                                    onClick = {
                                        selectedSubRecipe = sub
                                        lineUnit = when (sub.yieldUnitEnum) {
                                            UnitType.L -> UnitType.ML
                                            UnitType.KG -> UnitType.G
                                            else -> sub.yieldUnitEnum
                                        }
                                    },
                                    label = { Text("${sub.nameEn} ($rateLabel)") }
                                )
                            }
                        }
                    }
                }

                val canAddLine = (lineSourceTab == RecipeLineSource.INVENTORY && selectedInventoryItem != null) ||
                    (lineSourceTab == RecipeLineSource.SUB_RECIPE && selectedSubRecipe != null)

                if (canAddLine) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = lineQtyText,
                            onValueChange = { lineQtyText = it },
                            label = { Text("Qty") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                val q = lineQtyText.toDoubleOrNull() ?: return@FilledTonalButton
                                if (q <= 0.0) return@FilledTonalButton
                                if (lineSourceTab == RecipeLineSource.INVENTORY) {
                                    val inv = selectedInventoryItem ?: return@FilledTonalButton
                                    lines.add(
                                        RecipeIngredientLine(
                                            itemId = inv.id,
                                            itemName = inv.nameEn,
                                            quantity = q,
                                            unit = lineUnit.symbol,
                                            sourceType = RecipeLineSource.INVENTORY.id
                                        )
                                    )
                                } else {
                                    val sub = selectedSubRecipe ?: return@FilledTonalButton
                                    lines.add(
                                        RecipeIngredientLine(
                                            itemId = sub.id,
                                            itemName = sub.nameEn,
                                            quantity = q,
                                            unit = lineUnit.symbol,
                                            sourceType = RecipeLineSource.SUB_RECIPE.id
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.testTag("add_recipe_ingredient_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add ingredient", modifier = Modifier.size(16.dp))
                            Text("Add")
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UnitType.entries.forEach { u ->
                            FilterChip(
                                selected = lineUnit == u,
                                onClick = { lineUnit = u },
                                label = { Text(u.symbol) }
                            )
                        }
                    }
                }

                // Current Ingredient Lines
                lines.forEachIndexed { idx, line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (line.sourceEnum == RecipeLineSource.SUB_RECIPE) {
                                SubRecipePill()
                            }
                            Text(
                                text = "${line.itemName} • ${formatQty(line.quantity)} ${line.unit}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(
                            onClick = { lines.removeAt(idx) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.RemoveCircleOutline,
                                contentDescription = "Remove line",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (isSubRecipe) {
                    // Live Production Batch Cost Summary (NO Menu Price / NO Margin)
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(strings.batchCost, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = formatMoney(liveSubSummary.totalWasteAdjustedBatchCost, currency),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Cost / ${liveSubSummary.yieldUnit.symbol}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = formatMoney(liveSubSummary.costPerYieldUnit, currency),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            if (liveSubSummary.costPer100SmallUnit != null && liveSubSummary.smallUnitLabel != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Cost / ${liveSubSummary.smallUnitLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatMoney(liveSubSummary.costPer100SmallUnit, currency),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Desired Profit Margin Slider -> Auto Minimum Selling Price
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = strings.desiredProfitMargin,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "${desiredMarginPct.toInt()}% (Food Cost ${(100 - desiredMarginPct).toInt()}%)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = desiredMarginPct.toFloat(),
                            onValueChange = { desiredMarginPct = it.toDouble() },
                            valueRange = 40f..88f,
                            modifier = Modifier.testTag("desired_margin_slider")
                        )
                    }

                    // Live Calculated Profitability Summary Card
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(strings.costPerPlate, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = formatMoney(liveReport.wasteAdjustedCostPerPlate, currency),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = strings.minSellingPrice,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = formatMoney(liveReport.minimumSellingPrice, currency),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(
                                onClick = {
                                    menuPriceText = String.format(Locale.US, "%.2f", liveReport.minimumSellingPrice)
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(strings.applySuggestedPrice)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = menuPriceText,
                        onValueChange = { menuPriceText = it },
                        label = { Text("Menu Selling Price ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recipe_menu_price_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeYield = yieldQtyText.toDoubleOrNull()?.coerceAtLeast(0.01) ?: 1.0
                    val price = if (isSubRecipe) 0.0 else (menuPriceText.toDoubleOrNull() ?: liveReport.minimumSellingPrice)
                    onSave(
                        nameEn,
                        nameAr,
                        recipeType,
                        if (isSubRecipe) 1 else (servingsText.toIntOrNull()?.coerceAtLeast(1) ?: 1),
                        if (isSubRecipe) safeYield else 1.0,
                        if (isSubRecipe) yieldUnit else UnitType.G,
                        price,
                        desiredMarginPct,
                        if (isSubRecipe) "" else posSku,
                        if (isSubRecipe) "Prep" else "Main",
                        lines.toList()
                    )
                },
                enabled = nameEn.isNotBlank() && (!isSubRecipe || (yieldQtyText.toDoubleOrNull() ?: 0.0) > 0.0),
                modifier = Modifier.testTag("save_recipe_button")
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

private fun formatQty(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
