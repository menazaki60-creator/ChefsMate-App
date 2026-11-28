package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.BasicIngredientSpec
import com.example.data.BilingualIngredientMatcher
import com.example.i18n.AppStrings
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.ui.theme.CairoArabicFamily

@Composable
fun BasicIngredientsCatalogDialog(
    existingInventory: List<InventoryItem>,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onAddSelected: (List<BasicIngredientSpec>) -> Unit,
    onQuickFillSingle: (BasicIngredientSpec) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<IngredientCategory?>(null) }
    val selectedKeys = remember { mutableStateListOf<String>() }

    val existingKeys = remember(existingInventory) {
        existingInventory.flatMap {
            listOf(
                BilingualIngredientMatcher.normalize(it.canonicalKey),
                BilingualIngredientMatcher.normalize(it.nameEn)
            )
        }.toSet()
    }

    val typoSuggestion = remember(query, existingInventory) {
        BilingualIngredientMatcher.bestTypoSuggestion(query, existingInventory)
    }

    val filteredSpecs = remember(query, selectedCategory) {
        val q = BilingualIngredientMatcher.normalize(query)
        BilingualIngredientMatcher.basicIngredients.filter { spec ->
            val catOk = selectedCategory == null || spec.category == selectedCategory
            if (!catOk) return@filter false
            if (q.isEmpty()) return@filter true
            val matchDirect = BilingualIngredientMatcher.normalize(spec.nameEn).contains(q) ||
                BilingualIngredientMatcher.normalize(spec.nameAr).contains(q) ||
                spec.synonyms.any { BilingualIngredientMatcher.normalize(it).contains(q) }
            if (matchDirect) return@filter true
            // Fuzzy typo tolerance in catalog search (e.g. "galric" shows Garlic!)
            q.length >= 3 && (
                BilingualIngredientMatcher.damerauLevenshtein(q, BilingualIngredientMatcher.normalize(spec.nameEn)) <= 2 ||
                    spec.synonyms.any { syn ->
                        val ns = BilingualIngredientMatcher.normalize(syn)
                        ns.length >= 3 && BilingualIngredientMatcher.damerauLevenshtein(q, ns) <= 1
                    }
                )
        }
    }

    val unaddedVisible = filteredSpecs.filter { spec ->
        BilingualIngredientMatcher.normalize(spec.key) !in existingKeys &&
            BilingualIngredientMatcher.normalize(spec.nameEn) !in existingKeys
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(strings.basicIngredients)
                Text(
                    text = "${BilingualIngredientMatcher.basicIngredients.size} kitchen staples • EN / العربية",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search e.g. Garlic, ثوم, Chicken...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search basic ingredients") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("basic_catalog_search")
                )

                if (typoSuggestion != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { query = typoSuggestion.nameEn }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = strings.didYouMean,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${strings.didYouMean} ${typoSuggestion.nameEn} (${typoSuggestion.nameAr})?",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                // Category chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text(strings.allCategories) }
                    )
                    IngredientCategory.entries.filter { it != IngredientCategory.OTHER }.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            label = { Text(cat.name.replace('_', ' ')) }
                        )
                    }
                }

                // Quick Select Visible / Clear buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            unaddedVisible.forEach { spec ->
                                if (spec.key !in selectedKeys) selectedKeys.add(spec.key)
                            }
                        },
                        enabled = unaddedVisible.isNotEmpty()
                    ) {
                        Text("Select All Visible (${unaddedVisible.size})")
                    }
                    if (selectedKeys.isNotEmpty()) {
                        TextButton(onClick = { selectedKeys.clear() }) {
                            Text("Clear (${selectedKeys.size})")
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredSpecs, key = { it.key }) { spec ->
                        val alreadyInStock = BilingualIngredientMatcher.normalize(spec.key) in existingKeys ||
                            BilingualIngredientMatcher.normalize(spec.nameEn) in existingKeys
                        val checked = spec.key in selectedKeys

                        Surface(
                            color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(10.dp),
                            border = if (checked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !alreadyInStock) {
                                    if (checked) selectedKeys.remove(spec.key) else selectedKeys.add(spec.key)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (alreadyInStock) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Already in stock",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier
                                            .padding(horizontal = 10.dp)
                                            .size(20.dp)
                                    )
                                } else {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                if (spec.key !in selectedKeys) selectedKeys.add(spec.key)
                                            } else {
                                                selectedKeys.remove(spec.key)
                                            }
                                        }
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = spec.nameEn,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = spec.nameAr,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CairoArabicFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        text = "${spec.category.name.replace('_', ' ')} • ${spec.defaultUnit.symbol}${if (alreadyInStock) " • In Stock" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = { onQuickFillSingle(spec) },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = if (alreadyInStock) "+Stock" else "Price",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = BilingualIngredientMatcher.basicIngredients.filter { it.key in selectedKeys }
                    if (chosen.isNotEmpty()) {
                        onAddSelected(chosen)
                    }
                },
                enabled = selectedKeys.isNotEmpty(),
                modifier = Modifier.testTag("confirm_add_basic_ingredients")
            ) {
                Text("Add Selected (${selectedKeys.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
