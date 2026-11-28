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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Blender
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.BasicIngredientSpec
import com.example.data.BilingualIngredientMatcher
import com.example.domain.RecipeCostEngine
import com.example.domain.SubRecipeCostSummary
import com.example.i18n.AppStrings
import com.example.model.AppLanguage
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.StockAdjustMode
import com.example.model.UnitType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KpiTile
import com.example.ui.components.MinimalSegmentedControl
import com.example.ui.components.UrgentAlertBanner
import com.example.ui.components.formatMoney
import com.example.ui.components.formatQtyWithSmartUnit
import com.example.ui.theme.CairoArabicFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState
import java.util.Locale

private enum class StockViewSection {
    RAW_STOCK,
    PRODUCTION
}

@Composable
fun StockScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    isExpanded: Boolean,
    onAddOrMergeItem: (
        name: String,
        arabicName: String,
        category: IngredientCategory,
        quantity: Double,
        unit: UnitType,
        totalCost: Double,
        wasteQty: Double,
        wasteUnit: UnitType,
        minStock: Double,
        supplier: String,
        allowAutoCorrectTypos: Boolean
    ) -> Unit,
    onAddBasicIngredients: (List<BasicIngredientSpec>) -> Unit,
    onAdjustRawStock: (
        item: InventoryItem,
        mode: StockAdjustMode,
        amount: Double,
        unit: UnitType,
        extraCost: Double,
        newMinStock: Double?,
        reason: String
    ) -> Unit,
    onLogWaste: (InventoryItem, Double, UnitType, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onProduceSubRecipe: (
        subRecipe: Recipe,
        producedQtyInYieldUnit: Double,
        deductRawIngredients: Boolean
    ) -> Unit,
    onAdjustProductionStock: (
        subRecipe: Recipe,
        mode: StockAdjustMode,
        amount: Double,
        unit: UnitType,
        newMinProductionStock: Double?
    ) -> Unit,
    onNavigateToRecipes: () -> Unit,
    onNavigateToAlertsTarget: () -> Unit
) {
    val currency = uiState.profile?.currencySymbol ?: "$"
    val lang = uiState.preferences.language

    var activeSection by rememberSaveable { mutableStateOf(StockViewSection.RAW_STOCK) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<IngredientCategory?>(null) }
    var lowStockOnly by remember { mutableStateOf(false) }
    var outOfStockOnly by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showBasicCatalog by remember { mutableStateOf(false) }
    var prefillBasicSpec by remember { mutableStateOf<BasicIngredientSpec?>(null) }
    var wasteTargetItem by remember { mutableStateOf<InventoryItem?>(null) }
    var adjustRawTarget by remember { mutableStateOf<InventoryItem?>(null) }
    var produceTargetSub by remember { mutableStateOf<Recipe?>(null) }
    var adjustProdTarget by remember { mutableStateOf<Recipe?>(null) }

    val rawInStockCount = remember(uiState.inventory) {
        uiState.inventory.count { it.currentStock > 0.0001 }
    }
    val rawLowOrOutCount = remember(uiState.inventory) {
        uiState.inventory.count { it.currentStock <= 0.0001 || it.lowStock }
    }
    val prodReadyCount = remember(uiState.subRecipes) {
        uiState.subRecipes.count { it.productionStock > 0.0001 }
    }
    val prodTotalValue = remember(uiState.subRecipes, uiState.subRecipeReports) {
        uiState.subRecipes.sumOf { sub ->
            val costPerUnit = uiState.subRecipeReports[sub.id]?.costPerYieldUnit ?: 0.0
            sub.productionStock * costPerUnit
        }
    }

    val filteredRawItems = remember(
        uiState.inventory,
        searchQuery,
        selectedCategory,
        lowStockOnly,
        outOfStockOnly
    ) {
        uiState.inventory.filter { item ->
            val matchesCategory = selectedCategory == null || item.categoryEnum == selectedCategory
            val isOut = item.currentStock <= 0.0001
            val matchesLow = !lowStockOnly || item.lowStock
            val matchesOut = !outOfStockOnly || isOut
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isEmpty() ||
                item.nameEn.lowercase().contains(q) ||
                item.nameAr.lowercase().contains(q) ||
                item.aliases.any { it.lowercase().contains(q) }
            matchesCategory && matchesLow && matchesOut && matchesSearch
        }
    }

    val filteredProductionItems = remember(uiState.subRecipes, searchQuery, lowStockOnly, outOfStockOnly) {
        uiState.subRecipes.filter { sub ->
            val isOut = sub.productionStock <= 0.0001
            val matchesLow = !lowStockOnly || sub.lowProductionStock
            val matchesOut = !outOfStockOnly || isOut
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isEmpty() ||
                sub.nameEn.lowercase().contains(q) ||
                sub.nameAr.lowercase().contains(q)
            matchesLow && matchesOut && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stock_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Urgent real-time alert banner
        if (uiState.urgentAlerts.isNotEmpty()) {
            item(key = "alerts_banner") {
                UrgentAlertBanner(
                    alerts = uiState.urgentAlerts,
                    onAlertClick = onNavigateToAlertsTarget
                )
            }
        }

        // Sleek Minimal Segmented Switcher: Raw Stock vs Production Stock
        item(key = "stock_section_switcher") {
            MinimalSegmentedControl(
                options = listOf(
                    "${strings.rawStockTab} (${uiState.inventory.size})",
                    "${strings.productionStockTab} (${uiState.subRecipes.size})"
                ),
                selectedIndex = if (activeSection == StockViewSection.RAW_STOCK) 0 else 1,
                onSelect = { idx ->
                    activeSection = if (idx == 0) StockViewSection.RAW_STOCK else StockViewSection.PRODUCTION
                },
                testTags = listOf("tab_raw_stock", "tab_production_stock")
            )
        }

        // Hero KPI Strip (adapts to Raw Stock vs Production Stock)
        item(key = "kpi_strip") {
            if (activeSection == StockViewSection.RAW_STOCK) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiTile(
                        label = strings.availableQty,
                        value = "$rawInStockCount / ${uiState.inventory.size}",
                        accent = MaterialTheme.colorScheme.secondary,
                        icon = Icons.Default.Inventory2,
                        sublabel = if (rawLowOrOutCount > 0) "$rawLowOrOutCount low / out" else "All stocked",
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        label = "Stock Value",
                        value = formatMoney(uiState.totalInventoryValue, currency),
                        accent = MaterialTheme.colorScheme.primary,
                        icon = Icons.Default.TrendingDown,
                        sublabel = "${strings.waste}: ${formatMoney(uiState.totalWasteCost, currency)}",
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiTile(
                        label = "Prep Ready",
                        value = "$prodReadyCount / ${uiState.subRecipes.size}",
                        accent = MaterialTheme.colorScheme.tertiary,
                        icon = Icons.Default.Blender,
                        sublabel = "Production items on hand",
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        label = "Prepared Value",
                        value = formatMoney(prodTotalValue, currency),
                        accent = MaterialTheme.colorScheme.secondary,
                        icon = Icons.Default.Scale,
                        sublabel = "Waste-adjusted cost",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Search + Action Bar
        item(key = "search_and_actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(strings.searchIngredients) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = strings.searchIngredients)
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stock_search_input")
                    )
                    if (activeSection == StockViewSection.RAW_STOCK) {
                        Button(
                            onClick = {
                                prefillBasicSpec = null
                                showAddDialog = true
                            },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("add_item_button"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Add, contentDescription = strings.addItem)
                            if (isExpanded) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.addItem)
                            }
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onNavigateToRecipes,
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("go_create_subrecipe_btn"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Add, contentDescription = strings.newSubRecipe)
                            if (isExpanded) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.newSubRecipe)
                            }
                        }
                    }
                }

                if (activeSection == StockViewSection.RAW_STOCK) {
                    OutlinedButton(
                        onClick = { showBasicCatalog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_basic_ingredients_button"),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            Icons.Default.LibraryAdd,
                            contentDescription = strings.basicIngredients,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${strings.basicIngredients} (${BilingualIngredientMatcher.basicIngredients.size}+ staples)")
                    }
                }
            }
        }

        // Filter Chips (Status + Categories for Raw Stock)
        item(key = "stock_filters") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null && !lowStockOnly && !outOfStockOnly,
                    onClick = {
                        selectedCategory = null
                        lowStockOnly = false
                        outOfStockOnly = false
                    },
                    label = { Text(strings.allCategories) }
                )
                FilterChip(
                    selected = lowStockOnly,
                    onClick = {
                        lowStockOnly = !lowStockOnly
                        if (lowStockOnly) outOfStockOnly = false
                    },
                    label = { Text(strings.lowStockBadge) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = strings.lowStockBadge,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = outOfStockOnly,
                    onClick = {
                        outOfStockOnly = !outOfStockOnly
                        if (outOfStockOnly) lowStockOnly = false
                    },
                    label = { Text(strings.outOfStockBadge) }
                )
                if (activeSection == StockViewSection.RAW_STOCK) {
                    IngredientCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = {
                                selectedCategory = if (selectedCategory == cat) null else cat
                            },
                            label = { Text(cat.name.replace('_', ' ')) }
                        )
                    }
                }
            }
        }

        if (activeSection == StockViewSection.RAW_STOCK) {
            if (filteredRawItems.isEmpty()) {
                item(key = "empty_raw_stock") {
                    EmptyStatePanel(
                        icon = Icons.Default.Inventory2,
                        title = strings.emptyInventoryTitle,
                        hint = strings.emptyInventoryHint,
                        actionLabel = strings.addItem,
                        onAction = { showAddDialog = true }
                    )
                }
            } else if (isExpanded) {
                val chunked = filteredRawItems.chunked(2)
                items(chunked, key = { pair -> "raw_" + pair.joinToString("_") { it.id } }) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { item ->
                            RawStockCard(
                                item = item,
                                currency = currency,
                                language = lang,
                                strings = strings,
                                onAdjustStock = { adjustRawTarget = item },
                                onLogWaste = { wasteTargetItem = item },
                                onDelete = { onDeleteItem(item.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                items(filteredRawItems, key = { "raw_${it.id}" }) { item ->
                    RawStockCard(
                        item = item,
                        currency = currency,
                        language = lang,
                        strings = strings,
                        onAdjustStock = { adjustRawTarget = item },
                        onLogWaste = { wasteTargetItem = item },
                        onDelete = { onDeleteItem(item.id) }
                    )
                }
            }
        } else {
            // Production Stock Section (Sauces, Marinades, Stocks, Prep Batches)
            if (filteredProductionItems.isEmpty()) {
                item(key = "empty_production_stock") {
                    EmptyStatePanel(
                        icon = Icons.Default.Blender,
                        title = strings.emptyProductionTitle,
                        hint = strings.emptyProductionHint,
                        actionLabel = strings.newSubRecipe,
                        onAction = onNavigateToRecipes
                    )
                }
            } else if (isExpanded) {
                val chunked = filteredProductionItems.chunked(2)
                items(chunked, key = { pair -> "prod_" + pair.joinToString("_") { it.id } }) { rowSubs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowSubs.forEach { sub ->
                            val summary = uiState.subRecipeReports[sub.id]
                                ?: RecipeCostEngine.evaluateSubRecipe(sub, uiState.inventory, uiState.recipes)
                            ProductionStockCard(
                                subRecipe = sub,
                                summary = summary,
                                currency = currency,
                                language = lang,
                                strings = strings,
                                onProduceBatch = { produceTargetSub = sub },
                                onAdjustStock = { adjustProdTarget = sub },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowSubs.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                items(filteredProductionItems, key = { "prod_${it.id}" }) { sub ->
                    val summary = uiState.subRecipeReports[sub.id]
                        ?: RecipeCostEngine.evaluateSubRecipe(sub, uiState.inventory, uiState.recipes)
                    ProductionStockCard(
                        subRecipe = sub,
                        summary = summary,
                        currency = currency,
                        language = lang,
                        strings = strings,
                        onProduceBatch = { produceTargetSub = sub },
                        onAdjustStock = { adjustProdTarget = sub }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    if (showBasicCatalog) {
        BasicIngredientsCatalogDialog(
            existingInventory = uiState.inventory,
            strings = strings,
            onDismiss = { showBasicCatalog = false },
            onAddSelected = { specs ->
                onAddBasicIngredients(specs)
                showBasicCatalog = false
            },
            onQuickFillSingle = { spec ->
                showBasicCatalog = false
                prefillBasicSpec = spec
                showAddDialog = true
            }
        )
    }

    if (showAddDialog) {
        AddCostWasteDialog(
            currency = currency,
            strings = strings,
            existingInventory = uiState.inventory,
            prefillSpec = prefillBasicSpec,
            onDismiss = {
                showAddDialog = false
                prefillBasicSpec = null
            },
            onConfirm = { name, ar, cat, qty, unit, cost, waste, wasteUnit, minStock, supplier, allowAutoCorrect ->
                onAddOrMergeItem(name, ar, cat, qty, unit, cost, waste, wasteUnit, minStock, supplier, allowAutoCorrect)
                showAddDialog = false
                prefillBasicSpec = null
            }
        )
    }

    adjustRawTarget?.let { target ->
        AdjustRawStockDialog(
            item = target,
            currency = currency,
            strings = strings,
            onDismiss = { adjustRawTarget = null },
            onConfirm = { mode, amount, unit, extraCost, newMin, reason ->
                onAdjustRawStock(target, mode, amount, unit, extraCost, newMin, reason)
                adjustRawTarget = null
            }
        )
    }

    wasteTargetItem?.let { target ->
        LogWasteDialog(
            item = target,
            currency = currency,
            strings = strings,
            onDismiss = { wasteTargetItem = null },
            onConfirm = { amount, unit, reason ->
                onLogWaste(target, amount, unit, reason)
                wasteTargetItem = null
            }
        )
    }

    produceTargetSub?.let { sub ->
        val summary = uiState.subRecipeReports[sub.id]
            ?: RecipeCostEngine.evaluateSubRecipe(sub, uiState.inventory, uiState.recipes)
        ProduceBatchDialog(
            subRecipe = sub,
            summary = summary,
            inventory = uiState.inventory,
            allRecipes = uiState.recipes,
            currency = currency,
            strings = strings,
            onDismiss = { produceTargetSub = null },
            onConfirm = { producedQty, deductRaw ->
                onProduceSubRecipe(sub, producedQty, deductRaw)
                produceTargetSub = null
            }
        )
    }

    adjustProdTarget?.let { sub ->
        AdjustProductionStockDialog(
            subRecipe = sub,
            strings = strings,
            onDismiss = { adjustProdTarget = null },
            onConfirm = { mode, amount, unit, newMin ->
                onAdjustProductionStock(sub, mode, amount, unit, newMin)
                adjustProdTarget = null
            }
        )
    }
}

@Composable
private fun RawStockCard(
    item: InventoryItem,
    currency: String,
    language: AppLanguage,
    strings: AppStrings,
    onAdjustStock: () -> Unit,
    onLogWaste: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOut = item.currentStock <= 0.0001
    val isLow = !isOut && item.lowStock
    val statusColor = when {
        isOut -> MaterialTheme.colorScheme.error
        isLow -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val statusLabel = when {
        isOut -> strings.outOfStockBadge
        isLow -> strings.lowStockBadge
        else -> "IN STOCK"
    }

    val barBase = maxOf(item.grossQuantity, item.minStockLevel * 2.0, 0.001)
    val availableRatio = (item.currentStock / barBase).toFloat().coerceIn(0f, 1f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stock_card_${item.canonicalKey}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isOut || isLow) 1.5.dp else 1.dp,
            color = if (isOut || isLow) statusColor.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: Name + Category/Status + Prominent Available Quantity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.displayTitle(language),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val secondary = item.secondaryTitle(language)
                        if (secondary.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = secondary,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = CairoArabicFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = statusColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = item.categoryEnum.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Prominent Available Quantity Box
                Surface(
                    color = statusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = strings.availableQty,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                        Text(
                            text = formatQtyWithSmartUnit(item.currentStock, item.unitEnum),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = statusColor
                        )
                    }
                }
            }

            // Visual Available Stock Health Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (availableRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(availableRatio)
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusColor)
                    )
                }
            }

            // 4-column precision metrics: Gross | Waste | Min Alert | Net Unit Cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StockMetricCell(
                    label = strings.gross,
                    value = formatQtyWithSmartUnit(item.grossQuantity, item.unitEnum),
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                StockMetricCell(
                    label = strings.waste,
                    value = "${formatQtyWithSmartUnit(item.wasteQuantity, item.unitEnum)} (${String.format(Locale.US, "%.0f", item.wastePercent)}%)",
                    valueColor = if (item.wasteQuantity > 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                StockMetricCell(
                    label = "Min Alert",
                    value = formatQtyWithSmartUnit(item.minStockLevel, item.unitEnum),
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StockMetricCell(
                    label = strings.effectiveCost,
                    value = "${formatMoney(item.effectiveNetUnitCost, currency)}/${item.unit}",
                    valueColor = MaterialTheme.colorScheme.primary
                )
            }

            // Action row: Adjust Stock | Log Waste | Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onAdjustStock,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("adjust_stock_btn_${item.canonicalKey}"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = strings.adjustStock,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.adjustStock, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onLogWaste,
                    modifier = Modifier.testTag("log_waste_btn_${item.canonicalKey}"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Scale,
                        contentDescription = strings.logWaste,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.logWaste, maxLines = 1)
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_item_btn_${item.canonicalKey}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete ${item.nameEn}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductionStockCard(
    subRecipe: Recipe,
    summary: SubRecipeCostSummary,
    currency: String,
    language: AppLanguage,
    strings: AppStrings,
    onProduceBatch: () -> Unit,
    onAdjustStock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEmpty = subRecipe.productionStock <= 0.0001
    val isLow = !isEmpty && subRecipe.lowProductionStock
    val statusColor = when {
        isEmpty -> MaterialTheme.colorScheme.error
        isLow -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val statusLabel = when {
        isEmpty -> "NOT PREPPED"
        isLow -> "LOW PREP"
        else -> "READY"
    }

    val batchesAvailable = if (summary.yieldQuantity > 0.0001) {
        subRecipe.productionStock / summary.yieldQuantity
    } else 0.0
    val preparedValue = subRecipe.productionStock * summary.costPerYieldUnit
    val barTarget = maxOf(summary.yieldQuantity, subRecipe.minProductionStock * 2.0, 0.001)
    val fillRatio = (subRecipe.productionStock / barTarget).toFloat().coerceIn(0f, 1f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("production_stock_card_${subRecipe.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isEmpty || isLow) 1.5.dp else 1.dp,
            color = if (isEmpty || isLow) statusColor.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
        ),
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
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = statusColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "${String.format(Locale.US, "%.1f", batchesAvailable)} batch(es)  •  ${summary.usedInDishCount} dish(es)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = statusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = strings.availableQty,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                        Text(
                            text = formatQtyWithSmartUnit(subRecipe.productionStock, summary.yieldUnit),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = statusColor
                        )
                    }
                }
            }

            // Visual Production Fill Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (fillRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillRatio)
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusColor)
                    )
                }
            }

            // 3-column production metrics: Batch Yield | On-Hand Value | Min Alert
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StockMetricCell(
                    label = strings.batchYield,
                    value = formatQtyWithSmartUnit(summary.yieldQuantity, summary.yieldUnit),
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                StockMetricCell(
                    label = "On-Hand Value",
                    value = formatMoney(preparedValue, currency),
                    valueColor = MaterialTheme.colorScheme.primary
                )
                StockMetricCell(
                    label = "Min Prep Alert",
                    value = if (subRecipe.minProductionStock > 0.0) {
                        formatQtyWithSmartUnit(subRecipe.minProductionStock, summary.yieldUnit)
                    } else "Off",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action buttons: Produce Batch (cooks batch & deducts raw stock) + Adjust Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onProduceBatch,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("produce_batch_btn_${subRecipe.id}"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Blender,
                        contentDescription = strings.produceBatch,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.produceBatch, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onAdjustStock,
                    modifier = Modifier.testTag("adjust_prod_btn_${subRecipe.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = strings.adjustStock,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.adjustStock, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StockMetricCell(
    label: String,
    value: String,
    valueColor: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}
