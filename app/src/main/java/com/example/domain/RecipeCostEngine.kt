package com.example.domain

import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
import com.example.model.RecipeLineSource
import com.example.model.RecipeType
import com.example.model.UnitType
import kotlin.math.max

enum class MarginHealth {
    HEALTHY,
    TIGHT,
    UNDERPRICED
}

data class RecipeLineCostBreakdown(
    val line: RecipeIngredientLine,
    val matchedItem: InventoryItem?,
    val matchedSubRecipe: Recipe?,
    val rawLineCost: Double,
    val wasteAdjustedLineCost: Double,
    val shareOfPlatePercent: Double,
    val isLowStock: Boolean,
    val isSubRecipe: Boolean
)

data class SubRecipeCostSummary(
    val totalRawBatchCost: Double,
    val totalWasteAdjustedBatchCost: Double,
    val yieldQuantity: Double,
    val yieldUnit: UnitType,
    val costPerYieldUnit: Double,
    val costPer100SmallUnit: Double?,
    val smallUnitLabel: String?,
    val usedInDishCount: Int,
    val lineBreakdowns: List<RecipeLineCostBreakdown>
)

data class PlateProfitabilityReport(
    val servings: Int,
    val lineBreakdowns: List<RecipeLineCostBreakdown>,
    val totalRawBatchCost: Double,
    val totalWasteAdjustedBatchCost: Double,
    val rawCostPerPlate: Double,
    val wasteAdjustedCostPerPlate: Double,
    val wasteImpactPerPlate: Double,
    val desiredProfitMarginPercent: Double,
    val targetFoodCostPercent: Double,
    val minimumSellingPrice: Double,
    val currentMenuPrice: Double,
    val actualProfitPerPlate: Double,
    val actualProfitMarginPercent: Double,
    val actualFoodCostPercent: Double,
    val marginHealth: MarginHealth
)

/**
 * Pure domain calculation engine for Menu Dishes and Production Sub-Recipes (sauces, stocks, prep batches).
 * Pulls live waste-adjusted net unit pricing from Inventory AND recursively rolls up batch costs
 * from Sub-Recipes used as ingredients inside other recipes (with cycle detection).
 */
object RecipeCostEngine {

    /**
     * Checks whether adding [candidateSubRecipeId] inside [parentRecipeId] would create a circular dependency.
     */
    fun wouldCreateCycle(
        parentRecipeId: String?,
        candidateSubRecipeId: String,
        allRecipes: List<Recipe>
    ): Boolean {
        if (parentRecipeId.isNullOrBlank()) return false
        if (parentRecipeId == candidateSubRecipeId) return true
        val recipesById = allRecipes.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.add(candidateSubRecipeId)
        while (stack.isNotEmpty()) {
            val currentId = stack.removeFirst()
            if (currentId == parentRecipeId) return true
            if (!visited.add(currentId)) continue
            val rec = recipesById[currentId] ?: continue
            rec.parsedLines()
                .filter { it.sourceEnum == RecipeLineSource.SUB_RECIPE }
                .forEach { stack.add(it.itemId) }
        }
        return false
    }

    /**
     * Computes raw and waste-adjusted total batch costs for any recipe or sub-recipe,
     * recursively resolving nested sub-recipes safely with [visitedIds] cycle protection.
     */
    private fun computeBatchCosts(
        lines: List<RecipeIngredientLine>,
        inventoryById: Map<String, InventoryItem>,
        recipesById: Map<String, Recipe>,
        visitedIds: Set<String>
    ): Pair<Double, Double> {
        var totalRaw = 0.0
        var totalNet = 0.0
        for (line in lines) {
            val (raw, net) = computeSingleLineCost(line, inventoryById, recipesById, visitedIds)
            totalRaw += raw
            totalNet += net
        }
        return totalRaw to totalNet
    }

    private fun computeSingleLineCost(
        line: RecipeIngredientLine,
        inventoryById: Map<String, InventoryItem>,
        recipesById: Map<String, Recipe>,
        visitedIds: Set<String>
    ): Pair<Double, Double> {
        return if (line.sourceEnum == RecipeLineSource.SUB_RECIPE) {
            val sub = recipesById[line.itemId] ?: return 0.0 to 0.0
            if (sub.id in visitedIds) return 0.0 to 0.0
            val (subRawBatch, subNetBatch) = computeBatchCosts(
                lines = sub.parsedLines(),
                inventoryById = inventoryById,
                recipesById = recipesById,
                visitedIds = visitedIds + sub.id
            )
            val safeYield = max(0.0001, sub.yieldQuantity)
            val usedInSubUnit = UnitType.convert(
                quantity = line.quantity.coerceAtLeast(0.0),
                fromUnit = UnitType.fromSymbol(line.unit),
                toUnit = sub.yieldUnitEnum
            )
            val fraction = usedInSubUnit / safeYield
            (subRawBatch * fraction) to (subNetBatch * fraction)
        } else {
            val item = inventoryById[line.itemId] ?: return 0.0 to 0.0
            val qtyInInvUnit = UnitType.convert(
                quantity = line.quantity.coerceAtLeast(0.0),
                fromUnit = UnitType.fromSymbol(line.unit),
                toUnit = item.unitEnum
            )
            (qtyInInvUnit * item.unitCost) to (qtyInInvUnit * item.effectiveNetUnitCost)
        }
    }

    /**
     * Calculates complete plate costing and suggested minimum selling price for [lines],
     * resolving both raw inventory items and nested production sub-recipes (sauces/prep).
     */
    fun calculate(
        lines: List<RecipeIngredientLine>,
        servings: Int,
        menuPrice: Double,
        desiredProfitMarginPercent: Double,
        inventory: List<InventoryItem>,
        allRecipes: List<Recipe> = emptyList(),
        currentRecipeId: String? = null
    ): PlateProfitabilityReport {
        val safeServings = max(1, servings)
        val clampedMargin = desiredProfitMarginPercent.coerceIn(5.0, 95.0)
        val targetFoodCostPct = (100.0 - clampedMargin).coerceIn(5.0, 95.0)
        val inventoryById = inventory.associateBy { it.id }
        val recipesById = allRecipes.associateBy { it.id }
        val initialVisited = if (currentRecipeId.isNullOrBlank()) emptySet() else setOf(currentRecipeId)

        val evaluatedLines = lines.map { line ->
            val isSub = line.sourceEnum == RecipeLineSource.SUB_RECIPE
            val invItem = if (!isSub) inventoryById[line.itemId] else null
            val subRec = if (isSub) recipesById[line.itemId] else null
            val costs = computeSingleLineCost(line, inventoryById, recipesById, initialVisited)
            Triple(line, invItem to subRec, costs)
        }

        val totalRawBatch = evaluatedLines.sumOf { it.third.first }
        val totalWasteAdjustedBatch = evaluatedLines.sumOf { it.third.second }

        val breakdowns = evaluatedLines.map { (line, refs, costs) ->
            val (invItem, subRec) = refs
            val (rawLineCost, netLineCost) = costs
            val share = if (totalWasteAdjustedBatch > 0.0001) {
                (netLineCost / totalWasteAdjustedBatch) * 100.0
            } else 0.0
            RecipeLineCostBreakdown(
                line = line,
                matchedItem = invItem,
                matchedSubRecipe = subRec,
                rawLineCost = rawLineCost,
                wasteAdjustedLineCost = netLineCost,
                shareOfPlatePercent = share,
                isLowStock = invItem?.lowStock == true,
                isSubRecipe = line.sourceEnum == RecipeLineSource.SUB_RECIPE
            )
        }

        val rawPerPlate = totalRawBatch / safeServings
        val netPerPlate = totalWasteAdjustedBatch / safeServings
        val wasteImpact = max(0.0, netPerPlate - rawPerPlate)

        val foodCostRatio = (1.0 - (clampedMargin / 100.0)).coerceAtLeast(0.05)
        val minSellingPrice = netPerPlate / foodCostRatio

        val safeMenuPrice = menuPrice.coerceAtLeast(0.0)
        val actualProfit = safeMenuPrice - netPerPlate
        val actualMarginPct = if (safeMenuPrice > 0.0001) {
            (actualProfit / safeMenuPrice) * 100.0
        } else 0.0
        val actualFoodCostPct = if (safeMenuPrice > 0.0001) {
            (netPerPlate / safeMenuPrice) * 100.0
        } else 0.0

        val health = when {
            safeMenuPrice <= 0.0001 -> MarginHealth.UNDERPRICED
            actualMarginPct >= clampedMargin - 0.5 -> MarginHealth.HEALTHY
            actualMarginPct >= clampedMargin - 6.0 -> MarginHealth.TIGHT
            else -> MarginHealth.UNDERPRICED
        }

        return PlateProfitabilityReport(
            servings = safeServings,
            lineBreakdowns = breakdowns,
            totalRawBatchCost = totalRawBatch,
            totalWasteAdjustedBatchCost = totalWasteAdjustedBatch,
            rawCostPerPlate = rawPerPlate,
            wasteAdjustedCostPerPlate = netPerPlate,
            wasteImpactPerPlate = wasteImpact,
            desiredProfitMarginPercent = clampedMargin,
            targetFoodCostPercent = targetFoodCostPct,
            minimumSellingPrice = minSellingPrice,
            currentMenuPrice = safeMenuPrice,
            actualProfitPerPlate = actualProfit,
            actualProfitMarginPercent = actualMarginPct,
            actualFoodCostPercent = actualFoodCostPct,
            marginHealth = health
        )
    }

    fun evaluateRecipe(
        recipe: Recipe,
        inventory: List<InventoryItem>,
        allRecipes: List<Recipe> = emptyList()
    ): PlateProfitabilityReport {
        val desiredMargin = (100.0 - recipe.targetFoodCostPct).coerceIn(5.0, 95.0)
        return calculate(
            lines = recipe.parsedLines(),
            servings = recipe.servings,
            menuPrice = recipe.menuPrice,
            desiredProfitMarginPercent = desiredMargin,
            inventory = inventory,
            allRecipes = allRecipes,
            currentRecipeId = recipe.id
        )
    }

    /**
     * Evaluates a non-selling production Sub-Recipe (e.g. Garlic Sauce yielding 1000 mL)
     * to provide batch cost, cost per yield unit, and cost per 100g/100mL for plating reference.
     */
    fun evaluateSubRecipe(
        lines: List<RecipeIngredientLine>,
        yieldQuantity: Double,
        yieldUnit: UnitType,
        inventory: List<InventoryItem>,
        allRecipes: List<Recipe>,
        currentSubRecipeId: String? = null
    ): SubRecipeCostSummary {
        val safeYield = max(0.001, yieldQuantity)
        val report = calculate(
            lines = lines,
            servings = 1,
            menuPrice = 0.0,
            desiredProfitMarginPercent = 70.0,
            inventory = inventory,
            allRecipes = allRecipes,
            currentRecipeId = currentSubRecipeId
        )
        val costPerUnit = report.totalWasteAdjustedBatchCost / safeYield

        val (costPer100, smallLabel) = when (yieldUnit) {
            UnitType.KG -> (costPerUnit / 10.0) to "100 g"
            UnitType.G -> (costPerUnit * 100.0) to "100 g"
            UnitType.L -> (costPerUnit / 10.0) to "100 mL"
            UnitType.ML -> (costPerUnit * 100.0) to "100 mL"
            UnitType.PCS -> null to null
        }

        val usedCount = if (currentSubRecipeId.isNullOrBlank()) {
            0
        } else {
            allRecipes.count { rec ->
                rec.id != currentSubRecipeId &&
                    rec.parsedLines().any {
                        it.sourceEnum == RecipeLineSource.SUB_RECIPE && it.itemId == currentSubRecipeId
                    }
            }
        }

        return SubRecipeCostSummary(
            totalRawBatchCost = report.totalRawBatchCost,
            totalWasteAdjustedBatchCost = report.totalWasteAdjustedBatchCost,
            yieldQuantity = safeYield,
            yieldUnit = yieldUnit,
            costPerYieldUnit = costPerUnit,
            costPer100SmallUnit = costPer100,
            smallUnitLabel = smallLabel,
            usedInDishCount = usedCount,
            lineBreakdowns = report.lineBreakdowns
        )
    }

    fun evaluateSubRecipe(
        subRecipe: Recipe,
        inventory: List<InventoryItem>,
        allRecipes: List<Recipe>
    ): SubRecipeCostSummary = evaluateSubRecipe(
        lines = subRecipe.parsedLines(),
        yieldQuantity = subRecipe.yieldQuantity,
        yieldUnit = subRecipe.yieldUnitEnum,
        inventory = inventory,
        allRecipes = allRecipes,
        currentSubRecipeId = subRecipe.id
    )

    /**
     * Recursively expands a dish recipe (including any nested sub-recipe sauces/prep)
     * into raw InventoryItem consumption amounts (in each InventoryItem's native unit) for POS sales deduction.
     */
    fun flattenRawInventoryUsage(
        recipe: Recipe,
        multiplier: Double,
        inventoryById: Map<String, InventoryItem>,
        recipesById: Map<String, Recipe>,
        output: MutableMap<String, Double>,
        visitedIds: Set<String> = emptySet()
    ) {
        if (recipe.id.isNotBlank() && recipe.id in visitedIds) return
        val nextVisited = if (recipe.id.isNotBlank()) visitedIds + recipe.id else visitedIds

        for (line in recipe.parsedLines()) {
            if (line.sourceEnum == RecipeLineSource.SUB_RECIPE) {
                val sub = recipesById[line.itemId] ?: continue
                val usedInSubUnit = UnitType.convert(
                    quantity = line.quantity * multiplier,
                    fromUnit = UnitType.fromSymbol(line.unit),
                    toUnit = sub.yieldUnitEnum
                )
                val subBatchFraction = usedInSubUnit / max(0.0001, sub.yieldQuantity)
                flattenRawInventoryUsage(
                    recipe = sub,
                    multiplier = subBatchFraction,
                    inventoryById = inventoryById,
                    recipesById = recipesById,
                    output = output,
                    visitedIds = nextVisited
                )
            } else {
                val invItem = inventoryById[line.itemId] ?: continue
                val qtyInInvUnit = UnitType.convert(
                    quantity = line.quantity * multiplier,
                    fromUnit = UnitType.fromSymbol(line.unit),
                    toUnit = invItem.unitEnum
                )
                output[invItem.id] = (output[invItem.id] ?: 0.0) + qtyInInvUnit
            }
        }
    }

    /** Formats a sub-recipe's unit cost compactly for ingredient picker chips. */
    fun formatSubRecipeRateLabel(summary: SubRecipeCostSummary, currency: String): String {
        return when {
            summary.yieldUnit == UnitType.ML && summary.costPer100SmallUnit != null ->
                String.format(java.util.Locale.US, "%s%.2f/100mL", currency, summary.costPer100SmallUnit)
            summary.yieldUnit == UnitType.G && summary.costPer100SmallUnit != null ->
                String.format(java.util.Locale.US, "%s%.2f/100g", currency, summary.costPer100SmallUnit)
            else ->
                String.format(java.util.Locale.US, "%s%.2f/%s", currency, summary.costPerYieldUnit, summary.yieldUnit.symbol)
        }
    }
}
