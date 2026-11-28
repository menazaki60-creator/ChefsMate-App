package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.max

enum class AppLanguage(val code: String, val displayLabel: String, val isRtl: Boolean) {
    EN("en", "English", false),
    AR("ar", "العربية", true),
    ES("es", "Español", false),
    FR("fr", "Français", false);

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
    }
}

enum class IngredientCategory(val id: String) {
    PROTEIN("PROTEIN"),
    PRODUCE("PRODUCE"),
    DAIRY("DAIRY"),
    DRY_GOODS("DRY_GOODS"),
    SPICES("SPICES"),
    BEVERAGES("BEVERAGES"),
    OTHER("OTHER");

    companion object {
        fun fromRaw(raw: String?): IngredientCategory =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

enum class UnitType(val symbol: String) {
    KG("kg"),
    G("g"),
    L("L"),
    ML("mL"),
    PCS("pcs");

    companion object {
        fun fromSymbol(raw: String?): UnitType {
            val cleaned = raw?.trim()?.lowercase() ?: return KG
            return when (cleaned) {
                "kg", "kilogram", "kilograms", "كجم", "كيلو", "كيلوجرام" -> KG
                "g", "gr", "gm", "gram", "grams", "جم", "جرام", "غ", "غرام" -> G
                "l", "lt", "liter", "liters", "litre", "لتر" -> L
                "ml", "milliliter", "milliliters", "مل", "مليلتر" -> ML
                "pcs", "pc", "piece", "pieces", "ea", "unit", "units", "box", "pack", "حبة", "قطعة", "علبة" -> PCS
                else -> entries.firstOrNull { it.symbol.equals(raw, ignoreCase = true) } ?: KG
            }
        }

        /** Converts a quantity from [fromUnit] into [toUnit] when compatible (mass <-> mass, volume <-> volume). */
        fun convert(quantity: Double, fromUnit: UnitType, toUnit: UnitType): Double {
            if (fromUnit == toUnit) return quantity
            return when {
                fromUnit == G && toUnit == KG -> quantity / 1000.0
                fromUnit == KG && toUnit == G -> quantity * 1000.0
                fromUnit == ML && toUnit == L -> quantity / 1000.0
                fromUnit == L && toUnit == ML -> quantity * 1000.0
                else -> quantity
            }
        }
    }
}

enum class StaffRole(val id: String) {
    MANAGER("MANAGER"),
    SOUS_CHEF("SOUS_CHEF"),
    LINE_COOK("LINE_COOK"),
    INVENTORY_CLERK("INVENTORY_CLERK");

    companion object {
        fun fromRaw(raw: String?): StaffRole =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: LINE_COOK
    }
}

enum class LedgerEntryType(val id: String) {
    PURCHASE("PURCHASE"),
    WASTE("WASTE"),
    USAGE("USAGE"),
    INVOICE_SCAN("INVOICE_SCAN"),
    POS_SALE("POS_SALE");

    companion object {
        fun fromRaw(raw: String?): LedgerEntryType =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: PURCHASE
    }
}

enum class FlagStatus(val id: String) {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    DISMISSED("DISMISSED");

    companion object {
        fun fromRaw(raw: String?): FlagStatus =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

enum class PosProvider(
    val id: String,
    val displayName: String,
    val sampleEndpoint: String,
    val setupHint: String
) {
    OMEGA_POS(
        id = "OMEGA_POS",
        displayName = "Omega POS",
        sampleEndpoint = "https://your-omega-backoffice/api/v1/sales/items",
        setupHint = "Lebanon & MENA: Connects to Omega Cloud/BackOffice REST API (Bearer or X-API-Key) or imports Omega Item Sales / PLU CSV Z-Report."
    ),
    BIM_POS(
        id = "BIM_POS",
        displayName = "BIM POS",
        sampleEndpoint = "https://your-bim-backoffice/api/v1/reports/item-sales",
        setupHint = "Lebanon & MENA: Connects to BIM POS BackOffice REST API or imports BIM POS Item Sales / PLU CSV Z-Report."
    ),
    SQUARE(
        id = "SQUARE",
        displayName = "Square POS",
        sampleEndpoint = "https://connect.squareup.com/v2/orders/search",
        setupHint = "Square POS REST endpoint or shift Z-Report deduction."
    ),
    TOAST(
        id = "TOAST",
        displayName = "Toast POS",
        sampleEndpoint = "https://ws-api.toasttab.com/orders/v2/orders",
        setupHint = "Toast POS REST endpoint or shift Z-Report deduction."
    ),
    CLOVER(
        id = "CLOVER",
        displayName = "Clover POS",
        sampleEndpoint = "https://api.clover.com/v3/merchants/{mId}/orders",
        setupHint = "Clover POS REST endpoint or shift Z-Report deduction."
    ),
    CUSTOM(
        id = "CUSTOM",
        displayName = "Custom REST / Webhook",
        sampleEndpoint = "https://api.yourpos.com/v1/sales/today",
        setupHint = "Any custom JSON POS endpoint or CSV/Z-Report sales export."
    ),
    NONE(
        id = "NONE",
        displayName = "Not Connected",
        sampleEndpoint = "",
        setupHint = "Select Omega POS, BIM POS, or another POS system to sync sales."
    );

    companion object {
        fun fromRaw(raw: String?): PosProvider =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: NONE
    }
}

enum class RecipeType(val id: String) {
    MENU_ITEM("MENU_ITEM"),
    SUB_RECIPE("SUB_RECIPE");

    companion object {
        fun fromRaw(raw: String?): RecipeType =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: MENU_ITEM
    }
}

enum class StockAdjustMode {
    ADD,
    DEDUCT,
    SET_EXACT
}

enum class RecipeLineSource(val id: String) {
    INVENTORY("INVENTORY"),
    SUB_RECIPE("SUB_RECIPE");

    companion object {
        fun fromRaw(raw: String?): RecipeLineSource =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: INVENTORY
    }
}

@Keep
@IgnoreExtraProperties
data class KitchenProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val kitchenName: String = "Main Kitchen",
    val role: String = StaffRole.MANAGER.id,
    val currencySymbol: String = "$",
    val targetFoodCostPct: Double = 30.0,
    val lowStockAlertsEnabled: Boolean = true,
    val posProvider: String = PosProvider.NONE.id,
    val posEndpointUrl: String? = null,
    val managerPinHash: String? = null,
    val logoBase64: String? = null,
    val groupCode: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "displayName" to displayName.trim().ifBlank { "Chef" }.take(80),
        "kitchenName" to kitchenName.trim().ifBlank { "Main Kitchen" }.take(80),
        "role" to StaffRole.MANAGER.id,
        "currencySymbol" to currencySymbol.trim().ifBlank { "$" }.take(5),
        "targetFoodCostPct" to targetFoodCostPct.coerceIn(5.0, 90.0),
        "lowStockAlertsEnabled" to lowStockAlertsEnabled,
        "posProvider" to PosProvider.fromRaw(posProvider).id,
        "posEndpointUrl" to posEndpointUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(300),
        "managerPinHash" to managerPinHash?.trim()?.takeIf { it.isNotEmpty() }?.take(128),
        "logoBase64" to logoBase64?.takeIf { it.isNotBlank() && it.length <= 220_000 },
        "groupCode" to groupCode?.trim()?.uppercase()?.takeIf { it.length in 4..20 },
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class InventoryItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val canonicalKey: String = "",
    val nameEn: String = "",
    val nameAr: String = "",
    val aliases: List<String> = emptyList(),
    val category: String = IngredientCategory.OTHER.id,
    val unit: String = UnitType.KG.symbol,
    val grossQuantity: Double = 0.0,
    val wasteQuantity: Double = 0.0,
    val consumedQuantity: Double = 0.0,
    val totalCost: Double = 0.0,
    val unitCost: Double = 0.0,
    val minStockLevel: Double = 0.5,
    val supplierName: String? = null,
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val unitEnum: UnitType get() = UnitType.fromSymbol(unit)
    val categoryEnum: IngredientCategory get() = IngredientCategory.fromRaw(category)

    /** Net usable quantity after trim/spoilage waste: e.g. 1.0 kg - 0.1 kg = 0.9 kg (900 g). */
    val netUsableQuantity: Double get() = max(0.0, grossQuantity - wasteQuantity)

    /** Remaining live inventory on hand after waste and prep/POS sales consumption. */
    val currentStock: Double get() = max(0.0, grossQuantity - wasteQuantity - consumedQuantity)

    /** Percentage of gross stock lost to waste. */
    val wastePercent: Double get() = if (grossQuantity > 0.0) (wasteQuantity / grossQuantity) * 100.0 else 0.0

    /** Percentage of gross stock available as net usable yield. */
    val netYieldPercent: Double get() = if (grossQuantity > 0.0) (netUsableQuantity / grossQuantity) * 100.0 else 100.0

    /** Monetary value lost to waste. */
    val wasteCost: Double get() = wasteQuantity * unitCost

    /** True effective cost per usable unit after accounting for waste. */
    val effectiveNetUnitCost: Double
        get() = when {
            netUsableQuantity > 0.0001 -> totalCost / netUsableQuantity
            else -> unitCost
        }

    val lowStock: Boolean get() = currentStock <= minStockLevel

    fun displayTitle(language: AppLanguage): String = when (language) {
        AppLanguage.AR -> nameAr.ifBlank { nameEn }
        else -> nameEn.ifBlank { nameAr }
    }

    fun secondaryTitle(language: AppLanguage): String = when (language) {
        AppLanguage.AR -> if (nameAr.isNotBlank() && nameEn.isNotBlank()) nameEn else ""
        else -> if (nameAr.isNotBlank()) nameAr else ""
    }

    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "canonicalKey" to canonicalKey.trim().ifBlank { nameEn.lowercase().trim() }.take(80),
        "nameEn" to nameEn.trim().ifBlank { "Ingredient" }.take(100),
        "nameAr" to nameAr.trim().take(100),
        "aliases" to aliases.map { it.trim().take(80) }.filter { it.isNotEmpty() }.distinct().take(20),
        "category" to IngredientCategory.fromRaw(category).id,
        "unit" to UnitType.fromSymbol(unit).symbol,
        "grossQuantity" to grossQuantity.coerceIn(0.0, 1_000_000.0),
        "wasteQuantity" to wasteQuantity.coerceIn(0.0, 1_000_000.0),
        "consumedQuantity" to consumedQuantity.coerceIn(0.0, 1_000_000.0),
        "totalCost" to totalCost.coerceIn(0.0, 10_000_000.0),
        "unitCost" to unitCost.coerceIn(0.0, 1_000_000.0),
        "minStockLevel" to minStockLevel.coerceIn(0.0, 1_000_000.0),
        "supplierName" to supplierName?.trim()?.takeIf { it.isNotEmpty() }?.take(100),
        "notes" to notes?.trim()?.takeIf { it.isNotEmpty() }?.take(300),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class LedgerEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val itemId: String = "",
    val itemNameEn: String = "",
    val itemNameAr: String = "",
    val rawScannedLabel: String = "",
    val entryType: String = LedgerEntryType.PURCHASE.id,
    val category: String = IngredientCategory.OTHER.id,
    val unit: String = UnitType.KG.symbol,
    val grossDelta: Double = 0.0,
    val wasteDelta: Double = 0.0,
    val costAmount: Double = 0.0,
    val wasteCostAmount: Double = 0.0,
    val loggedByRole: String = StaffRole.MANAGER.id,
    val hourOfDay: Int = 12,
    val reason: String? = null,
    val staffName: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "itemId" to itemId.take(128),
        "itemNameEn" to itemNameEn.trim().ifBlank { "Item" }.take(100),
        "itemNameAr" to itemNameAr.trim().take(100),
        "rawScannedLabel" to rawScannedLabel.trim().take(120),
        "entryType" to LedgerEntryType.fromRaw(entryType).id,
        "category" to IngredientCategory.fromRaw(category).id,
        "unit" to UnitType.fromSymbol(unit).symbol,
        "grossDelta" to grossDelta.coerceIn(0.0, 1_000_000.0),
        "wasteDelta" to wasteDelta.coerceIn(0.0, 1_000_000.0),
        "costAmount" to costAmount.coerceIn(0.0, 10_000_000.0),
        "wasteCostAmount" to wasteCostAmount.coerceIn(0.0, 10_000_000.0),
        "loggedByRole" to StaffRole.fromRaw(loggedByRole).id,
        "hourOfDay" to hourOfDay.coerceIn(0, 23),
        "reason" to reason?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
        "staffName" to staffName?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
        "createdAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class RecipeIngredientLine(
    val itemId: String = "",
    val itemName: String = "",
    val quantity: Double = 0.0,
    val unit: String = UnitType.G.symbol,
    val sourceType: String = RecipeLineSource.INVENTORY.id
) {
    val sourceEnum: RecipeLineSource get() = RecipeLineSource.fromRaw(sourceType)

    fun toMap(): Map<String, Any> = mapOf(
        "itemId" to itemId,
        "itemName" to itemName.take(100),
        "quantity" to quantity.coerceAtLeast(0.0),
        "unit" to UnitType.fromSymbol(unit).symbol,
        "sourceType" to RecipeLineSource.fromRaw(sourceType).id
    )
}

@Keep
@IgnoreExtraProperties
data class Recipe(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nameEn: String = "",
    val nameAr: String = "",
    val recipeType: String = RecipeType.MENU_ITEM.id,
    val servings: Int = 1,
    val yieldQuantity: Double = 1.0,
    val yieldUnit: String = UnitType.G.symbol,
    val productionStock: Double = 0.0,
    val minProductionStock: Double = 0.0,
    val menuPrice: Double = 0.0,
    val targetFoodCostPct: Double = 30.0,
    val ingredients: List<Map<String, Any>> = emptyList(),
    val posSku: String? = null,
    val category: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val recipeTypeEnum: RecipeType get() = RecipeType.fromRaw(recipeType)
    val yieldUnitEnum: UnitType get() = UnitType.fromSymbol(yieldUnit)
    val lowProductionStock: Boolean
        get() = recipeTypeEnum == RecipeType.SUB_RECIPE &&
            minProductionStock > 0.0 &&
            productionStock <= minProductionStock

    fun parsedLines(): List<RecipeIngredientLine> = ingredients.map { map ->
        RecipeIngredientLine(
            itemId = map["itemId"] as? String ?: "",
            itemName = map["itemName"] as? String ?: "",
            quantity = (map["quantity"] as? Number)?.toDouble() ?: 0.0,
            unit = map["unit"] as? String ?: UnitType.G.symbol,
            sourceType = RecipeLineSource.fromRaw(map["sourceType"] as? String).id
        )
    }

    fun toCreateMap(uid: String, lines: List<RecipeIngredientLine>): Map<String, Any> {
        val safeType = RecipeType.fromRaw(recipeType)
        val safeYieldQty = yieldQuantity.coerceIn(0.001, 1_000_000.0)
        return mapOf(
            "userId" to uid,
            "nameEn" to nameEn.trim().ifBlank { if (safeType == RecipeType.SUB_RECIPE) "Sub-Recipe" else "Dish" }.take(100),
            "nameAr" to nameAr.trim().take(100),
            "recipeType" to safeType.id,
            "servings" to servings.coerceIn(1, 100),
            "yieldQuantity" to safeYieldQty,
            "yieldUnit" to UnitType.fromSymbol(yieldUnit).symbol,
            "productionStock" to if (safeType == RecipeType.SUB_RECIPE) productionStock.coerceIn(0.0, 1_000_000.0) else 0.0,
            "minProductionStock" to if (safeType == RecipeType.SUB_RECIPE) minProductionStock.coerceIn(0.0, 1_000_000.0) else 0.0,
            "menuPrice" to if (safeType == RecipeType.SUB_RECIPE) 0.0 else menuPrice.coerceIn(0.0, 100_000.0),
            "targetFoodCostPct" to targetFoodCostPct.coerceIn(5.0, 95.0),
            "ingredients" to lines.take(30).map { it.toMap() },
            "posSku" to if (safeType == RecipeType.SUB_RECIPE) null else posSku?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
            "category" to category?.trim()?.takeIf { it.isNotEmpty() }?.take(50),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }.mapValues { it.value!! }
    }
}

@Keep
@IgnoreExtraProperties
data class FlaggedScanItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val rawLabel: String = "",
    val suggestedNameEn: String = "",
    val suggestedNameAr: String = "",
    val quantity: Double = 0.0,
    val unit: String = UnitType.KG.symbol,
    val totalCost: Double = 0.0,
    val confidence: Double = 0.0,
    val reviewReason: String = "",
    val status: String = FlagStatus.PENDING.id,
    val resolvedItemId: String? = null,
    val sourceNote: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "rawLabel" to rawLabel.trim().ifBlank { "Unknown Item" }.take(160),
        "suggestedNameEn" to suggestedNameEn.trim().take(100),
        "suggestedNameAr" to suggestedNameAr.trim().take(100),
        "quantity" to quantity.coerceIn(0.0, 1_000_000.0),
        "unit" to UnitType.fromSymbol(unit).symbol,
        "totalCost" to totalCost.coerceIn(0.0, 10_000_000.0),
        "confidence" to confidence.coerceIn(0.0, 1.0),
        "reviewReason" to reviewReason.trim().ifBlank { "Unrecognized ingredient label" }.take(240),
        "status" to FlagStatus.PENDING.id,
        "resolvedItemId" to resolvedItemId?.trim()?.takeIf { it.isNotEmpty() }?.take(128),
        "sourceNote" to sourceNote?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

enum class InviteStatus(val id: String) {
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    REVOKED("REVOKED"),
    DECLINED("DECLINED");

    companion object {
        fun fromRaw(raw: String?): InviteStatus =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

@Keep
@IgnoreExtraProperties
data class Workgroup(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val name: String = "Main Kitchen",
    val normalizedName: String = "main_kitchen",
    val username: String = "",
    val groupCode: String = "",
    val primaryKitchen: Boolean = false,
    val currencySymbol: String = "$",
    val targetFoodCostPct: Double = 30.0,
    val lowStockAlertsEnabled: Boolean = true,
    val posProvider: String = PosProvider.OMEGA_POS.id,
    val posEndpointUrl: String? = null,
    val logoBase64: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    /** Canonical unique @username handle (without leading '@'). */
    val resolvedUsername: String
        get() = username.trim().lowercase().ifBlank { normalizedName.trim().lowercase() }

    fun toCreateMap(ownerUid: String): Map<String, Any> {
        val cleanHandle = resolvedUsername.take(32)
        return mapOf(
            "ownerUserId" to ownerUid,
            "name" to name.trim().ifBlank { "Main Kitchen" }.take(80),
            "normalizedName" to normalizedName.trim().lowercase().ifBlank { cleanHandle }.take(64),
            "username" to cleanHandle.takeIf { it.length in 3..32 && it.matches(Regex("^[a-z0-9_]+$")) },
            "groupCode" to groupCode.trim().uppercase().take(20),
            "primaryKitchen" to primaryKitchen,
            "currencySymbol" to currencySymbol.trim().ifBlank { "$" }.take(5),
            "targetFoodCostPct" to targetFoodCostPct.coerceIn(5.0, 90.0),
            "lowStockAlertsEnabled" to lowStockAlertsEnabled,
            "posProvider" to PosProvider.fromRaw(posProvider).id,
            "posEndpointUrl" to posEndpointUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(300),
            "logoBase64" to logoBase64?.takeIf { it.isNotBlank() && it.length <= 220_000 },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }.mapValues { it.value!! }
    }
}

@Keep
@IgnoreExtraProperties
data class StaffMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val fullName: String = "",
    val role: String = StaffRole.LINE_COOK.id,
    val canViewReports: Boolean = false,
    val canExportCsv: Boolean = false,
    val canApproveScans: Boolean = false,
    val canEditRecipes: Boolean = false,
    val canManagePos: Boolean = false,
    val active: Boolean = true,
    val station: String? = null,
    val inviteEmail: String? = null,
    val inviteCode: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val roleEnum: StaffRole get() = StaffRole.fromRaw(role)

    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "fullName" to fullName.trim().ifBlank { "Staff Member" }.take(80),
        "role" to StaffRole.fromRaw(role).id,
        "canViewReports" to canViewReports,
        "canExportCsv" to canExportCsv,
        "canApproveScans" to canApproveScans,
        "canEditRecipes" to canEditRecipes,
        "canManagePos" to canManagePos,
        "active" to active,
        "station" to station?.trim()?.takeIf { it.isNotEmpty() }?.take(60),
        "inviteEmail" to inviteEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(160),
        "inviteCode" to inviteCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.take(32),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class WorkgroupInvite(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val groupId: String = "",
    val groupCode: String = "",
    val kitchenName: String = "Main Kitchen",
    val invitedByName: String = "Manager",
    val staffRecordId: String = "",
    val staffName: String = "",
    val inviteEmail: String? = null,
    val role: String = StaffRole.LINE_COOK.id,
    val canViewReports: Boolean = false,
    val canExportCsv: Boolean = false,
    val canApproveScans: Boolean = false,
    val canEditRecipes: Boolean = false,
    val canManagePos: Boolean = false,
    val status: String = InviteStatus.PENDING.id,
    val logoBase64: String? = null,
    val claimedByUid: String? = null,
    val claimedByEmail: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val roleEnum: StaffRole get() = StaffRole.fromRaw(role)
    val statusEnum: InviteStatus get() = InviteStatus.fromRaw(status)
    val resolvedWorkspaceId: String get() = groupId.ifBlank { ownerUserId }
    val requiredVerificationCode: String get() = groupCode.ifBlank { id }

    fun toCreateMap(ownerUid: String): Map<String, Any> = mapOf(
        "ownerUserId" to ownerUid,
        "groupId" to groupId.trim().takeIf { it.isNotEmpty() }?.take(128),
        "groupCode" to groupCode.trim().uppercase().takeIf { it.length in 4..20 },
        "kitchenName" to kitchenName.trim().ifBlank { "Main Kitchen" }.take(80),
        "invitedByName" to invitedByName.trim().ifBlank { "Manager" }.take(80),
        "staffRecordId" to staffRecordId.trim().take(128),
        "staffName" to staffName.trim().ifBlank { "Staff Member" }.take(80),
        "inviteEmail" to inviteEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(160),
        "role" to StaffRole.fromRaw(role).id,
        "canViewReports" to canViewReports,
        "canExportCsv" to canExportCsv,
        "canApproveScans" to canApproveScans,
        "canEditRecipes" to canEditRecipes,
        "canManagePos" to canManagePos,
        "status" to InviteStatus.PENDING.id,
        "logoBase64" to logoBase64?.takeIf { it.isNotBlank() && it.length <= 220_000 },
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class WorkgroupMember(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val memberUid: String = "",
    val staffRecordId: String = "",
    val inviteCode: String = "",
    val fullName: String = "",
    val email: String? = null,
    val role: String = StaffRole.LINE_COOK.id,
    val canViewReports: Boolean = false,
    val canExportCsv: Boolean = false,
    val canApproveScans: Boolean = false,
    val canEditRecipes: Boolean = false,
    val canManagePos: Boolean = false,
    val active: Boolean = true,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val roleEnum: StaffRole get() = StaffRole.fromRaw(role)

    fun toCreateMap(): Map<String, Any> = mapOf(
        "ownerUserId" to ownerUserId.trim().take(128),
        "memberUid" to memberUid.trim().take(128),
        "staffRecordId" to staffRecordId.trim().take(128),
        "inviteCode" to inviteCode.trim().uppercase().take(32),
        "fullName" to fullName.trim().ifBlank { "Staff Member" }.take(80),
        "email" to email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(160),
        "role" to StaffRole.fromRaw(role).id,
        "canViewReports" to canViewReports,
        "canExportCsv" to canExportCsv,
        "canApproveScans" to canApproveScans,
        "canEditRecipes" to canEditRecipes,
        "canManagePos" to canManagePos,
        "active" to active,
        "joinedAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class JoinedWorkgroup(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val groupId: String = "",
    val kitchenName: String = "Main Kitchen",
    val username: String = "",
    val role: String = StaffRole.LINE_COOK.id,
    val inviteCode: String = "",
    val logoBase64: String? = null,
    @ServerTimestamp val joinedAt: Timestamp? = null
) {
    val roleEnum: StaffRole get() = StaffRole.fromRaw(role)
    val resolvedWorkspaceId: String get() = groupId.ifBlank { id.ifBlank { ownerUserId } }

    fun toCreateMap(): Map<String, Any> = mapOf(
        "ownerUserId" to ownerUserId.trim().take(128),
        "groupId" to resolvedWorkspaceId.trim().take(128),
        "kitchenName" to kitchenName.trim().ifBlank { "Main Kitchen" }.take(80),
        "username" to username.trim().lowercase().takeIf { it.length in 3..32 && it.matches(Regex("^[a-z0-9_]+$")) },
        "role" to StaffRole.fromRaw(role).id,
        "inviteCode" to inviteCode.trim().uppercase().take(32),
        "logoBase64" to logoBase64?.takeIf { it.isNotBlank() && it.length <= 220_000 },
        "joinedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class PosSyncRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val provider: String = PosProvider.CUSTOM.id,
    val syncMode: String = "Z_REPORT_BATCH",
    val ordersCount: Int = 0,
    val totalRevenue: Double = 0.0,
    val deductedItemsCount: Int = 0,
    val summary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "provider" to provider.trim().ifBlank { "CUSTOM" }.take(40),
        "syncMode" to if (syncMode == "API_ENDPOINT") "API_ENDPOINT" else "Z_REPORT_BATCH",
        "ordersCount" to ordersCount.coerceIn(0, 100_000),
        "totalRevenue" to totalRevenue.coerceIn(0.0, 100_000_000.0),
        "deductedItemsCount" to deductedItemsCount.coerceIn(0, 10_000),
        "summary" to summary.trim().ifBlank { "POS Sync" }.take(300),
        "createdAt" to FieldValue.serverTimestamp()
    )
}

/** Parsed result from the local/AI Invoice & Label Scanner. */
data class ScannedInvoiceLine(
    val rawLabel: String,
    val canonicalKey: String,
    val nameEn: String,
    val nameAr: String,
    val category: IngredientCategory,
    val quantity: Double,
    val unit: UnitType,
    val totalCost: Double,
    val confidence: Double,
    val needsManualReview: Boolean,
    val reviewReason: String,
    val matchedInventoryId: String? = null,
    val detectedCurrency: String = "$"
)

/** Computed plate profitability metrics for a Recipe. */
data class RecipeCostSummary(
    val rawCostPerServing: Double,
    val wasteAdjustedCostPerServing: Double,
    val totalBatchCost: Double,
    val foodCostPercent: Double,
    val profitPerPlate: Double,
    val profitMarginPercent: Double,
    val suggestedMenuPrice: Double
)

/** Sync state surfaced from Firestore SnapshotMetadata for offline visibility. */
data class SyncStatus(
    val fromCache: Boolean = false,
    val hasPendingWrites: Boolean = false
)

/** Active role permissions governing UI access in shared kitchen terminal mode. */
data class ActiveRolePermissions(
    val role: StaffRole = StaffRole.MANAGER,
    val activeStaffName: String = "Manager",
    val canViewReports: Boolean = true,
    val canExportCsv: Boolean = true,
    val canApproveScans: Boolean = true,
    val canEditRecipes: Boolean = true,
    val canManagePos: Boolean = true
)
