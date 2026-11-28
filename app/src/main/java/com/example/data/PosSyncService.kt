package com.example.data

import com.example.model.PosProvider
import com.example.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class PosFetchedSale(
    val codeOrPlu: String = "",
    val itemName: String = "",
    val quantitySold: Int = 0
) {
    val primaryKey: String get() = codeOrPlu.ifBlank { itemName }
}

/**
 * Real HTTP & CSV Z-Report parser for POS sales synchronization.
 * Supports Omega POS (Lebanon/MENA), BIM POS (Lebanon/MENA), Square, Toast, Clover, and Custom REST/CSV exports.
 */
class PosSyncService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchLivePosSales(
        endpointUrl: String,
        bearerToken: String,
        provider: PosProvider = PosProvider.CUSTOM
    ): Result<List<PosFetchedSale>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = endpointUrl.trim()
            require(trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                "POS Endpoint URL must start with https:// or http://"
            }
            val requestBuilder = Request.Builder()
                .url(trimmed)
                .get()
                .addHeader("Accept", "application/json, text/csv, text/plain")
            if (bearerToken.isNotBlank()) {
                val token = bearerToken.trim()
                requestBuilder.addHeader("Authorization", "Bearer $token")
                // Omega POS and BIM POS BackOffice APIs frequently authenticate via X-API-Key header
                if (provider == PosProvider.OMEGA_POS || provider == PosProvider.BIM_POS || provider == PosProvider.CUSTOM) {
                    requestBuilder.addHeader("X-API-Key", token)
                }
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("POS endpoint returned HTTP ${response.code}: ${response.message}")
                }
                val body = response.body?.string().orEmpty().trim()
                if (body.startsWith("{") || body.startsWith("[")) {
                    parseSalesJson(body)
                } else {
                    parseSalesCsvOrReport(body)
                }
            }
        }
    }

    /**
     * Parses Omega POS, BIM POS, Square, Toast, Clover, and generic JSON sales payloads,
     * including nested order/check item arrays and PLU / ItemCode / QtySold schemas.
     */
    fun parseSalesJson(rawJson: String): List<PosFetchedSale> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                extractFirstArray(
                    root,
                    listOf(
                        "sales", "Sales", "salesItems", "SalesItems", "itemSales", "ItemSales",
                        "items", "Items", "menuItems", "MenuItems", "orders", "Orders",
                        "checks", "Checks", "data", "Data", "reportData", "ReportData",
                        "results", "Results", "rows", "Rows"
                    )
                ) ?: JSONArray()
            }
        }

        val sales = mutableListOf<PosFetchedSale>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // If this row is a check/order containing nested line items (common in Omega/BIM/Square/Toast), flatten them
            val nested = extractFirstArray(
                obj,
                listOf("items", "Items", "orderItems", "OrderItems", "lineItems", "LineItems", "menuItems", "MenuItems", "checkItems", "CheckItems")
            )
            if (nested != null && nested.length() > 0) {
                for (j in 0 until nested.length()) {
                    nested.optJSONObject(j)?.let { lineObj ->
                        parseSingleJsonItem(lineObj)?.let { sales.add(it) }
                    }
                }
            } else {
                parseSingleJsonItem(obj)?.let { sales.add(it) }
            }
        }
        return aggregateSales(sales)
    }

    private fun extractFirstArray(obj: JSONObject, keys: List<String>): JSONArray? {
        for (key in keys) {
            val arr = obj.optJSONArray(key)
            if (arr != null) return arr
        }
        // Check one level nested under "data" or "result" object (common in Omega/BIM BackOffice JSON)
        for (wrapperKey in listOf("data", "Data", "result", "Result", "report", "Report")) {
            val inner = obj.optJSONObject(wrapperKey) ?: continue
            for (key in keys) {
                val arr = inner.optJSONArray(key)
                if (arr != null) return arr
            }
        }
        return null
    }

    private fun parseSingleJsonItem(obj: JSONObject): PosFetchedSale? {
        val code = firstNonBlank(
            obj,
            "plu", "PLU", "Plu", "itemCode", "ItemCode", "menuItemCode", "MenuItemCode",
            "productCode", "ProductCode", "sku", "SKU", "Sku", "code", "Code", "posSku"
        )
        val name = firstNonBlank(
            obj,
            "itemName", "ItemName", "menuItemName", "MenuItemName",
            "description", "Description", "itemDescription", "ItemDescription",
            "name", "Name", "nameEn", "nameAr", "dishName", "DishName"
        )
        val qty = firstPositiveInt(
            obj,
            "quantity", "Quantity", "qty", "Qty", "qtySold", "QtySold",
            "soldQty", "SoldQty", "count", "Count", "unitsSold", "UnitsSold", "salesQty", "SalesQty"
        )
        if ((code.isNotBlank() || name.isNotBlank()) && qty > 0) {
            return PosFetchedSale(codeOrPlu = code, itemName = name, quantitySold = qty)
        }
        return null
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val v = obj.optString(k, "").trim()
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun firstPositiveInt(obj: JSONObject, vararg keys: String): Int {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val d = obj.optDouble(k, Double.NaN)
            if (!d.isNaN() && d > 0.0) return d.roundToInt().coerceAtLeast(1)
            val strVal = obj.optString(k, "").trim().toDoubleOrNull()
            if (strVal != null && strVal > 0.0) return strVal.roundToInt().coerceAtLeast(1)
        }
        return 0
    }

    /**
     * Parses Omega POS & BIM POS BackOffice Z-Report CSV / TSV / semicolon-delimited Item Sales exports.
     * Supports headered exports (e.g. PLU,ItemName,QtySold,Total) or simple lines like:
     *   1001, Grilled Chicken Plate, 14
     *   Grilled Chicken Plate, 14
     */
    fun parseSalesCsvOrReport(rawText: String): List<PosFetchedSale> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        // Detect delimiter: comma, semicolon (common in regional Excel exports), or tab
        val firstLine = lines.first()
        val delimiter = when {
            firstLine.count { it == '\t' } >= 1 -> '\t'
            firstLine.count { it == ';' } >= firstLine.count { it == ',' } && firstLine.contains(';') -> ';'
            else -> ','
        }

        val firstCols = splitCsvLine(firstLine, delimiter).map { it.lowercase() }
        val hasHeader = firstCols.any { col ->
            col.contains("plu") || col.contains("code") || col.contains("sku") ||
                col.contains("item") || col.contains("desc") || col.contains("name") ||
                col.contains("qty") || col.contains("quant")
        }

        var codeIdx = -1
        var nameIdx = -1
        var qtyIdx = -1
        val startRow = if (hasHeader) {
            firstCols.forEachIndexed { idx, col ->
                when {
                    codeIdx < 0 && (col.contains("plu") || col.contains("code") || col.contains("sku")) -> codeIdx = idx
                    qtyIdx < 0 && (col.contains("qty") || col.contains("quant") || col.contains("sold") || col.contains("count")) -> qtyIdx = idx
                    nameIdx < 0 && (col.contains("name") || col.contains("item") || col.contains("desc") || col.contains("dish") || col.contains("menu")) -> nameIdx = idx
                }
            }
            1
        } else {
            0
        }

        val parsed = mutableListOf<PosFetchedSale>()
        for (i in startRow until lines.size) {
            val cols = splitCsvLine(lines[i], delimiter)
            if (cols.size < 2) continue

            if (hasHeader && qtyIdx in cols.indices) {
                val qty = parseQtyCell(cols[qtyIdx])
                if (qty <= 0) continue
                val code = if (codeIdx in cols.indices) cols[codeIdx] else ""
                val name = if (nameIdx in cols.indices) cols[nameIdx] else ""
                if (code.isNotBlank() || name.isNotBlank()) {
                    parsed.add(PosFetchedSale(codeOrPlu = code, itemName = name, quantitySold = qty))
                }
            } else {
                // Headerless fallback:
                // 3+ cols: PLU/Code, ItemName, Qty(, Amount)
                // 2 cols:  ItemName or PLU, Qty
                if (cols.size >= 3) {
                    val qty = parseQtyCell(cols[2]).takeIf { it > 0 } ?: parseQtyCell(cols[1])
                    if (qty > 0) {
                        val code = cols[0]
                        val name = if (parseQtyCell(cols[2]) > 0) cols[1] else ""
                        parsed.add(PosFetchedSale(codeOrPlu = code, itemName = name, quantitySold = qty))
                    }
                } else {
                    val qty = parseQtyCell(cols[1])
                    if (qty > 0) {
                        parsed.add(PosFetchedSale(codeOrPlu = cols[0], itemName = cols[0], quantitySold = qty))
                    }
                }
            }
        }
        return aggregateSales(parsed)
    }

    private fun parseQtyCell(raw: String): Int {
        val cleaned = raw.trim().replace(",", "").replace(" ", "")
        val d = cleaned.toDoubleOrNull() ?: return 0
        return if (d > 0.0) d.roundToInt().coerceAtLeast(1) else 0
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    private fun aggregateSales(raw: List<PosFetchedSale>): List<PosFetchedSale> {
        val grouped = linkedMapOf<String, PosFetchedSale>()
        for (sale in raw) {
            val key = "${sale.codeOrPlu.trim().lowercase()}|${sale.itemName.trim().lowercase()}"
            val prev = grouped[key]
            grouped[key] = if (prev == null) {
                sale
            } else {
                prev.copy(quantitySold = prev.quantitySold + sale.quantitySold)
            }
        }
        return grouped.values.toList()
    }

    fun matchSalesToRecipes(
        sales: List<PosFetchedSale>,
        recipes: List<Recipe>
    ): List<Pair<Recipe, Int>> {
        val totalsByRecipeId = linkedMapOf<String, Pair<Recipe, Int>>()
        for (sale in sales) {
            val normCode = sale.codeOrPlu.trim().lowercase()
            val normName = sale.itemName.trim().lowercase()
            val recipe = recipes.firstOrNull { r ->
                val sku = r.posSku?.trim()?.lowercase().orEmpty()
                val en = r.nameEn.trim().lowercase()
                val ar = r.nameAr.trim().lowercase()
                (normCode.isNotBlank() && sku.isNotEmpty() && sku == normCode) ||
                    (normName.isNotBlank() && (en == normName || (ar.isNotEmpty() && ar == normName))) ||
                    (normCode.isNotBlank() && (en == normCode || (ar.isNotEmpty() && ar == normCode)))
            }
            if (recipe != null && sale.quantitySold > 0) {
                val prev = totalsByRecipeId[recipe.id]
                totalsByRecipeId[recipe.id] = recipe to ((prev?.second ?: 0) + sale.quantitySold)
            }
        }
        return totalsByRecipeId.values.toList()
    }
}
