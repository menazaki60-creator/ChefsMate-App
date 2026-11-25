package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.MarginHealth
import com.example.domain.PlateProfitabilityReport
import com.example.domain.RecipeCostEngine
import com.example.i18n.AppStrings
import com.example.model.AppLanguage
import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
import com.example.model.UnitType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KpiTile
import com.example.ui.components.formatMoney
import com.example.ui.theme.CairoArabicFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState
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
        servings: Int,
        menuPrice: Double,
        desiredProfitMarginPct: Double,
        posSku: String,
        category: String,
        lines: List<RecipeIngredientLine>
    ) -> Unit,
    onDeleteRecipe: (String) -> Unit
) {
    val currency = uiState.profile?.currencySymbol ?: "$"
    val lang = uiState.preferences.language
    val canEdit = uiState.activePermissions.canEditRecipes

    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("recipes_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Summary KPIs
        item(key = "recipes_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.recipesTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Live waste-adjusted plate cost & minimum selling price calculator",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showCreateDialog = true },
                    enabled = canEdit,
                    modifier = Modifier.testTag("new_recipe_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = strings.newRecipe, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.newRecipe)
                }
            }
        }

        item(key = "recipes_kpis") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiTile(
                    label = "Avg Plate Margin",
                    value = "${String.format(Locale.US, "%.1f", uiState.averageRecipeMarginPct)}%",
                    accent = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Default.TrendingUp,
                    sublabel = "${uiState.recipes.size} recipes",
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    label = "Inventory Priced",
                    value = "${uiState.inventory.size}",
                    accent = MaterialTheme.colorScheme.primary,
                    icon = Icons.Default.Calculate,
                    sublabel = "Live net unit cost",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (uiState.recipes.isEmpty()) {
            item(key = "empty_recipes") {
                EmptyStatePanel(
                    icon = Icons.Default.MenuBook,
                    title = strings.emptyRecipesTitle,
                    hint = strings.emptyRecipesHint,
                    actionLabel = strings.newRecipe,
                    onAction = { if (canEdit) showCreateDialog = true }
                )
            }
        } else if (isExpanded) {
            val chunked = uiState.recipes.chunked(2)
            items(chunked, key = { pair -> pair.joinToString("_") { it.id } }) { rowRecipes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowRecipes.forEach { recipe ->
                        val report = uiState.recipeReports[recipe.id]
                            ?: RecipeCostEngine.evaluateRecipe(recipe, uiState.inventory)
                        RecipeProfitCard(
                            recipe = recipe,
                            report = report,
                            currency = currency,
                            language = lang,
                            strings = strings,
                            canEdit = canEdit,
                            onEdit = { editingRecipe = recipe },
                            onDelete = { onDeleteRecipe(recipe.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowRecipes.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else {
            items(uiState.recipes, key = { it.id }) { recipe ->
                val report = uiState.recipeReports[recipe.id]
                    ?: RecipeCostEngine.evaluateRecipe(recipe, uiState.inventory)
                RecipeProfitCard(
                    recipe = recipe,
                    report = report,
                    currency = currency,
                    language = lang,
                    strings = strings,
                    canEdit = canEdit,
                    onEdit = { editingRecipe = recipe },
                    onDelete = { onDeleteRecipe(recipe.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    if (showCreateDialog) {
        RecipeBuilderDialog(
            existingRecipe = null,
            inventory = uiState.inventory,
            currency = currency,
            defaultTargetFoodCostPct = uiState.profile?.targetFoodCostPct ?: 30.0,
            strings = strings,
            onDismiss = { showCreateDialog = false },
            onSave = { nameEn, nameAr, servings, price, margin, sku, cat, lines ->
                onSaveRecipe(null, nameEn, nameAr, servings, price, margin, sku, cat, lines)
                showCreateDialog = false
            }
        )
    }

    editingRecipe?.let { target ->
        RecipeBuilderDialog(
            existingRecipe = target,
            inventory = uiState.inventory,
            currency = currency,
            defaultTargetFoodCostPct = target.targetFoodCostPct,
            strings = strings,
            onDismiss = { editingRecipe = null },
            onSave = { nameEn, nameAr, servings, price, margin, sku, cat, lines ->
                onSaveRecipe(target.id, nameEn, nameAr, servings, price, margin, sku, cat, lines)
                editingRecipe = null
            }
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
                        append("${recipe.servings} serving(s)")
                        if (!recipe.posSku.isNullOrBlank()) append("  •  SKU: ${recipe.posSku}")
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

            // Ingredient cost breakdown bars
            if (report.lineBreakdowns.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    report.lineBreakdowns.forEach { bd ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${bd.line.itemName} (${bd.line.quantity} ${bd.line.unit})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${formatMoney(bd.wasteAdjustedLineCost, currency)} (${String.format(Locale.US, "%.0f", bd.shareOfPlatePercent)}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (canEdit) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
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
    inventory: List<InventoryItem>,
    currency: String,
    defaultTargetFoodCostPct: Double,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (
        nameEn: String,
        nameAr: String,
        servings: Int,
        menuPrice: Double,
        desiredProfitMarginPct: Double,
        posSku: String,
        category: String,
        lines: List<RecipeIngredientLine>
    ) -> Unit
) {
    var nameEn by remember { mutableStateOf(existingRecipe?.nameEn ?: "") }
    var nameAr by remember { mutableStateOf(existingRecipe?.nameAr ?: "") }
    var servingsText by remember { mutableStateOf((existingRecipe?.servings ?: 1).toString()) }
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

    var selectedInventoryItem by remember { mutableStateOf(inventory.firstOrNull()) }
    var lineQtyText by remember { mutableStateOf("200") }
    var lineUnit by remember { mutableStateOf(UnitType.G) }

    val liveReport = RecipeCostEngine.calculate(
        lines = lines,
        servings = servingsText.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        menuPrice = menuPriceText.toDoubleOrNull() ?: 0.0,
        desiredProfitMarginPercent = desiredMarginPct,
        inventory = inventory
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingRecipe == null) strings.newRecipe else "Edit ${existingRecipe.nameEn}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text("Dish Name (English)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recipe_name_input")
                )
                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it },
                    label = { Text("Dish Name (العربية, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Add Ingredient from Live Inventory
                Text(
                    text = "Add Ingredients from Live Inventory",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (inventory.isEmpty()) {
                    Text(
                        text = "Add ingredients in the Ledger tab first to pull live pricing.",
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
                                    lineUnit = if (item.unitEnum == UnitType.KG) UnitType.G else item.unitEnum
                                },
                                label = {
                                    Text("${item.nameEn} (${formatMoney(item.effectiveNetUnitCost, currency)}/${item.unit})")
                                }
                            )
                        }
                    }

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
                                val inv = selectedInventoryItem ?: return@FilledTonalButton
                                val q = lineQtyText.toDoubleOrNull() ?: return@FilledTonalButton
                                if (q > 0.0) {
                                    lines.add(
                                        RecipeIngredientLine(
                                            itemId = inv.id,
                                            itemName = inv.nameEn,
                                            quantity = q,
                                            unit = lineUnit.symbol
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
                        Text(
                            text = "${line.itemName} • ${line.quantity} ${line.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val price = menuPriceText.toDoubleOrNull() ?: liveReport.minimumSellingPrice
                    onSave(
                        nameEn,
                        nameAr,
                        servingsText.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        price,
                        desiredMarginPct,
                        posSku,
                        "Main",
                        lines.toList()
                    )
                },
                enabled = nameEn.isNotBlank(),
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
