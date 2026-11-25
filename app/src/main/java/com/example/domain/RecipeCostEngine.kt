package com.example.domain

import com.example.model.InventoryItem
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
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
    val rawLineCost: Double,
    val wasteAdjustedLineCost: Double,
    val shareOfPlatePercent: Double,
    val isLowStock: Boolean
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
 * Pure domain calculation engine for the Recipe Cost Calculator.
 * Pulls live unit pricing and waste-adjusted net unit costs directly from inventory records,
 * converts ingredient units automatically, and computes per-plate cost + minimum selling price
 * for any desired profit margin percentage.
 */
object RecipeCostEngine {

    /**
     * Calculates complete plate costing and suggested minimum selling price for [lines].
     *
     * Formula:
     *   - Effective Net Unit Cost = Inventory.totalCost / max(0, grossQuantity - wasteQuantity)
     *   - Line Cost = convert(line.quantity, line.unit -> inventory.unit) * effectiveNetUnitCost
     *   - Cost Per Plate = sum(Line Costs) / servings
     *   - Minimum Selling Price = Cost Per Plate / (1 - desiredMarginPercent / 100)
     */
    fun calculate(
        lines: List<RecipeIngredientLine>,
        servings: Int,
        menuPrice: Double,
        desiredProfitMarginPercent: Double,
        inventory: List<InventoryItem>
    ): PlateProfitabilityReport {
        val safeServings = max(1, servings)
        val clampedMargin = desiredProfitMarginPercent.coerceIn(5.0, 95.0)
        val targetFoodCostPct = (100.0 - clampedMargin).coerceIn(5.0, 95.0)
        val inventoryById = inventory.associateBy { it.id }

        val rawLines = lines.map { line ->
            val item = inventoryById[line.itemId]
            if (item == null) {
                Triple(line, null as InventoryItem?, 0.0 to 0.0)
            } else {
                val qtyInInvUnit = UnitType.convert(
                    quantity = line.quantity.coerceAtLeast(0.0),
                    fromUnit = UnitType.fromSymbol(line.unit),
                    toUnit = item.unitEnum
                )
                val rawCost = qtyInInvUnit * item.unitCost
                val netCost = qtyInInvUnit * item.effectiveNetUnitCost
                Triple(line, item, rawCost to netCost)
            }
        }

        val totalRawBatch = rawLines.sumOf { it.third.first }
        val totalWasteAdjustedBatch = rawLines.sumOf { it.third.second }

        val breakdowns = rawLines.map { (line, item, costs) ->
            val (rawLineCost, netLineCost) = costs
            val share = if (totalWasteAdjustedBatch > 0.0001) {
                (netLineCost / totalWasteAdjustedBatch) * 100.0
            } else 0.0
            RecipeLineCostBreakdown(
                line = line,
                matchedItem = item,
                rawLineCost = rawLineCost,
                wasteAdjustedLineCost = netLineCost,
                shareOfPlatePercent = share,
                isLowStock = item?.lowStock == true
            )
        }

        val rawPerPlate = totalRawBatch / safeServings
        val netPerPlate = totalWasteAdjustedBatch / safeServings
        val wasteImpact = max(0.0, netPerPlate - rawPerPlate)

        // Minimum selling price to achieve desired profit margin
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

    fun evaluateRecipe(recipe: Recipe, inventory: List<InventoryItem>): PlateProfitabilityReport {
        val desiredMargin = (100.0 - recipe.targetFoodCostPct).coerceIn(5.0, 95.0)
        return calculate(
            lines = recipe.parsedLines(),
            servings = recipe.servings,
            menuPrice = recipe.menuPrice,
            desiredProfitMarginPercent = desiredMargin,
            inventory = inventory
        )
    }
}
