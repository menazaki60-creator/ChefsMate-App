package com.example.data

import android.content.Context
import com.example.model.IngredientCategory
import com.example.model.RecipeLineSource
import com.example.model.UnitType
import org.json.JSONObject

data class BundledRawItemSpec(
    val canonicalKey: String,
    val name: String,
    val category: IngredientCategory,
    val unit: UnitType
)

data class BundledIngredientLineSpec(
    val name: String,
    val qty: Double,
    val unit: UnitType,
    val source: RecipeLineSource
)

data class BundledSubRecipeSpec(
    val name: String,
    val posSku: String,
    val yieldQty: Double,
    val yieldUnit: UnitType,
    val ingredients: List<BundledIngredientLineSpec>
)

data class BundledMenuDishSpec(
    val name: String,
    val posSku: String,
    val category: String,
    val ingredients: List<BundledIngredientLineSpec>
)

data class BundledRecipeBook(
    val bookName: String,
    val source: String,
    val inventoryItems: List<BundledRawItemSpec>,
    val subRecipes: List<BundledSubRecipeSpec>,
    val menuDishes: List<BundledMenuDishSpec>
)

data class BundledBookSummary(
    val bookName: String,
    val dishCount: Int,
    val subRecipeCount: Int,
    val rawIngredientCount: Int
)

data class BundledBookImportResult(
    val createdInventory: Int,
    val createdSubRecipes: Int,
    val createdMenuDishes: Int,
    val skippedExisting: Int
) {
    val totalCreated: Int get() = createdInventory + createdSubRecipes + createdMenuDishes
}

/**
 * Loads the pre-parsed Barrio 67 Omega POS recipe book bundled in assets/barrio67_recipe_book.json
 * (187 Menu Dishes, 72 Production Sub-Recipes, and 265 raw POS ingredients with exact POS naming).
 */
object BarrioRecipeBookImporter {
    private const val ASSET_FILE = "barrio67_recipe_book.json"
    private const val AUTHORIZED_OWNER_EMAIL = "menazaki60@gmail.com"

    @Volatile
    private var cachedBook: BundledRecipeBook? = null

    /**
     * Restricts access to the private Barrio 67 recipe book strictly to the authorized
     * owner account while viewing their own kitchen workgroup (never other users or external groups).
     */
    fun isAuthorizedForWorkspace(userEmail: String?, isViewingJoinedWorkgroup: Boolean): Boolean {
        if (isViewingJoinedWorkgroup) return false
        return userEmail?.trim()?.equals(AUTHORIZED_OWNER_EMAIL, ignoreCase = true) == true
    }

    fun loadSummary(context: Context): BundledBookSummary {
        val book = loadBook(context)
        return BundledBookSummary(
            bookName = book.bookName,
            dishCount = book.menuDishes.size,
            subRecipeCount = book.subRecipes.size,
            rawIngredientCount = book.inventoryItems.size
        )
    }

    fun loadBook(context: Context): BundledRecipeBook {
        cachedBook?.let { return it }
        val rawJson = context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(rawJson)

        val bookName = root.optString("bookName", "Barrio 67 — Omega POS Recipe Book")
        val source = root.optString("source", "Omega POS")

        val invArray = root.getJSONArray("inventoryItems")
        val inventoryItems = buildList(invArray.length()) {
            for (i in 0 until invArray.length()) {
                val obj = invArray.getJSONObject(i)
                add(
                    BundledRawItemSpec(
                        canonicalKey = obj.getString("canonicalKey"),
                        name = obj.getString("name"),
                        category = IngredientCategory.fromRaw(obj.optString("category", "OTHER")),
                        unit = UnitType.fromSymbol(obj.optString("unit", "kg"))
                    )
                )
            }
        }

        val subArray = root.getJSONArray("subRecipes")
        val subRecipes = buildList(subArray.length()) {
            for (i in 0 until subArray.length()) {
                val obj = subArray.getJSONObject(i)
                add(
                    BundledSubRecipeSpec(
                        name = obj.getString("name"),
                        posSku = obj.optString("posSku", obj.getString("name")).take(64),
                        yieldQty = obj.optDouble("yieldQty", 1.0).coerceAtLeast(0.001),
                        yieldUnit = UnitType.fromSymbol(obj.optString("yieldUnit", "kg")),
                        ingredients = parseIngredientArray(obj.getJSONArray("ingredients"))
                    )
                )
            }
        }

        val dishArray = root.getJSONArray("menuDishes")
        val menuDishes = buildList(dishArray.length()) {
            for (i in 0 until dishArray.length()) {
                val obj = dishArray.getJSONObject(i)
                add(
                    BundledMenuDishSpec(
                        name = obj.getString("name"),
                        posSku = obj.optString("posSku", obj.getString("name")).take(64),
                        category = obj.optString("category", "").take(50),
                        ingredients = parseIngredientArray(obj.getJSONArray("ingredients"))
                    )
                )
            }
        }

        val book = BundledRecipeBook(
            bookName = bookName,
            source = source,
            inventoryItems = inventoryItems,
            subRecipes = subRecipes,
            menuDishes = menuDishes
        )
        cachedBook = book
        return book
    }

    private fun parseIngredientArray(arr: org.json.JSONArray): List<BundledIngredientLineSpec> =
        buildList(arr.length()) {
            for (j in 0 until arr.length()) {
                val obj = arr.getJSONObject(j)
                add(
                    BundledIngredientLineSpec(
                        name = obj.getString("name"),
                        qty = obj.optDouble("qty", 0.0).coerceAtLeast(0.0),
                        unit = UnitType.fromSymbol(obj.optString("unit", "g")),
                        source = RecipeLineSource.fromRaw(obj.optString("source", "INVENTORY"))
                    )
                )
            }
        }

    fun normalizePosKey(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), "")
}
