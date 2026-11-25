package com.example.data

import com.example.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PosFetchedSale(
    val skuOrName: String,
    val quantitySold: Int
)

/**
 * Real HTTP client for syncing live POS sales payloads (Square, Toast, Clover, or custom webhook/REST endpoints).
 * Expected JSON format:
 *   { "sales": [ { "sku": "CHK-01", "name": "Grilled Chicken", "quantity": 12 } ] }
 *   or top-level array [ { "sku": "...", "name": "...", "quantity": 12 } ]
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
        bearerToken: String
    ): Result<List<PosFetchedSale>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = endpointUrl.trim()
            require(trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                "POS Endpoint URL must start with https:// or http://"
            }
            val requestBuilder = Request.Builder()
                .url(trimmed)
                .get()
                .addHeader("Accept", "application/json")
            if (bearerToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${bearerToken.trim()}")
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("POS endpoint returned HTTP ${response.code}: ${response.message}")
                }
                val body = response.body?.string().orEmpty()
                parseSalesJson(body)
            }
        }
    }

    private fun parseSalesJson(rawJson: String): List<PosFetchedSale> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("sales")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("orders")
                    ?: JSONArray()
            }
        }
        val sales = mutableListOf<PosFetchedSale>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("sku", "").trim()
                .ifBlank { obj.optString("name", "").trim() }
                .ifBlank { obj.optString("itemName", "").trim() }
            val qty = obj.optInt("quantity", obj.optInt("count", 0))
            if (key.isNotBlank() && qty > 0) {
                sales.add(PosFetchedSale(skuOrName = key, quantitySold = qty))
            }
        }
        return sales
    }

    fun matchSalesToRecipes(
        sales: List<PosFetchedSale>,
        recipes: List<Recipe>
    ): List<Pair<Recipe, Int>> {
        val matched = mutableListOf<Pair<Recipe, Int>>()
        for (sale in sales) {
            val normKey = sale.skuOrName.trim().lowercase()
            val recipe = recipes.firstOrNull { r ->
                (!r.posSku.isNullOrBlank() && r.posSku.trim().lowercase() == normKey) ||
                    r.nameEn.trim().lowercase() == normKey ||
                    (r.nameAr.isNotBlank() && r.nameAr.trim().lowercase() == normKey)
            }
            if (recipe != null) {
                matched.add(recipe to sale.quantitySold)
            }
        }
        return matched
    }
}
