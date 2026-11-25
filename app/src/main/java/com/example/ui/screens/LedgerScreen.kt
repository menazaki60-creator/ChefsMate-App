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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.AppStrings
import com.example.model.AppLanguage
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.UnitType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KpiTile
import com.example.ui.components.UrgentAlertBanner
import com.example.ui.components.formatMoney
import com.example.ui.components.formatQtyWithSmartUnit
import com.example.ui.theme.CairoArabicFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState
import java.util.Locale

@Composable
fun LedgerScreen(
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
        supplier: String
    ) -> Unit,
    onLogWaste: (InventoryItem, Double, UnitType, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onNavigateToAlertsTarget: () -> Unit
) {
    val currency = uiState.profile?.currencySymbol ?: "$"
    val lang = uiState.preferences.language

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<IngredientCategory?>(null) }
    var lowStockOnly by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var wasteTargetItem by remember { mutableStateOf<InventoryItem?>(null) }

    val filteredItems = remember(uiState.inventory, searchQuery, selectedCategory, lowStockOnly) {
        uiState.inventory.filter { item ->
            val matchesCategory = selectedCategory == null || item.categoryEnum == selectedCategory
            val matchesLowStock = !lowStockOnly || item.lowStock
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isEmpty() ||
                item.nameEn.lowercase().contains(q) ||
                item.nameAr.lowercase().contains(q) ||
                item.aliases.any { it.lowercase().contains(q) }
            matchesCategory && matchesLowStock && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ledger_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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

        // Minimal Hero KPI Strip
        item(key = "kpi_strip") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiTile(
                    label = strings.netUsage,
                    value = "${String.format(Locale.US, "%.1f", uiState.overallNetYieldPct)}%",
                    accent = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Default.Verified,
                    sublabel = "${uiState.inventory.size} items",
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    label = strings.waste,
                    value = formatMoney(uiState.totalWasteCost, currency),
                    accent = MaterialTheme.colorScheme.error,
                    icon = Icons.Default.TrendingDown,
                    sublabel = "of ${formatMoney(uiState.totalInventoryValue, currency)}",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Search + Quick Add bar
        item(key = "search_and_add") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                        .testTag("ledger_search_input")
                )
                Button(
                    onClick = { showAddDialog = true },
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
            }
        }

        // Category & Low-Stock filter chips
        item(key = "category_filters") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null && !lowStockOnly,
                    onClick = {
                        selectedCategory = null
                        lowStockOnly = false
                    },
                    label = { Text(strings.allCategories) }
                )
                FilterChip(
                    selected = lowStockOnly,
                    onClick = { lowStockOnly = !lowStockOnly },
                    label = { Text(strings.lowStockBadge) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = strings.lowStockBadge,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
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

        // Inventory List or Empty State
        if (filteredItems.isEmpty()) {
            item(key = "empty_ledger") {
                EmptyStatePanel(
                    icon = Icons.Default.Inventory2,
                    title = strings.emptyInventoryTitle,
                    hint = strings.emptyInventoryHint,
                    actionLabel = strings.addItem,
                    onAction = { showAddDialog = true }
                )
            }
        } else if (isExpanded) {
            val chunked = filteredItems.chunked(2)
            items(chunked, key = { pair -> pair.joinToString("_") { it.id } }) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { item ->
                        InventoryLedgerCard(
                            item = item,
                            currency = currency,
                            language = lang,
                            strings = strings,
                            onLogWaste = { wasteTargetItem = item },
                            onDelete = { onDeleteItem(item.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            items(filteredItems, key = { it.id }) { item ->
                InventoryLedgerCard(
                    item = item,
                    currency = currency,
                    language = lang,
                    strings = strings,
                    onLogWaste = { wasteTargetItem = item },
                    onDelete = { onDeleteItem(item.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    if (showAddDialog) {
        AddCostWasteDialog(
            currency = currency,
            strings = strings,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, ar, cat, qty, unit, cost, waste, wasteUnit, minStock, supplier ->
                onAddOrMergeItem(name, ar, cat, qty, unit, cost, waste, wasteUnit, minStock, supplier)
                showAddDialog = false
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
}

@Composable
private fun InventoryLedgerCard(
    item: InventoryItem,
    currency: String,
    language: AppLanguage,
    strings: AppStrings,
    onLogWaste: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val netRatio = if (item.grossQuantity > 0.0001) {
        (item.netUsableQuantity / item.grossQuantity).toFloat().coerceIn(0f, 1f)
    } else 1f
    val wasteRatio = (1f - netRatio).coerceIn(0f, 1f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("inventory_card_${item.canonicalKey}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (item.lowStock) 1.5.dp else 1.dp,
            color = if (item.lowStock) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: Bilingual name + Low stock badge + Total Cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.categoryEnum.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.lowStock) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = strings.lowStockBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatMoney(item.totalCost, currency),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${formatMoney(item.effectiveNetUnitCost, currency)}/${item.unit} net",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Visual Net Usage vs Waste Yield Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (netRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(netRatio)
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                    }
                    if (wasteRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(wasteRatio)
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }

                // 4-column precision ledger metrics: Gross | Waste | Net Usage | On Hand
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LedgerMetricCell(
                        label = strings.gross,
                        value = formatQtyWithSmartUnit(item.grossQuantity, item.unitEnum),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    LedgerMetricCell(
                        label = strings.waste,
                        value = "${formatQtyWithSmartUnit(item.wasteQuantity, item.unitEnum)} (${String.format(Locale.US, "%.0f", item.wastePercent)}%)",
                        valueColor = if (item.wasteQuantity > 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LedgerMetricCell(
                        label = strings.netUsage,
                        value = formatQtyWithSmartUnit(item.netUsableQuantity, item.unitEnum),
                        valueColor = MaterialTheme.colorScheme.secondary
                    )
                    LedgerMetricCell(
                        label = strings.inStock,
                        value = formatQtyWithSmartUnit(item.currentStock, item.unitEnum),
                        valueColor = if (item.lowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onLogWaste,
                    modifier = Modifier.testTag("log_waste_btn_${item.canonicalKey}"),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Scale,
                        contentDescription = strings.logWaste,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.logWaste)
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
private fun LedgerMetricCell(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
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

@Composable
private fun AddCostWasteDialog(
    currency: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        arabicName: String,
        category: IngredientCategory,
        quantity: Double,
        unit: UnitType,
        totalCost: Double,
        wasteQty: Double,
        wasteUnit: UnitType,
        minStock: Double,
        supplier: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var arabicName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(IngredientCategory.PROTEIN) }
    var qtyText by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(UnitType.KG) }
    var costText by remember { mutableStateOf("10") }
    var wasteText by remember { mutableStateOf("100") }
    var wasteUnit by remember { mutableStateOf(UnitType.G) }
    var minStockText by remember { mutableStateOf("0.5") }
    var supplier by remember { mutableStateOf("") }

    val qty = qtyText.toDoubleOrNull() ?: 0.0
    val cost = costText.toDoubleOrNull() ?: 0.0
    val rawWaste = wasteText.toDoubleOrNull() ?: 0.0
    val wasteInBase = UnitType.convert(rawWaste, wasteUnit, unit)
    val netPreview = (qty - wasteInBase).coerceAtLeast(0.0)
    val netUnitCostPreview = if (netPreview > 0.0001) cost / netPreview else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addItem) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ingredient (EN or العربية, e.g. Chicken / دجاج)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_item_name_input")
                )
                OutlinedTextField(
                    value = arabicName,
                    onValueChange = { arabicName = it },
                    label = { Text("Arabic Alias (optional, auto-matched)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Quantity & Unit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text("Gross Qty") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_item_qty_input")
                    )
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Total Cost ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_item_cost_input")
                    )
                }

                // Unit selector
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

                // Initial Waste + Waste Unit (supports e.g. 1kg gross + 100g waste!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = wasteText,
                        onValueChange = { wasteText = it },
                        label = { Text("Initial Waste") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_item_waste_input")
                    )
                    OutlinedTextField(
                        value = minStockText,
                        onValueChange = { minStockText = it },
                        label = { Text("Min Alert (${unit.symbol})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Waste Unit:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UnitType.entries.forEach { u ->
                        FilterChip(
                            selected = wasteUnit == u,
                            onClick = { wasteUnit = u },
                            label = { Text(u.symbol) }
                        )
                    }
                }

                // Category chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IngredientCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c.name.replace('_', ' ')) }
                        )
                    }
                }

                OutlinedTextField(
                    value = supplier,
                    onValueChange = { supplier = it },
                    label = { Text("Supplier (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Live Net Usage Preview Box
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Live Net Usage Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "${strings.netUsage}: ${formatQtyWithSmartUnit(netPreview, unit)}  •  ${strings.effectiveCost}: ${formatMoney(netUnitCostPreview, currency)}/${unit.symbol}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && qty > 0.0) {
                        onConfirm(
                            name,
                            arabicName,
                            category,
                            qty,
                            unit,
                            cost,
                            rawWaste,
                            wasteUnit,
                            minStockText.toDoubleOrNull() ?: 0.5,
                            supplier
                        )
                    }
                },
                enabled = name.isNotBlank() && qty > 0.0,
                modifier = Modifier.testTag("confirm_add_item_button")
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun LogWasteDialog(
    item: InventoryItem,
    currency: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (Double, UnitType, String) -> Unit
) {
    var wasteQtyText by remember { mutableStateOf("100") }
    var wasteUnit by remember {
        mutableStateOf(if (item.unitEnum == UnitType.KG) UnitType.G else item.unitEnum)
    }
    var reason by remember { mutableStateOf("Trim / Prep") }

    val parsed = wasteQtyText.toDoubleOrNull() ?: 0.0
    val converted = UnitType.convert(parsed, wasteUnit, item.unitEnum)
    val costImpact = converted * item.unitCost

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = strings.logWaste,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("${strings.logWaste}: ${item.nameEn}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = wasteQtyText,
                    onValueChange = { wasteQtyText = it },
                    label = { Text("Waste Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("waste_amount_input")
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UnitType.entries.forEach { u ->
                        FilterChip(
                            selected = wasteUnit == u,
                            onClick = { wasteUnit = u },
                            label = { Text(u.symbol) }
                        )
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (Trim, Spoilage, Over-prep...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Waste cost impact: ${formatMoney(costImpact, currency)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (parsed > 0.0) onConfirm(parsed, wasteUnit, reason) },
                enabled = parsed > 0.0,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_log_waste_button")
            ) {
                Text(strings.logWaste)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
