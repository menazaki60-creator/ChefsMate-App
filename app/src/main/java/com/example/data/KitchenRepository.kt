package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.model.FlagStatus
import com.example.model.FlaggedScanItem
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.KitchenProfile
import com.example.model.LedgerEntry
import com.example.model.LedgerEntryType
import com.example.model.PosProvider
import com.example.model.PosSyncRecord
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
import com.example.model.ScannedInvoiceLine
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.model.SyncStatus
import com.example.model.UnitType
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import kotlin.math.max

data class SyncedSnapshot<T>(
    val data: T,
    val syncStatus: SyncStatus
)

class KitchenRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        createConfiguredFirestore(context.applicationContext)
    )

    companion object {
        fun createConfiguredFirestore(appContext: Context): FirebaseFirestore {
            val dbId = appContext.getString(R.string.firestore_database_id)
            val instance = FirebaseFirestore.getInstance(dbId)
            try {
                // Enable unlimited persistent local disk cache for offline kitchen environments
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build()
                    )
                    .build()
                instance.firestoreSettings = settings
            } catch (_: IllegalStateException) {
                // Firestore settings already applied in this process
            }
            return instance
        }
    }

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun currentHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun <T> Flow<T>.logAndRethrow(uid: String, op: OperationType, path: String): Flow<T> =
        catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && auth.currentUser?.uid == uid) {
                handleFirestoreError(toLog, op, path)
            }
            throw error
        }

    // --- Real-Time Streams ---

    fun observeProfile(userId: String): Flow<KitchenProfile?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(KitchenProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .logAndRethrow(userId, OperationType.GET, path)
        )
    }

    fun observeInventory(userId: String): Flow<SyncedSnapshot<List<InventoryItem>>> = flow {
        val path = "users/$userId/inventory"
        emitAll(
            db.collection("users").document(userId).collection("inventory")
                .snapshots(MetadataChanges.INCLUDE)
                .map { snapshot ->
                    val items = snapshot.toObjects(
                        InventoryItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                    SyncedSnapshot(
                        data = items,
                        syncStatus = SyncStatus(
                            fromCache = snapshot.metadata.isFromCache,
                            hasPendingWrites = snapshot.metadata.hasPendingWrites()
                        )
                    )
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    fun observeLedgerEntries(userId: String): Flow<List<LedgerEntry>> = flow {
        val path = "users/$userId/ledger_entries"
        emitAll(
            db.collection("users").document(userId).collection("ledger_entries")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(LedgerEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    fun observeRecipes(userId: String): Flow<List<Recipe>> = flow {
        val path = "users/$userId/recipes"
        emitAll(
            db.collection("users").document(userId).collection("recipes")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Recipe::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    fun observeFlaggedScans(userId: String): Flow<List<FlaggedScanItem>> = flow {
        val path = "users/$userId/flagged_scans"
        emitAll(
            db.collection("users").document(userId).collection("flagged_scans")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(FlaggedScanItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    fun observeStaff(userId: String): Flow<List<StaffMember>> = flow {
        val path = "users/$userId/staff_roles"
        emitAll(
            db.collection("users").document(userId).collection("staff_roles")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(StaffMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.fullName.lowercase() }
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    fun observePosSyncs(userId: String): Flow<List<PosSyncRecord>> = flow {
        val path = "users/$userId/pos_syncs"
        emitAll(
            db.collection("users").document(userId).collection("pos_syncs")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(PosSyncRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    // --- Profile Bootstrap & Updates ---

    suspend fun ensureKitchenProfile(displayName: String): Result<Unit> {
        var docPath = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid)
            docPath = ref.path
            val snap = ref.get().await()
            if (!snap.exists()) {
                val profile = KitchenProfile(
                    userId = uid,
                    displayName = displayName.ifBlank { "Head Chef" },
                    kitchenName = "Main Kitchen",
                    role = StaffRole.MANAGER.id,
                    currencySymbol = "$",
                    targetFoodCostPct = 30.0,
                    lowStockAlertsEnabled = true,
                    posProvider = PosProvider.NONE.id
                )
                ref.set(profile.toCreateMap(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateKitchenProfile(
        kitchenName: String,
        currencySymbol: String,
        targetFoodCostPct: Double,
        lowStockAlertsEnabled: Boolean,
        posProvider: PosProvider,
        posEndpointUrl: String?,
        managerPinHash: String? = null,
        updatePin: Boolean = false
    ): Result<Unit> {
        var docPath = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid)
            docPath = ref.path
            val updates = mutableMapOf<String, Any>(
                "kitchenName" to kitchenName.trim().ifBlank { "Main Kitchen" }.take(80),
                "currencySymbol" to currencySymbol.trim().ifBlank { "$" }.take(5),
                "targetFoodCostPct" to targetFoodCostPct.coerceIn(5.0, 90.0),
                "lowStockAlertsEnabled" to lowStockAlertsEnabled,
                "posProvider" to posProvider.id,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!posEndpointUrl.isNullOrBlank()) {
                updates["posEndpointUrl"] = posEndpointUrl.trim().take(300)
            }
            if (updatePin) {
                updates["managerPinHash"] = managerPinHash?.trim()?.take(128) ?: ""
            }
            ref.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- Inventory & Waste Operations (with Multilingual Unification) ---

    suspend fun addOrMergeInventoryItem(
        rawName: String,
        arabicNameInput: String,
        category: IngredientCategory,
        quantity: Double,
        unit: UnitType,
        totalCost: Double,
        wasteQuantityInWasteUnit: Double,
        wasteUnit: UnitType,
        minStockLevel: Double,
        supplierName: String?,
        existingInventory: List<InventoryItem>,
        loggedByRole: StaffRole,
        staffName: String?,
        entryType: LedgerEntryType = LedgerEntryType.PURCHASE,
        rawScannedLabel: String = ""
    ): Result<String> {
        var docPath = "users/unauthenticated/inventory"
        return try {
            val uid = requireUserId()
            require(rawName.trim().isNotEmpty()) { "Ingredient name is required" }
            val safeQty = quantity.coerceIn(0.0, 1_000_000.0)
            val safeCost = totalCost.coerceIn(0.0, 10_000_000.0)
            val wasteConverted = UnitType.convert(wasteQuantityInWasteUnit.coerceAtLeast(0.0), wasteUnit, unit)
                .coerceIn(0.0, 1_000_000.0)

            val match = BilingualIngredientMatcher.resolve(
                rawName = rawName,
                existingInventory = existingInventory,
                fallbackArabic = arabicNameInput,
                fallbackCategory = category
            )

            val existing = match.matchedExistingId?.let { id ->
                existingInventory.firstOrNull { it.id == id }
            }

            val batch = db.batch()
            val itemRef = if (existing != null) {
                db.collection("users").document(uid).collection("inventory").document(existing.id)
            } else {
                db.collection("users").document(uid).collection("inventory").document()
            }
            docPath = itemRef.path

            val targetUnit = existing?.unitEnum ?: unit
            val convertedGrossDelta = UnitType.convert(safeQty, unit, targetUnit)
            val convertedWasteDelta = UnitType.convert(wasteConverted, unit, targetUnit)

            if (existing != null) {
                val newGross = (existing.grossQuantity + convertedGrossDelta).coerceIn(0.0, 1_000_000.0)
                val newWaste = (existing.wasteQuantity + convertedWasteDelta).coerceIn(0.0, 1_000_000.0)
                val newTotalCost = (existing.totalCost + safeCost).coerceIn(0.0, 10_000_000.0)
                val newUnitCost = if (newGross > 0.0001) newTotalCost / newGross else existing.unitCost
                val mergedAliases = (existing.aliases + listOf(rawName.trim(), arabicNameInput.trim(), rawScannedLabel.trim()))
                    .map { it.take(80) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(20)

                val updateMap = mutableMapOf<String, Any>(
                    "nameAr" to existing.nameAr.ifBlank { match.nameAr.ifBlank { arabicNameInput.trim() } }.take(100),
                    "aliases" to mergedAliases,
                    "grossQuantity" to newGross,
                    "wasteQuantity" to newWaste,
                    "totalCost" to newTotalCost,
                    "unitCost" to newUnitCost.coerceIn(0.0, 1_000_000.0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!supplierName.isNullOrBlank()) {
                    updateMap["supplierName"] = supplierName.trim().take(100)
                }
                batch.update(itemRef, updateMap)
            } else {
                val unitCost = if (convertedGrossDelta > 0.0001) safeCost / convertedGrossDelta else safeCost
                val aliases = listOf(rawName.trim(), match.nameEn, match.nameAr, arabicNameInput.trim(), rawScannedLabel.trim())
                    .map { it.take(80) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(20)
                val newItem = InventoryItem(
                    userId = uid,
                    canonicalKey = match.canonicalKey,
                    nameEn = match.nameEn,
                    nameAr = match.nameAr.ifBlank { arabicNameInput.trim() },
                    aliases = aliases,
                    category = match.category.id,
                    unit = targetUnit.symbol,
                    grossQuantity = convertedGrossDelta,
                    wasteQuantity = convertedWasteDelta,
                    consumedQuantity = 0.0,
                    totalCost = safeCost,
                    unitCost = unitCost.coerceIn(0.0, 1_000_000.0),
                    minStockLevel = minStockLevel.coerceIn(0.0, 1_000_000.0),
                    supplierName = supplierName?.trim()?.takeIf { it.isNotEmpty() }
                )
                batch.set(itemRef, newItem.toCreateMap(uid))
            }

            // Append Ledger Entry for reporting & CSV export
            val unitCostForWaste = if (existing != null && existing.unitCost > 0.0) {
                existing.unitCost
            } else if (convertedGrossDelta > 0.0001) {
                safeCost / convertedGrossDelta
            } else 0.0

            val ledgerRef = db.collection("users").document(uid).collection("ledger_entries").document()
            val ledger = LedgerEntry(
                userId = uid,
                itemId = itemRef.id,
                itemNameEn = existing?.nameEn ?: match.nameEn,
                itemNameAr = existing?.nameAr?.ifBlank { match.nameAr } ?: match.nameAr,
                rawScannedLabel = rawScannedLabel.ifBlank { rawName },
                entryType = entryType.id,
                category = (existing?.categoryEnum ?: match.category).id,
                unit = targetUnit.symbol,
                grossDelta = convertedGrossDelta,
                wasteDelta = convertedWasteDelta,
                costAmount = safeCost,
                wasteCostAmount = (convertedWasteDelta * unitCostForWaste).coerceIn(0.0, 10_000_000.0),
                loggedByRole = loggedByRole.id,
                hourOfDay = currentHourOfDay(),
                reason = if (convertedWasteDelta > 0.0) "Initial trim / prep waste" else null,
                staffName = staffName
            )
            batch.set(ledgerRef, ledger.toCreateMap(uid))
            batch.commit().await()
            Result.success(itemRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun logWasteForItem(
        item: InventoryItem,
        wasteAmount: Double,
        wasteUnit: UnitType,
        reason: String,
        loggedByRole: StaffRole,
        staffName: String?
    ): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/${item.id}"
        return try {
            val uid = requireUserId()
            require(item.id.isNotBlank()) { "Item ID required" }
            val convertedWaste = UnitType.convert(wasteAmount.coerceAtLeast(0.0), wasteUnit, item.unitEnum)
            require(convertedWaste > 0.0) { "Waste amount must be positive" }

            val itemRef = db.collection("users").document(uid).collection("inventory").document(item.id)
            docPath = itemRef.path
            val batch = db.batch()
            val newWasteTotal = (item.wasteQuantity + convertedWaste).coerceIn(0.0, 1_000_000.0)

            batch.update(
                itemRef,
                mapOf(
                    "wasteQuantity" to newWasteTotal,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            val ledgerRef = db.collection("users").document(uid).collection("ledger_entries").document()
            val wasteCost = (convertedWaste * item.unitCost).coerceIn(0.0, 10_000_000.0)
            val ledger = LedgerEntry(
                userId = uid,
                itemId = item.id,
                itemNameEn = item.nameEn,
                itemNameAr = item.nameAr,
                rawScannedLabel = item.nameEn,
                entryType = LedgerEntryType.WASTE.id,
                category = item.category,
                unit = item.unit,
                grossDelta = 0.0,
                wasteDelta = convertedWaste,
                costAmount = 0.0,
                wasteCostAmount = wasteCost,
                loggedByRole = loggedByRole.id,
                hourOfDay = currentHourOfDay(),
                reason = reason.trim().ifBlank { "Prep / Spoilage waste" },
                staffName = staffName
            )
            batch.set(ledgerRef, ledger.toCreateMap(uid))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateMinStockLevel(itemId: String, minStockLevel: Double): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/$itemId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("inventory").document(itemId)
            docPath = ref.path
            ref.update(
                mapOf(
                    "minStockLevel" to minStockLevel.coerceIn(0.0, 1_000_000.0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteInventoryItem(itemId: String): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/$itemId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("inventory").document(itemId)
            docPath = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- Flagged Bilingual Scans (Manual Human Review Queue) ---

    suspend fun saveFlaggedScanItem(line: ScannedInvoiceLine, sourceNote: String? = null): Result<Unit> {
        var docPath = "users/unauthenticated/flagged_scans"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("flagged_scans").document()
            docPath = ref.path
            val flagged = FlaggedScanItem(
                userId = uid,
                rawLabel = line.rawLabel.ifBlank { "Unknown label" },
                suggestedNameEn = line.nameEn,
                suggestedNameAr = line.nameAr,
                quantity = line.quantity,
                unit = line.unit.symbol,
                totalCost = line.totalCost,
                confidence = line.confidence,
                reviewReason = line.reviewReason.ifBlank { "Needs manual verification" },
                status = FlagStatus.PENDING.id,
                sourceNote = sourceNote
            )
            ref.set(flagged.toCreateMap(uid)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun approveFlaggedScan(
        flagged: FlaggedScanItem,
        targetNameEn: String,
        targetNameAr: String,
        category: IngredientCategory,
        linkToExisting: InventoryItem?,
        existingInventory: List<InventoryItem>,
        loggedByRole: StaffRole,
        staffName: String?
    ): Result<Unit> {
        var docPath = "users/unauthenticated/flagged_scans/${flagged.id}"
        return try {
            val uid = requireUserId()
            require(flagged.id.isNotBlank()) { "Flagged scan ID required" }

            val itemIdResult = if (linkToExisting != null) {
                addOrMergeInventoryItem(
                    rawName = linkToExisting.nameEn,
                    arabicNameInput = targetNameAr.ifBlank { flagged.rawLabel },
                    category = linkToExisting.categoryEnum,
                    quantity = flagged.quantity,
                    unit = UnitType.fromSymbol(flagged.unit),
                    totalCost = flagged.totalCost,
                    wasteQuantityInWasteUnit = 0.0,
                    wasteUnit = UnitType.fromSymbol(flagged.unit),
                    minStockLevel = linkToExisting.minStockLevel,
                    supplierName = null,
                    existingInventory = existingInventory,
                    loggedByRole = loggedByRole,
                    staffName = staffName,
                    entryType = LedgerEntryType.INVOICE_SCAN,
                    rawScannedLabel = flagged.rawLabel
                )
            } else {
                addOrMergeInventoryItem(
                    rawName = targetNameEn.ifBlank { flagged.rawLabel },
                    arabicNameInput = targetNameAr,
                    category = category,
                    quantity = flagged.quantity,
                    unit = UnitType.fromSymbol(flagged.unit),
                    totalCost = flagged.totalCost,
                    wasteQuantityInWasteUnit = 0.0,
                    wasteUnit = UnitType.fromSymbol(flagged.unit),
                    minStockLevel = 0.5,
                    supplierName = null,
                    existingInventory = existingInventory,
                    loggedByRole = loggedByRole,
                    staffName = staffName,
                    entryType = LedgerEntryType.INVOICE_SCAN,
                    rawScannedLabel = flagged.rawLabel
                )
            }

            val resolvedId = itemIdResult.getOrThrow()
            val flagRef = db.collection("users").document(uid).collection("flagged_scans").document(flagged.id)
            docPath = flagRef.path
            flagRef.update(
                mapOf(
                    "status" to FlagStatus.APPROVED.id,
                    "resolvedItemId" to resolvedId.take(128),
                    "suggestedNameEn" to targetNameEn.trim().take(100),
                    "suggestedNameAr" to targetNameAr.trim().take(100),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun dismissFlaggedScan(flaggedId: String): Result<Unit> {
        var docPath = "users/unauthenticated/flagged_scans/$flaggedId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("flagged_scans").document(flaggedId)
            docPath = ref.path
            ref.update(
                mapOf(
                    "status" to FlagStatus.DISMISSED.id,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- Recipes & Plate Costing ---

    suspend fun saveRecipe(
        existingId: String?,
        nameEn: String,
        nameAr: String,
        servings: Int,
        menuPrice: Double,
        targetFoodCostPct: Double,
        posSku: String?,
        category: String?,
        lines: List<RecipeIngredientLine>
    ): Result<Unit> {
        var docPath = "users/unauthenticated/recipes"
        return try {
            val uid = requireUserId()
            require(nameEn.trim().isNotEmpty()) { "Recipe name is required" }
            val collection = db.collection("users").document(uid).collection("recipes")
            if (existingId.isNullOrBlank()) {
                val ref = collection.document()
                docPath = ref.path
                val recipe = Recipe(
                    userId = uid,
                    nameEn = nameEn,
                    nameAr = nameAr,
                    servings = servings,
                    menuPrice = menuPrice,
                    targetFoodCostPct = targetFoodCostPct,
                    posSku = posSku,
                    category = category
                )
                ref.set(recipe.toCreateMap(uid, lines)).await()
            } else {
                val ref = collection.document(existingId)
                docPath = ref.path
                val updates = mutableMapOf<String, Any>(
                    "nameEn" to nameEn.trim().take(100),
                    "nameAr" to nameAr.trim().take(100),
                    "servings" to servings.coerceIn(1, 100),
                    "menuPrice" to menuPrice.coerceIn(0.0, 100_000.0),
                    "targetFoodCostPct" to targetFoodCostPct.coerceIn(5.0, 95.0),
                    "ingredients" to lines.take(30).map { it.toMap() },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!posSku.isNullOrBlank()) updates["posSku"] = posSku.trim().take(64)
                if (!category.isNullOrBlank()) updates["category"] = category.trim().take(50)
                ref.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteRecipe(recipeId: String): Result<Unit> {
        var docPath = "users/unauthenticated/recipes/$recipeId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("recipes").document(recipeId)
            docPath = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- POS Sales Deduction & Sync Audit ---

    suspend fun applyPosSalesDeductions(
        provider: PosProvider,
        syncMode: String,
        soldRecipes: List<Pair<Recipe, Int>>,
        inventory: List<InventoryItem>,
        loggedByRole: StaffRole,
        staffName: String?
    ): Result<PosSyncRecord> {
        var docPath = "users/unauthenticated/pos_syncs"
        return try {
            val uid = requireUserId()
            val batch = db.batch()

            // Accumulate total consumption per inventory item across all sold plates
            val consumptionByItemId = mutableMapOf<String, Double>()
            var totalRevenue = 0.0
            var totalOrders = 0

            for ((recipe, platesSold) in soldRecipes) {
                if (platesSold <= 0) continue
                totalOrders += platesSold
                totalRevenue += recipe.menuPrice * platesSold
                val servingsMultiplier = platesSold.toDouble() / max(1, recipe.servings).toDouble()
                for (line in recipe.parsedLines()) {
                    val invItem = inventory.firstOrNull { it.id == line.itemId } ?: continue
                    val qtyInInvUnit = UnitType.convert(
                        line.quantity * servingsMultiplier,
                        UnitType.fromSymbol(line.unit),
                        invItem.unitEnum
                    )
                    consumptionByItemId[invItem.id] = (consumptionByItemId[invItem.id] ?: 0.0) + qtyInInvUnit
                }
            }

            for ((itemId, consumedDelta) in consumptionByItemId) {
                val invItem = inventory.firstOrNull { it.id == itemId } ?: continue
                val itemRef = db.collection("users").document(uid).collection("inventory").document(itemId)
                val newConsumed = (invItem.consumedQuantity + consumedDelta).coerceIn(0.0, 1_000_000.0)
                batch.update(
                    itemRef,
                    mapOf(
                        "consumedQuantity" to newConsumed,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                val ledgerRef = db.collection("users").document(uid).collection("ledger_entries").document()
                val ledger = LedgerEntry(
                    userId = uid,
                    itemId = invItem.id,
                    itemNameEn = invItem.nameEn,
                    itemNameAr = invItem.nameAr,
                    rawScannedLabel = "POS:${provider.id}",
                    entryType = LedgerEntryType.POS_SALE.id,
                    category = invItem.category,
                    unit = invItem.unit,
                    grossDelta = consumedDelta,
                    wasteDelta = 0.0,
                    costAmount = (consumedDelta * invItem.effectiveNetUnitCost).coerceIn(0.0, 10_000_000.0),
                    wasteCostAmount = 0.0,
                    loggedByRole = loggedByRole.id,
                    hourOfDay = currentHourOfDay(),
                    reason = "POS sales deduction (${provider.displayName})",
                    staffName = staffName
                )
                batch.set(ledgerRef, ledger.toCreateMap(uid))
            }

            val syncRef = db.collection("users").document(uid).collection("pos_syncs").document()
            docPath = syncRef.path
            val summaryText = "$totalOrders plates synced across ${consumptionByItemId.size} ingredients"
            val record = PosSyncRecord(
                userId = uid,
                provider = provider.displayName.take(40),
                syncMode = syncMode,
                ordersCount = totalOrders,
                totalRevenue = totalRevenue.coerceIn(0.0, 100_000_000.0),
                deductedItemsCount = consumptionByItemId.size,
                summary = summaryText.take(300)
            )
            batch.set(syncRef, record.toCreateMap(uid))
            batch.commit().await()
            Result.success(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    // --- Staff RBAC Management ---

    suspend fun saveStaffMember(
        existingId: String?,
        member: StaffMember
    ): Result<Unit> {
        var docPath = "users/unauthenticated/staff_roles"
        return try {
            val uid = requireUserId()
            val col = db.collection("users").document(uid).collection("staff_roles")
            if (existingId.isNullOrBlank()) {
                val ref = col.document()
                docPath = ref.path
                ref.set(member.toCreateMap(uid)).await()
            } else {
                val ref = col.document(existingId)
                docPath = ref.path
                val updates = mutableMapOf<String, Any>(
                    "fullName" to member.fullName.trim().ifBlank { "Staff" }.take(80),
                    "role" to StaffRole.fromRaw(member.role).id,
                    "canViewReports" to member.canViewReports,
                    "canExportCsv" to member.canExportCsv,
                    "canApproveScans" to member.canApproveScans,
                    "canEditRecipes" to member.canEditRecipes,
                    "canManagePos" to member.canManagePos,
                    "active" to member.active,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!member.station.isNullOrBlank()) {
                    updates["station"] = member.station.trim().take(60)
                }
                ref.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteStaffMember(staffId: String): Result<Unit> {
        var docPath = "users/unauthenticated/staff_roles/$staffId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("staff_roles").document(staffId)
            docPath = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
