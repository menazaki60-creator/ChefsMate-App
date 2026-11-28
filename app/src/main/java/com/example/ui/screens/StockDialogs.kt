package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Blender
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.BasicIngredientSpec
import com.example.data.BilingualIngredientMatcher
import com.example.data.SpellSuggestion
import com.example.domain.RecipeCostEngine
import com.example.domain.SubRecipeCostSummary
import com.example.i18n.AppStrings
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.StockAdjustMode
import com.example.model.UnitType
import com.example.ui.components.formatMoney
import com.example.ui.components.formatQtyWithSmartUnit
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@Composable
fun AdjustRawStockDialog(
    item: InventoryItem,
    currency: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (
        mode: StockAdjustMode,
        amount: Double,
        unit: UnitType,
        extraCost: Double,
        newMinStock: Double?,
        reason: String
    ) -> Unit
) {
    var mode by remember { mutableStateOf(StockAdjustMode.ADD) }
    var amountText by remember { mutableStateOf("1") }
    var selectedUnit by remember { mutableStateOf(item.unitEnum) }
    var extraCostText by remember { mutableStateOf("") }
    var minStockText by remember {
        mutableStateOf(
            String.format(Locale.US, "%.2f", item.minStockLevel).trimEnd('0').trimEnd('.')
        )
    }
    var reason by remember { mutableStateOf("") }

    val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
    val converted = UnitType.convert(parsedAmount.coerceAtLeast(0.0), selectedUnit, item.unitEnum)
    val projectedStock = when (mode) {
        StockAdjustMode.ADD -> (item.currentStock + converted).coerceAtLeast(0.0)
        StockAdjustMode.DEDUCT -> (item.currentStock - converted).coerceAtLeast(0.0)
        StockAdjustMode.SET_EXACT -> converted.coerceAtLeast(0.0)
    }
    val parsedMin = minStockText.toDoubleOrNull()
    val minChanged = parsedMin != null && kotlin.math.abs(parsedMin - item.minStockLevel) > 0.0001
    val canSubmit = when (mode) {
        StockAdjustMode.SET_EXACT -> amountText.toDoubleOrNull() != null && parsedAmount >= 0.0
        StockAdjustMode.ADD, StockAdjustMode.DEDUCT -> parsedAmount > 0.0 || minChanged
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Tune,
                contentDescription = strings.adjustStock,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column {
                Text("${strings.adjustStock}: ${item.nameEn}")
                Text(
                    text = "Current available: ${formatQtyWithSmartUnit(item.currentStock, item.unitEnum)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode Selector: Restock (+), Deduct (-), Exact Count (=)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = mode == StockAdjustMode.ADD,
                        onClick = {
                            mode = StockAdjustMode.ADD
                            if (amountText == "0" || amountText.isBlank()) amountText = "1"
                        },
                        label = { Text("+ Restock") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adjust_mode_add")
                    )
                    FilterChip(
                        selected = mode == StockAdjustMode.DEDUCT,
                        onClick = {
                            mode = StockAdjustMode.DEDUCT
                            if (amountText == "0" || amountText.isBlank()) amountText = "1"
                        },
                        label = { Text("- Use") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adjust_mode_deduct")
                    )
                    FilterChip(
                        selected = mode == StockAdjustMode.SET_EXACT,
                        onClick = {
                            mode = StockAdjustMode.SET_EXACT
                            amountText = String.format(Locale.US, "%.2f", item.currentStock)
                                .trimEnd('0').trimEnd('.')
                            selectedUnit = item.unitEnum
                        },
                        label = { Text("= Count") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adjust_mode_exact")
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = {
                        Text(
                            when (mode) {
                                StockAdjustMode.ADD -> "Quantity to Add"
                                StockAdjustMode.DEDUCT -> "Quantity Used / Deducted"
                                StockAdjustMode.SET_EXACT -> "Exact Physical Count Available"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("adjust_raw_amount_input")
                )

                // Compatible unit selector
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UnitType.entries.forEach { u ->
                        FilterChip(
                            selected = selectedUnit == u,
                            onClick = { selectedUnit = u },
                            label = { Text(u.symbol) }
                        )
                    }
                }

                if (mode == StockAdjustMode.ADD) {
                    OutlinedTextField(
                        value = extraCostText,
                        onValueChange = { extraCostText = it },
                        label = { Text("Purchase Cost ($currency, optional)") },
                        placeholder = { Text("Auto-uses ${formatMoney(item.unitCost, currency)}/${item.unit}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = minStockText,
                    onValueChange = { minStockText = it },
                    label = { Text("Low Stock Alert Level (${item.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Note (optional, e.g. Morning delivery / Prep)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "New Available Qty",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = formatQtyWithSmartUnit(projectedStock, item.unitEnum),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveMode = if (mode != StockAdjustMode.SET_EXACT && parsedAmount <= 0.0 && minChanged) {
                        StockAdjustMode.SET_EXACT
                    } else mode
                    val effectiveAmount = if (effectiveMode == StockAdjustMode.SET_EXACT && mode != StockAdjustMode.SET_EXACT) {
                        item.currentStock
                    } else parsedAmount
                    val effectiveUnit = if (effectiveMode == StockAdjustMode.SET_EXACT && mode != StockAdjustMode.SET_EXACT) {
                        item.unitEnum
                    } else selectedUnit
                    onConfirm(
                        effectiveMode,
                        effectiveAmount,
                        effectiveUnit,
                        extraCostText.toDoubleOrNull() ?: 0.0,
                        parsedMin,
                        reason
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.testTag("confirm_adjust_raw_stock")
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
fun ProduceBatchDialog(
    subRecipe: Recipe,
    summary: SubRecipeCostSummary,
    inventory: List<InventoryItem>,
    allRecipes: List<Recipe>,
    currency: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (producedQtyInYieldUnit: Double, deductRawIngredients: Boolean) -> Unit
) {
    val baseYield = subRecipe.yieldQuantity.coerceAtLeast(0.01)
    var qtyText by remember {
        mutableStateOf(String.format(Locale.US, "%.2f", baseYield).trimEnd('0').trimEnd('.'))
    }
    var deductRaw by remember { mutableStateOf(true) }

    val producedQty = qtyText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val multiplier = if (baseYield > 0.0001) producedQty / baseYield else 0.0
    val batchCost = summary.totalWasteAdjustedBatchCost * multiplier

    val rawDeductions = remember(subRecipe, multiplier, inventory, allRecipes) {
        if (multiplier <= 0.0001) emptyList()
        else {
            val invById = inventory.associateBy { it.id }
            val recById = allRecipes.associateBy { it.id }
            val out = mutableMapOf<String, Double>()
            RecipeCostEngine.flattenRawInventoryUsage(
                recipe = subRecipe,
                multiplier = multiplier,
                inventoryById = invById,
                recipesById = recById,
                output = out
            )
            out.mapNotNull { (itemId, neededQty) ->
                val inv = invById[itemId] ?: return@mapNotNull null
                Triple(inv, neededQty, inv.currentStock >= neededQty)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Blender,
                contentDescription = strings.produceBatch,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Column {
                Text("${strings.produceBatch}: ${subRecipe.nameEn}")
                Text(
                    text = "1 batch = ${formatQtyWithSmartUnit(baseYield, subRecipe.yieldUnitEnum)}  •  On hand: ${formatQtyWithSmartUnit(subRecipe.productionStock, subRecipe.yieldUnitEnum)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Quick Batch Multipliers
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.5, 1.0, 2.0, 3.0).forEach { mult ->
                        val presetQty = baseYield * mult
                        val label = if (mult == 0.5) "0.5x" else "${mult.toInt()}x"
                        val isSelected = kotlin.math.abs(producedQty - presetQty) < 0.01
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                qtyText = String.format(Locale.US, "%.2f", presetQty)
                                    .trimEnd('0').trimEnd('.')
                            },
                            label = { Text("$label (${formatQtyWithSmartUnit(presetQty, subRecipe.yieldUnitEnum)})") }
                        )
                    }
                }

                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("Produced Quantity (${subRecipe.yieldUnit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("produce_batch_qty_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Deduct raw ingredients from stock",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically subtracts ingredients used to cook this batch",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deductRaw,
                        onCheckedChange = { deductRaw = it },
                        modifier = Modifier.testTag("produce_deduct_raw_switch")
                    )
                }

                if (deductRaw && rawDeductions.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Raw Stock to Deduct (${rawDeductions.size} items)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        rawDeductions.forEach { (inv, needed, hasEnough) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = inv.nameEn,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "-${formatQtyWithSmartUnit(needed, inv.unitEnum)} (avail: ${formatQtyWithSmartUnit(inv.currentStock, inv.unitEnum)})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = if (hasEnough) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "New Prepared Stock",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = formatQtyWithSmartUnit(subRecipe.productionStock + producedQty, subRecipe.yieldUnitEnum),
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Text(
                            text = "Estimated batch cost: ${formatMoney(batchCost, currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (producedQty > 0.0) onConfirm(producedQty, deductRaw) },
                enabled = producedQty > 0.0,
                modifier = Modifier.testTag("confirm_produce_batch_btn")
            ) {
                Text(strings.produceBatch)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
fun AdjustProductionStockDialog(
    subRecipe: Recipe,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (
        mode: StockAdjustMode,
        amount: Double,
        unit: UnitType,
        newMinProductionStock: Double?
    ) -> Unit
) {
    var mode by remember { mutableStateOf(StockAdjustMode.SET_EXACT) }
    var amountText by remember {
        mutableStateOf(
            String.format(Locale.US, "%.2f", subRecipe.productionStock).trimEnd('0').trimEnd('.')
        )
    }
    var selectedUnit by remember { mutableStateOf(subRecipe.yieldUnitEnum) }
    var minAlertText by remember {
        mutableStateOf(
            String.format(Locale.US, "%.2f", subRecipe.minProductionStock).trimEnd('0').trimEnd('.')
        )
    }

    val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
    val converted = UnitType.convert(parsedAmount.coerceAtLeast(0.0), selectedUnit, subRecipe.yieldUnitEnum)
    val projected = when (mode) {
        StockAdjustMode.ADD -> (subRecipe.productionStock + converted).coerceAtLeast(0.0)
        StockAdjustMode.DEDUCT -> (subRecipe.productionStock - converted).coerceAtLeast(0.0)
        StockAdjustMode.SET_EXACT -> converted.coerceAtLeast(0.0)
    }
    val parsedMin = minAlertText.toDoubleOrNull()
    val canSubmit = when (mode) {
        StockAdjustMode.SET_EXACT -> amountText.toDoubleOrNull() != null && parsedAmount >= 0.0
        StockAdjustMode.ADD, StockAdjustMode.DEDUCT -> parsedAmount > 0.0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Tune,
                contentDescription = strings.adjustStock,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Column {
                Text("${strings.adjustStock}: ${subRecipe.nameEn}")
                Text(
                    text = "Current prepared: ${formatQtyWithSmartUnit(subRecipe.productionStock, subRecipe.yieldUnitEnum)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = mode == StockAdjustMode.SET_EXACT,
                        onClick = {
                            mode = StockAdjustMode.SET_EXACT
                            amountText = String.format(Locale.US, "%.2f", subRecipe.productionStock)
                                .trimEnd('0').trimEnd('.')
                            selectedUnit = subRecipe.yieldUnitEnum
                        },
                        label = { Text("= Count") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == StockAdjustMode.ADD,
                        onClick = {
                            mode = StockAdjustMode.ADD
                            amountText = String.format(Locale.US, "%.2f", subRecipe.yieldQuantity)
                                .trimEnd('0').trimEnd('.')
                        },
                        label = { Text("+ Add") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == StockAdjustMode.DEDUCT,
                        onClick = {
                            mode = StockAdjustMode.DEDUCT
                            amountText = "100"
                        },
                        label = { Text("- Use") },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = {
                        Text(
                            when (mode) {
                                StockAdjustMode.SET_EXACT -> "Exact Prepared Qty Available"
                                StockAdjustMode.ADD -> "Quantity to Add"
                                StockAdjustMode.DEDUCT -> "Quantity Used / Spoiled"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("adjust_prod_amount_input")
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UnitType.entries.forEach { u ->
                        FilterChip(
                            selected = selectedUnit == u,
                            onClick = { selectedUnit = u },
                            label = { Text(u.symbol) }
                        )
                    }
                }

                OutlinedTextField(
                    value = minAlertText,
                    onValueChange = { minAlertText = it },
                    label = { Text("Min Prep Alert (${subRecipe.yieldUnit}, 0 = off)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "New Prepared Qty",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = formatQtyWithSmartUnit(projected, subRecipe.yieldUnitEnum),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(mode, parsedAmount, selectedUnit, parsedMin) },
                enabled = canSubmit,
                modifier = Modifier.testTag("confirm_adjust_prod_stock")
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
fun AddCostWasteDialog(
    currency: String,
    strings: AppStrings,
    existingInventory: List<InventoryItem>,
    prefillSpec: BasicIngredientSpec?,
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
        supplier: String,
        allowAutoCorrectTypos: Boolean
    ) -> Unit
) {
    var name by remember(prefillSpec) { mutableStateOf(prefillSpec?.nameEn ?: "") }
    var arabicName by remember(prefillSpec) { mutableStateOf(prefillSpec?.nameAr ?: "") }
    var category by remember(prefillSpec) { mutableStateOf(prefillSpec?.category ?: IngredientCategory.PROTEIN) }
    var qtyText by remember { mutableStateOf("1") }
    var unit by remember(prefillSpec) { mutableStateOf(prefillSpec?.defaultUnit ?: UnitType.KG) }
    var costText by remember { mutableStateOf("10") }
    var wasteText by remember { mutableStateOf("100") }
    var wasteUnit by remember(prefillSpec) {
        mutableStateOf(
            when (prefillSpec?.defaultUnit) {
                UnitType.L -> UnitType.ML
                UnitType.PCS -> UnitType.PCS
                else -> UnitType.G
            }
        )
    }
    var minStockText by remember(prefillSpec) {
        mutableStateOf(prefillSpec?.defaultMinStock?.toString() ?: "0.5")
    }
    var supplier by remember { mutableStateOf("") }
    var autoCorrectEnabled by remember { mutableStateOf(true) }
    var pendingSpellConfirm by remember { mutableStateOf<SpellSuggestion?>(null) }

    val spellSuggestions = remember(name, existingInventory) {
        BilingualIngredientMatcher.suggestCorrections(name, existingInventory, maxResults = 4)
    }
    val exactMatch = remember(spellSuggestions) { spellSuggestions.firstOrNull { it.isExactMatch } }
    val topTypo = remember(name, existingInventory) {
        BilingualIngredientMatcher.bestTypoSuggestion(name, existingInventory)
    }
    val prefixMatches = remember(spellSuggestions) {
        spellSuggestions.filter { it.isPrefixMatch }
    }

    fun applySuggestion(suggestion: SpellSuggestion) {
        name = suggestion.nameEn
        if (suggestion.nameAr.isNotBlank()) arabicName = suggestion.nameAr
        category = suggestion.category
        unit = suggestion.defaultUnit
        wasteUnit = when (suggestion.defaultUnit) {
            UnitType.L -> UnitType.ML
            UnitType.PCS -> UnitType.PCS
            else -> UnitType.G
        }
    }

    val qty = qtyText.toDoubleOrNull() ?: 0.0
    val cost = costText.toDoubleOrNull() ?: 0.0
    val rawWaste = wasteText.toDoubleOrNull() ?: 0.0
    val wasteInBase = UnitType.convert(rawWaste, wasteUnit, unit)
    val netPreview = (qty - wasteInBase).coerceAtLeast(0.0)
    val netUnitCostPreview = if (netPreview > 0.0001) cost / netPreview else 0.0

    fun submitItem(finalName: String, finalAr: String, finalCat: IngredientCategory, allowAutoCorrect: Boolean) {
        onConfirm(
            finalName,
            finalAr,
            finalCat,
            qty,
            unit,
            cost,
            rawWaste,
            wasteUnit,
            minStockText.toDoubleOrNull() ?: 0.5,
            supplier,
            allowAutoCorrect
        )
    }

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
                    label = { Text("Ingredient (EN or العربية, e.g. Garlic / ثوم)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_item_name_input")
                )

                if (exactMatch != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Matched",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Matched: ${exactMatch.nameEn}${if (exactMatch.nameAr.isNotBlank()) " (${exactMatch.nameAr})" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            if (arabicName.isBlank() && exactMatch.nameAr.isNotBlank()) {
                                TextButton(
                                    onClick = { applySuggestion(exactMatch) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("Fill")
                                }
                            }
                        }
                    }
                } else if (topTypo != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spell_suggestion_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.AutoFixHigh,
                                    contentDescription = strings.didYouMean,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "${strings.didYouMean} \"${topTypo.nameEn}\"${if (topTypo.nameAr.isNotBlank()) " (${topTypo.nameAr})" else ""}?",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { applySuggestion(topTypo) },
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("accept_spell_suggestion_btn"),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text("Use ${topTypo.nameEn}", style = MaterialTheme.typography.labelMedium)
                                }
                                spellSuggestions
                                    .filter { it.canonicalKey != topTypo.canonicalKey && !it.isExactMatch }
                                    .take(2)
                                    .forEach { alt ->
                                        SuggestionChip(
                                            onClick = { applySuggestion(alt) },
                                            label = { Text(alt.nameEn) }
                                        )
                                    }
                            }
                        }
                    }
                } else if (prefixMatches.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        prefixMatches.forEach { sug ->
                            SuggestionChip(
                                onClick = { applySuggestion(sug) },
                                label = {
                                    Text("${sug.nameEn}${if (sug.nameAr.isNotBlank()) " • ${sug.nameAr}" else ""}")
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-correct misspellings on save",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoCorrectEnabled,
                        onCheckedChange = { autoCorrectEnabled = it },
                        modifier = Modifier.testTag("autocorrect_switch")
                    )
                }

                OutlinedTextField(
                    value = arabicName,
                    onValueChange = { arabicName = it },
                    label = { Text("Arabic Alias (optional, auto-matched)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Live Available & Net Cost Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "${strings.availableQty}: ${formatQtyWithSmartUnit(netPreview, unit)}  •  ${strings.effectiveCost}: ${formatMoney(netUnitCostPreview, currency)}/${unit.symbol}",
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
                        if (topTypo != null) {
                            if (autoCorrectEnabled && topTypo.isHighConfidenceAutoCorrect) {
                                submitItem(
                                    finalName = name,
                                    finalAr = arabicName.ifBlank { topTypo.nameAr },
                                    finalCat = topTypo.category,
                                    allowAutoCorrect = true
                                )
                            } else {
                                pendingSpellConfirm = topTypo
                            }
                        } else {
                            submitItem(name, arabicName, category, autoCorrectEnabled)
                        }
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

    pendingSpellConfirm?.let { suggestion ->
        AlertDialog(
            onDismissRequest = { pendingSpellConfirm = null },
            icon = {
                Icon(
                    Icons.Default.AutoFixHigh,
                    contentDescription = strings.didYouMean,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("${strings.didYouMean} ${suggestion.nameEn}?") },
            text = {
                Text(
                    "You typed \"${name.trim()}\". Would you like to save it as \"${suggestion.nameEn}\"${if (suggestion.nameAr.isNotBlank()) " (${suggestion.nameAr})" else ""} so your stock stays unified?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingSpellConfirm = null
                        submitItem(
                            finalName = suggestion.nameEn,
                            finalAr = arabicName.ifBlank { suggestion.nameAr },
                            finalCat = suggestion.category,
                            allowAutoCorrect = true
                        )
                    }
                ) {
                    Text("Use ${suggestion.nameEn}")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSpellConfirm = null
                        submitItem(
                            finalName = name,
                            finalAr = arabicName,
                            finalCat = category,
                            allowAutoCorrect = false
                        )
                    }
                ) {
                    Text("Keep \"${name.trim()}\"")
                }
            }
        )
    }
}

@Composable
fun LogWasteDialog(
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
