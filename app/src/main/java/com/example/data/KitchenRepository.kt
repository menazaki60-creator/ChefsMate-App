package com.example.data

import android.content.Context
import com.example.R
import com.example.domain.RecipeCostEngine
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
import com.example.model.RecipeLineSource
import com.example.model.RecipeType
import com.example.model.ScannedInvoiceLine
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.model.StockAdjustMode
import com.example.model.SyncStatus
import com.example.model.UnitType
import com.example.model.Workgroup
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.WriteBatch
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

data class ScannedCommitSummary(
    val addedCount: Int,
    val flaggedCount: Int
)

private data class PendingInventoryWrite(
    val ref: DocumentReference,
    val isNew: Boolean,
    val newItem: InventoryItem?,
    val updateMap: Map<String, Any>?
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

    private fun resolveWorkspaceOwnerId(workspaceOwnerId: String?): String {
        val authUid = requireUserId()
        return workspaceOwnerId?.trim()?.takeIf { it.isNotEmpty() } ?: authUid
    }

    /** Routes additional manager-owned locations (grp_*) to /workgroups/{id} and primary kitchens to /users/{uid}. */
    private fun workspaceDoc(workspaceId: String): DocumentReference =
        if (workspaceId.startsWith("grp_")) {
            db.collection("workgroups").document(workspaceId)
        } else {
            db.collection("users").document(workspaceId)
        }

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

    fun observeProfile(workspaceId: String): Flow<KitchenProfile?> = flow {
        val docRef = workspaceDoc(workspaceId)
        val path = docRef.path
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) return@map null
                    if (workspaceId.startsWith("grp_")) {
                        val wg = snapshot.toObject(Workgroup::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?: return@map null
                        KitchenProfile(
                            id = wg.id,
                            userId = wg.ownerUserId,
                            displayName = auth.currentUser?.displayName ?: "Manager",
                            kitchenName = wg.name,
                            role = StaffRole.MANAGER.id,
                            currencySymbol = wg.currencySymbol,
                            targetFoodCostPct = wg.targetFoodCostPct,
                            lowStockAlertsEnabled = wg.lowStockAlertsEnabled,
                            posProvider = wg.posProvider,
                            posEndpointUrl = wg.posEndpointUrl,
                            logoBase64 = wg.logoBase64,
                            groupCode = wg.groupCode,
                            createdAt = wg.createdAt,
                            updatedAt = wg.updatedAt
                        )
                    } else {
                        snapshot.toObject(KitchenProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.GET, path)
        )
    }

    fun observeInventory(workspaceId: String): Flow<SyncedSnapshot<List<InventoryItem>>> = flow {
        val colRef = workspaceDoc(workspaceId).collection("inventory")
        val path = colRef.path
        emitAll(
            colRef.snapshots(MetadataChanges.INCLUDE)
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
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
        )
    }

    fun observeLedgerEntries(workspaceId: String): Flow<List<LedgerEntry>> = flow {
        val colRef = workspaceDoc(workspaceId).collection("ledger_entries")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(LedgerEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
        )
    }

    fun observeRecipes(workspaceId: String): Flow<List<Recipe>> = flow {
        val colRef = workspaceDoc(workspaceId).collection("recipes")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Recipe::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
        )
    }

    fun observeFlaggedScans(workspaceId: String): Flow<List<FlaggedScanItem>> = flow {
        val colRef = workspaceDoc(workspaceId).collection("flagged_scans")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(FlaggedScanItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
        )
    }

    fun observeStaff(workspaceId: String): Flow<List<StaffMember>> = flow {
        val colRef = workspaceDoc(workspaceId).collection("staff_roles")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(StaffMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.fullName.lowercase() }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
        )
    }

    fun observePosSyncs(workspaceId: String): Flow<List<PosSyncRecord>> = flow {
        val colRef = workspaceDoc(workspaceId).collection("pos_syncs")
        val path = colRef.path
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(PosSyncRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
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
        updatePin: Boolean = false,
        workspaceId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val targetWorkspace = workspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: uid
            val safeCurrency = currencySymbol.trim().ifBlank { "$" }.take(5)
            val safeFoodCost = targetFoodCostPct.coerceIn(5.0, 90.0)
            val safeEndpoint = posEndpointUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(300)

            if (targetWorkspace.startsWith("grp_")) {
                val ref = db.collection("workgroups").document(targetWorkspace)
                docPath = ref.path
                val updates = mutableMapOf<String, Any>(
                    "currencySymbol" to safeCurrency,
                    "targetFoodCostPct" to safeFoodCost,
                    "lowStockAlertsEnabled" to lowStockAlertsEnabled,
                    "posProvider" to posProvider.id,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (safeEndpoint != null) {
                    updates["posEndpointUrl"] = safeEndpoint
                }
                ref.update(updates).await()
            } else {
                val ref = db.collection("users").document(uid)
                docPath = ref.path
                val updates = mutableMapOf<String, Any>(
                    "kitchenName" to kitchenName.trim().ifBlank { "Main Kitchen" }.take(80),
                    "currencySymbol" to safeCurrency,
                    "targetFoodCostPct" to safeFoodCost,
                    "lowStockAlertsEnabled" to lowStockAlertsEnabled,
                    "posProvider" to posProvider.id,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (safeEndpoint != null) {
                    updates["posEndpointUrl"] = safeEndpoint
                }
                if (updatePin) {
                    updates["managerPinHash"] = managerPinHash?.trim()?.take(128) ?: ""
                }
                ref.update(updates).await()

                // Mirror settings onto primary /workgroups/{uid} metadata doc if it exists
                runCatching {
                    val wgRef = db.collection("workgroups").document(uid)
                    val wgUpdates = mutableMapOf<String, Any>(
                        "currencySymbol" to safeCurrency,
                        "targetFoodCostPct" to safeFoodCost,
                        "lowStockAlertsEnabled" to lowStockAlertsEnabled,
                        "posProvider" to posProvider.id,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (safeEndpoint != null) wgUpdates["posEndpointUrl"] = safeEndpoint
                    wgRef.update(wgUpdates).await()
                }
            }
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
        rawScannedLabel: String = "",
        allowAutoCorrectTypos: Boolean = true,
        workspaceOwnerId: String? = null
    ): Result<String> {
        var docPath = "users/unauthenticated/inventory"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            require(rawName.trim().isNotEmpty()) { "Ingredient name is required" }
            val safeQty = quantity.coerceIn(0.0, 1_000_000.0)
            val safeCost = totalCost.coerceIn(0.0, 10_000_000.0)
            val wasteConverted = UnitType.convert(wasteQuantityInWasteUnit.coerceAtLeast(0.0), wasteUnit, unit)
                .coerceIn(0.0, 1_000_000.0)

            val match = BilingualIngredientMatcher.resolve(
                rawName = rawName,
                existingInventory = existingInventory,
                fallbackArabic = arabicNameInput,
                fallbackCategory = category,
                allowAutoCorrectTypos = allowAutoCorrectTypos
            )

            val existing = match.matchedExistingId?.let { id ->
                existingInventory.firstOrNull { it.id == id }
            }

            val batch = db.batch()
            val itemRef = if (existing != null) {
                workspaceDoc(uid).collection("inventory").document(existing.id)
            } else {
                workspaceDoc(uid).collection("inventory").document()
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

            val ledgerRef = workspaceDoc(uid).collection("ledger_entries").document()
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

    /**
     * Commits all scanned invoice lines (inventory creates/updates, INVOICE_SCAN ledger entries,
     * and flagged manual-review items) in a single atomic Firestore WriteBatch instead of
     * sequential per-line network round-trips.
     */
    suspend fun commitScannedLinesBatch(
        lines: List<ScannedInvoiceLine>,
        existingInventory: List<InventoryItem>,
        loggedByRole: StaffRole,
        staffName: String?,
        workspaceOwnerId: String? = null
    ): Result<ScannedCommitSummary> {
        var docPath = "users/unauthenticated/inventory"
        return try {
            if (lines.isEmpty()) return Result.success(ScannedCommitSummary(0, 0))
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val userDoc = workspaceDoc(uid)
            val inventoryCol = userDoc.collection("inventory")
            val ledgerCol = userDoc.collection("ledger_entries")
            val flaggedCol = userDoc.collection("flagged_scans")
            docPath = inventoryCol.path

            val workingInventory = existingInventory.toMutableList()
            val pendingInvWrites = linkedMapOf<String, PendingInventoryWrite>()
            val batchOps = mutableListOf<(WriteBatch) -> Unit>()

            var addedCount = 0
            var flaggedCount = 0
            val hour = currentHourOfDay()

            for (line in lines) {
                if (line.needsManualReview) {
                    val flagRef = flaggedCol.document()
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
                        sourceNote = "Local Invoice Scanner"
                    )
                    val flagMap = flagged.toCreateMap(uid)
                    batchOps.add { b -> b.set(flagRef, flagMap) }
                    flaggedCount++
                    continue
                }

                val rawName = line.nameEn.ifBlank { line.rawLabel }.trim()
                if (rawName.isEmpty()) continue

                val safeQty = line.quantity.coerceIn(0.0, 1_000_000.0)
                val safeCost = line.totalCost.coerceIn(0.0, 10_000_000.0)
                val normalizedRaw = BilingualIngredientMatcher.normalize(rawName)

                // Strictly respect explicit chef POS/stock link OR exact existing name/alias match (NEVER invent or substitute generic names!)
                val existingIndex = line.matchedInventoryId
                    ?.let { targetId -> workingInventory.indexOfFirst { it.id == targetId } }
                    ?.takeIf { it >= 0 }
                    ?: workingInventory.indexOfFirst { item ->
                        BilingualIngredientMatcher.normalize(item.nameEn) == normalizedRaw ||
                            (item.nameAr.isNotBlank() && BilingualIngredientMatcher.normalize(item.nameAr) == normalizedRaw) ||
                            item.canonicalKey.equals(line.canonicalKey, ignoreCase = true) ||
                            item.aliases.any { BilingualIngredientMatcher.normalize(it) == normalizedRaw }
                    }.takeIf { it >= 0 }

                val targetUnit = existingIndex?.let { workingInventory[it].unitEnum } ?: line.unit
                val convertedGrossDelta = UnitType.convert(safeQty, line.unit, targetUnit)

                val itemId: String
                val ledgerNameEn: String
                val ledgerNameAr: String
                val ledgerCategory: String

                if (existingIndex != null) {
                    val existing = workingInventory[existingIndex]
                    val newGross = (existing.grossQuantity + convertedGrossDelta).coerceIn(0.0, 1_000_000.0)
                    val newTotalCost = (existing.totalCost + safeCost).coerceIn(0.0, 10_000_000.0)
                    val newUnitCost = if (newGross > 0.0001) {
                        (newTotalCost / newGross).coerceIn(0.0, 1_000_000.0)
                    } else {
                        existing.unitCost
                    }
                    val mergedAliases = (existing.aliases + listOf(rawName, line.nameAr.trim(), line.rawLabel.trim()))
                        .map { it.take(80) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(20)
                    val mergedNameAr = existing.nameAr.ifBlank { line.nameAr.trim() }.take(100)

                    val updatedItem = existing.copy(
                        nameAr = mergedNameAr,
                        aliases = mergedAliases,
                        grossQuantity = newGross,
                        totalCost = newTotalCost,
                        unitCost = newUnitCost,
                        supplierName = "Scanned Invoice"
                    )
                    workingInventory[existingIndex] = updatedItem

                    val priorWrite = pendingInvWrites[existing.id]
                    if (priorWrite != null && priorWrite.isNew) {
                        pendingInvWrites[existing.id] = priorWrite.copy(newItem = updatedItem)
                    } else {
                        val itemRef = inventoryCol.document(existing.id)
                        val updateMap = mapOf<String, Any>(
                            "nameAr" to mergedNameAr,
                            "aliases" to mergedAliases,
                            "grossQuantity" to newGross,
                            "wasteQuantity" to existing.wasteQuantity,
                            "totalCost" to newTotalCost,
                            "unitCost" to newUnitCost,
                            "supplierName" to "Scanned Invoice",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        pendingInvWrites[existing.id] = PendingInventoryWrite(
                            ref = itemRef,
                            isNew = false,
                            newItem = null,
                            updateMap = updateMap
                        )
                    }

                    itemId = existing.id
                    ledgerNameEn = existing.nameEn
                    ledgerNameAr = mergedNameAr
                    ledgerCategory = existing.categoryEnum.id
                } else {
                    val itemRef = inventoryCol.document()
                    val unitCost = (if (convertedGrossDelta > 0.0001) safeCost / convertedGrossDelta else safeCost)
                        .coerceIn(0.0, 1_000_000.0)
                    val aliases = listOf(rawName, line.nameAr.trim(), line.rawLabel.trim())
                        .map { it.take(80) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(20)
                    val newItem = InventoryItem(
                        id = itemRef.id,
                        userId = uid,
                        canonicalKey = line.canonicalKey.ifBlank { normalizedRaw.ifBlank { "item" } },
                        nameEn = rawName,
                        nameAr = line.nameAr.trim(),
                        aliases = aliases,
                        category = line.category.id,
                        unit = targetUnit.symbol,
                        grossQuantity = convertedGrossDelta,
                        wasteQuantity = 0.0,
                        consumedQuantity = 0.0,
                        totalCost = safeCost,
                        unitCost = unitCost,
                        minStockLevel = 0.5,
                        supplierName = "Scanned Invoice"
                    )
                    workingInventory.add(newItem)
                    pendingInvWrites[itemRef.id] = PendingInventoryWrite(
                        ref = itemRef,
                        isNew = true,
                        newItem = newItem,
                        updateMap = null
                    )

                    itemId = itemRef.id
                    ledgerNameEn = newItem.nameEn
                    ledgerNameAr = newItem.nameAr
                    ledgerCategory = newItem.category
                }

                val ledgerRef = ledgerCol.document()
                val ledger = LedgerEntry(
                    userId = uid,
                    itemId = itemId,
                    itemNameEn = ledgerNameEn,
                    itemNameAr = ledgerNameAr,
                    rawScannedLabel = line.rawLabel.ifBlank { rawName },
                    entryType = LedgerEntryType.INVOICE_SCAN.id,
                    category = ledgerCategory,
                    unit = targetUnit.symbol,
                    grossDelta = convertedGrossDelta,
                    wasteDelta = 0.0,
                    costAmount = safeCost,
                    wasteCostAmount = 0.0,
                    loggedByRole = loggedByRole.id,
                    hourOfDay = hour,
                    reason = null,
                    staffName = staffName
                )
                val ledgerMap = ledger.toCreateMap(uid)
                batchOps.add { b -> b.set(ledgerRef, ledgerMap) }
                addedCount++
            }

            // Place all unique inventory creates/updates first, then ledger & flagged writes
            val allOps = mutableListOf<(WriteBatch) -> Unit>()
            pendingInvWrites.values.forEach { write ->
                if (write.isNew && write.newItem != null) {
                    val createMap = write.newItem.toCreateMap(uid)
                    allOps.add { b -> b.set(write.ref, createMap) }
                } else if (write.updateMap != null) {
                    allOps.add { b -> b.update(write.ref, write.updateMap) }
                }
            }
            allOps.addAll(batchOps)

            allOps.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { op -> op(batch) }
                batch.commit().await()
            }

            Result.success(ScannedCommitSummary(addedCount = addedCount, flaggedCount = flaggedCount))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Adds selected basic culinary staples from the built-in bilingual catalog to inventory
     * (skipping any items already present) with 0 initial stock/cost so chefs can price them on purchase.
     */
    suspend fun addBasicIngredientsBatch(
        specs: List<BasicIngredientSpec>,
        existingInventory: List<InventoryItem>,
        workspaceOwnerId: String? = null
    ): Result<Int> {
        var docPath = "users/unauthenticated/inventory"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val existingKeys = existingInventory.flatMap {
                listOf(
                    BilingualIngredientMatcher.normalize(it.canonicalKey),
                    BilingualIngredientMatcher.normalize(it.nameEn)
                )
            }.toSet()

            val toCreate = specs.filter { spec ->
                BilingualIngredientMatcher.normalize(spec.key) !in existingKeys &&
                    BilingualIngredientMatcher.normalize(spec.nameEn) !in existingKeys
            }
            if (toCreate.isEmpty()) return Result.success(0)

            val col = workspaceDoc(uid).collection("inventory")
            docPath = col.path

            // Chunk into Firestore batch limit (max 400 writes per batch)
            toCreate.chunked(200).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { spec ->
                    val ref = col.document()
                    val aliases = (listOf(spec.nameEn, spec.nameAr) + spec.synonyms.take(6))
                        .map { it.trim().take(80) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(20)
                    val item = InventoryItem(
                        userId = uid,
                        canonicalKey = spec.key,
                        nameEn = spec.nameEn,
                        nameAr = spec.nameAr,
                        aliases = aliases,
                        category = spec.category.id,
                        unit = spec.defaultUnit.symbol,
                        grossQuantity = 0.0,
                        wasteQuantity = 0.0,
                        consumedQuantity = 0.0,
                        totalCost = 0.0,
                        unitCost = 0.0,
                        minStockLevel = spec.defaultMinStock
                    )
                    batch.set(ref, item.toCreateMap(uid))
                }
                batch.commit().await()
            }
            Result.success(toCreate.size)
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
        staffName: String?,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/${item.id}"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            require(item.id.isNotBlank()) { "Item ID required" }
            val convertedWaste = UnitType.convert(wasteAmount.coerceAtLeast(0.0), wasteUnit, item.unitEnum)
            require(convertedWaste > 0.0) { "Waste amount must be positive" }

            val itemRef = workspaceDoc(uid).collection("inventory").document(item.id)
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

            val ledgerRef = workspaceDoc(uid).collection("ledger_entries").document()
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

    suspend fun updateMinStockLevel(itemId: String, minStockLevel: Double, workspaceOwnerId: String? = null): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/$itemId"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val ref = workspaceDoc(uid).collection("inventory").document(itemId)
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

    suspend fun adjustRawItemStock(
        item: InventoryItem,
        mode: StockAdjustMode,
        amountInInputUnit: Double,
        inputUnit: UnitType,
        extraCost: Double = 0.0,
        newMinStockLevel: Double? = null,
        reason: String = "",
        loggedByRole: StaffRole,
        staffName: String?,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/${item.id}"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            require(item.id.isNotBlank()) { "Item ID required" }
            val convertedAmount = UnitType.convert(amountInInputUnit.coerceAtLeast(0.0), inputUnit, item.unitEnum)
            val safeExtraCost = extraCost.coerceIn(0.0, 10_000_000.0)
            val itemRef = workspaceDoc(uid).collection("inventory").document(item.id)
            docPath = itemRef.path

            val currentAvailable = item.currentStock
            var newGross = item.grossQuantity
            var newConsumed = item.consumedQuantity
            var newTotalCost = item.totalCost
            var newUnitCost = item.unitCost
            var logDelta = 0.0
            var entryType = LedgerEntryType.USAGE
            var entryCost = 0.0
            var defaultReason = "Stock adjustment"

            when (mode) {
                StockAdjustMode.ADD -> {
                    require(convertedAmount > 0.0) { "Add quantity must be positive" }
                    newGross = (item.grossQuantity + convertedAmount).coerceIn(0.0, 1_000_000.0)
                    val costAddition = if (safeExtraCost > 0.0) safeExtraCost else (convertedAmount * item.unitCost)
                    newTotalCost = (item.totalCost + costAddition).coerceIn(0.0, 10_000_000.0)
                    newUnitCost = if (newGross > 0.0001) (newTotalCost / newGross).coerceIn(0.0, 1_000_000.0) else item.unitCost
                    logDelta = convertedAmount
                    entryType = LedgerEntryType.PURCHASE
                    entryCost = costAddition.coerceIn(0.0, 10_000_000.0)
                    defaultReason = "Stock restock"
                }

                StockAdjustMode.DEDUCT -> {
                    require(convertedAmount > 0.0) { "Deduct quantity must be positive" }
                    val actualDeduct = minOf(convertedAmount, currentAvailable)
                    newConsumed = (item.consumedQuantity + actualDeduct).coerceIn(0.0, 1_000_000.0)
                    logDelta = actualDeduct
                    entryType = LedgerEntryType.USAGE
                    entryCost = (actualDeduct * item.effectiveNetUnitCost).coerceIn(0.0, 10_000_000.0)
                    defaultReason = "Manual stock deduction"
                }

                StockAdjustMode.SET_EXACT -> {
                    val targetStock = convertedAmount.coerceIn(0.0, 1_000_000.0)
                    val diff = targetStock - currentAvailable
                    if (diff > 0.0001) {
                        newGross = (item.grossQuantity + diff).coerceIn(0.0, 1_000_000.0)
                        val costAddition = if (safeExtraCost > 0.0) safeExtraCost else (diff * item.unitCost)
                        newTotalCost = (item.totalCost + costAddition).coerceIn(0.0, 10_000_000.0)
                        newUnitCost = if (newGross > 0.0001) (newTotalCost / newGross).coerceIn(0.0, 1_000_000.0) else item.unitCost
                        logDelta = diff
                        entryType = LedgerEntryType.PURCHASE
                        entryCost = costAddition.coerceIn(0.0, 10_000_000.0)
                        defaultReason = "Physical stock count (+)"
                    } else if (diff < -0.0001) {
                        val decrease = -diff
                        newConsumed = (item.consumedQuantity + decrease).coerceIn(0.0, 1_000_000.0)
                        logDelta = decrease
                        entryType = LedgerEntryType.USAGE
                        entryCost = (decrease * item.effectiveNetUnitCost).coerceIn(0.0, 10_000_000.0)
                        defaultReason = "Physical stock count (-)"
                    }
                }
            }

            val updates = mutableMapOf<String, Any>(
                "grossQuantity" to newGross,
                "consumedQuantity" to newConsumed,
                "totalCost" to newTotalCost,
                "unitCost" to newUnitCost,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (newMinStockLevel != null) {
                updates["minStockLevel"] = newMinStockLevel.coerceIn(0.0, 1_000_000.0)
            }

            val batch = db.batch()
            batch.update(itemRef, updates)

            if (logDelta > 0.0001) {
                val entryRef = workspaceDoc(uid).collection("ledger_entries").document()
                val entry = LedgerEntry(
                    userId = uid,
                    itemId = item.id,
                    itemNameEn = item.nameEn,
                    itemNameAr = item.nameAr,
                    rawScannedLabel = item.nameEn,
                    entryType = entryType.id,
                    category = item.category,
                    unit = item.unit,
                    grossDelta = logDelta,
                    wasteDelta = 0.0,
                    costAmount = entryCost,
                    wasteCostAmount = 0.0,
                    loggedByRole = loggedByRole.id,
                    hourOfDay = currentHourOfDay(),
                    reason = reason.trim().ifBlank { defaultReason },
                    staffName = staffName
                )
                batch.set(entryRef, entry.toCreateMap(uid))
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteInventoryItem(itemId: String, workspaceOwnerId: String? = null): Result<Unit> {
        var docPath = "users/unauthenticated/inventory/$itemId"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val ref = workspaceDoc(uid).collection("inventory").document(itemId)
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

    suspend fun saveFlaggedScanItem(
        line: ScannedInvoiceLine,
        sourceNote: String? = null,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/flagged_scans"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val ref = workspaceDoc(uid).collection("flagged_scans").document()
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
        staffName: String?,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/flagged_scans/${flagged.id}"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
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
                    rawScannedLabel = flagged.rawLabel,
                    workspaceOwnerId = uid
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
                    rawScannedLabel = flagged.rawLabel,
                    workspaceOwnerId = uid
                )
            }

            val resolvedId = itemIdResult.getOrThrow()
            val flagRef = workspaceDoc(uid).collection("flagged_scans").document(flagged.id)
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

    suspend fun dismissFlaggedScan(flaggedId: String, workspaceOwnerId: String? = null): Result<Unit> {
        var docPath = "users/unauthenticated/flagged_scans/$flaggedId"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val ref = workspaceDoc(uid).collection("flagged_scans").document(flaggedId)
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
        recipeType: RecipeType = RecipeType.MENU_ITEM,
        servings: Int,
        yieldQuantity: Double = 1.0,
        yieldUnit: UnitType = UnitType.G,
        menuPrice: Double,
        targetFoodCostPct: Double,
        posSku: String?,
        category: String?,
        lines: List<RecipeIngredientLine>,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/recipes"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            require(nameEn.trim().isNotEmpty()) { "Recipe name is required" }
            val safeYieldQty = yieldQuantity.coerceIn(0.001, 1_000_000.0)
            val safeMenuPrice = if (recipeType == RecipeType.SUB_RECIPE) 0.0 else menuPrice.coerceIn(0.0, 100_000.0)
            val collection = workspaceDoc(uid).collection("recipes")
            if (existingId.isNullOrBlank()) {
                val ref = collection.document()
                docPath = ref.path
                val recipe = Recipe(
                    userId = uid,
                    nameEn = nameEn,
                    nameAr = nameAr,
                    recipeType = recipeType.id,
                    servings = servings,
                    yieldQuantity = safeYieldQty,
                    yieldUnit = yieldUnit.symbol,
                    menuPrice = safeMenuPrice,
                    targetFoodCostPct = targetFoodCostPct,
                    posSku = if (recipeType == RecipeType.SUB_RECIPE) null else posSku,
                    category = category
                )
                ref.set(recipe.toCreateMap(uid, lines)).await()
            } else {
                val ref = collection.document(existingId)
                docPath = ref.path
                val updates = mutableMapOf<String, Any>(
                    "nameEn" to nameEn.trim().take(100),
                    "nameAr" to nameAr.trim().take(100),
                    "recipeType" to recipeType.id,
                    "servings" to servings.coerceIn(1, 100),
                    "yieldQuantity" to safeYieldQty,
                    "yieldUnit" to yieldUnit.symbol,
                    "menuPrice" to safeMenuPrice,
                    "targetFoodCostPct" to targetFoodCostPct.coerceIn(5.0, 95.0),
                    "ingredients" to lines.take(30).map { it.toMap() },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (recipeType != RecipeType.SUB_RECIPE && !posSku.isNullOrBlank()) {
                    updates["posSku"] = posSku.trim().take(64)
                }
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

    suspend fun deleteRecipe(recipeId: String, workspaceOwnerId: String? = null): Result<Unit> {
        var docPath = "users/unauthenticated/recipes/$recipeId"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val ref = workspaceDoc(uid).collection("recipes").document(recipeId)
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

    /**
     * Imports a full bundled POS recipe book (raw POS ingredients, production sub-recipes,
     * and menu dishes) idempotently into the active workspace using chunked Firestore WriteBatches.
     * Existing inventory items and recipes with matching normalized POS names are preserved and linked.
     */
    suspend fun importBundledRecipeBook(
        book: BundledRecipeBook,
        existingInventory: List<InventoryItem>,
        existingRecipes: List<Recipe>,
        targetFoodCostPct: Double,
        workspaceOwnerId: String? = null
    ): Result<BundledBookImportResult> {
        var docPath = "users/unauthenticated/recipes"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val rootDoc = workspaceDoc(uid)
            val invCol = rootDoc.collection("inventory")
            val recipeCol = rootDoc.collection("recipes")
            docPath = recipeCol.path

            val safeTargetFoodCost = targetFoodCostPct.coerceIn(5.0, 95.0)
            val ops = mutableListOf<(WriteBatch) -> Unit>()

            var createdInventory = 0
            var createdSubRecipes = 0
            var createdMenuDishes = 0
            var skippedExisting = 0

            // Pass 1: Resolve or create raw POS inventory items (respecting exact POS names)
            val invIdByNorm = mutableMapOf<String, String>()
            for (item in existingInventory) {
                val nKey = BarrioRecipeBookImporter.normalizePosKey(item.canonicalKey)
                val nName = BarrioRecipeBookImporter.normalizePosKey(item.nameEn)
                if (nKey.isNotEmpty()) invIdByNorm.putIfAbsent(nKey, item.id)
                if (nName.isNotEmpty()) invIdByNorm.putIfAbsent(nName, item.id)
                item.aliases.forEach { alias ->
                    val na = BarrioRecipeBookImporter.normalizePosKey(alias)
                    if (na.isNotEmpty()) invIdByNorm.putIfAbsent(na, item.id)
                }
            }

            for (spec in book.inventoryItems) {
                val normName = BarrioRecipeBookImporter.normalizePosKey(spec.name)
                val normKey = BarrioRecipeBookImporter.normalizePosKey(spec.canonicalKey)
                if (invIdByNorm.containsKey(normName) || invIdByNorm.containsKey(normKey)) {
                    skippedExisting++
                    continue
                }
                val ref = invCol.document()
                if (normName.isNotEmpty()) invIdByNorm[normName] = ref.id
                if (normKey.isNotEmpty()) invIdByNorm[normKey] = ref.id

                val newItem = InventoryItem(
                    userId = uid,
                    canonicalKey = spec.canonicalKey.take(80),
                    nameEn = spec.name.take(100),
                    nameAr = "",
                    aliases = listOf(spec.name.take(80)),
                    category = spec.category.id,
                    unit = spec.unit.symbol,
                    grossQuantity = 0.0,
                    wasteQuantity = 0.0,
                    consumedQuantity = 0.0,
                    totalCost = 0.0,
                    unitCost = 0.0,
                    minStockLevel = 0.0,
                    supplierName = "Omega POS"
                )
                val createMap = newItem.toCreateMap(uid)
                ops.add { batch -> batch.set(ref, createMap) }
                createdInventory++
            }

            // Pass 2: Pre-allocate IDs for all production sub-recipes (supports nested sub-recipes)
            val subIdByNorm = mutableMapOf<String, String>()
            for (existing in existingRecipes) {
                if (existing.recipeTypeEnum == RecipeType.SUB_RECIPE) {
                    val nk = BarrioRecipeBookImporter.normalizePosKey(existing.nameEn)
                    if (nk.isNotEmpty()) subIdByNorm.putIfAbsent(nk, existing.id)
                }
            }

            val pendingSubs = mutableListOf<Pair<DocumentReference, BundledSubRecipeSpec>>()
            for (sub in book.subRecipes) {
                val nk = BarrioRecipeBookImporter.normalizePosKey(sub.name)
                if (subIdByNorm.containsKey(nk)) {
                    skippedExisting++
                    continue
                }
                val ref = recipeCol.document()
                if (nk.isNotEmpty()) subIdByNorm[nk] = ref.id
                pendingSubs.add(ref to sub)
            }

            fun resolveLines(specs: List<BundledIngredientLineSpec>): List<RecipeIngredientLine> =
                specs.take(30).map { line ->
                    val lk = BarrioRecipeBookImporter.normalizePosKey(line.name)
                    if (line.source == RecipeLineSource.SUB_RECIPE) {
                        val subId = subIdByNorm[lk]
                        if (subId != null) {
                            RecipeIngredientLine(
                                itemId = subId,
                                itemName = line.name.take(100),
                                quantity = line.qty,
                                unit = line.unit.symbol,
                                sourceType = RecipeLineSource.SUB_RECIPE.id
                            )
                        } else {
                            val fallbackInv = invIdByNorm[lk] ?: "unlinked_$lk"
                            RecipeIngredientLine(
                                itemId = fallbackInv,
                                itemName = line.name.take(100),
                                quantity = line.qty,
                                unit = line.unit.symbol,
                                sourceType = RecipeLineSource.INVENTORY.id
                            )
                        }
                    } else {
                        val invId = invIdByNorm[lk] ?: "unlinked_$lk"
                        RecipeIngredientLine(
                            itemId = invId,
                            itemName = line.name.take(100),
                            quantity = line.qty,
                            unit = line.unit.symbol,
                            sourceType = RecipeLineSource.INVENTORY.id
                        )
                    }
                }

            // Pass 3: Queue production sub-recipe writes
            for ((ref, sub) in pendingSubs) {
                val lines = resolveLines(sub.ingredients)
                val recipe = Recipe(
                    userId = uid,
                    nameEn = sub.name.take(100),
                    nameAr = "",
                    recipeType = RecipeType.SUB_RECIPE.id,
                    servings = 1,
                    yieldQuantity = sub.yieldQty.coerceIn(0.001, 1_000_000.0),
                    yieldUnit = sub.yieldUnit.symbol,
                    productionStock = 0.0,
                    minProductionStock = 0.0,
                    menuPrice = 0.0,
                    targetFoodCostPct = safeTargetFoodCost,
                    posSku = null,
                    category = "Production Prep"
                )
                val createMap = recipe.toCreateMap(uid, lines)
                ops.add { batch -> batch.set(ref, createMap) }
                createdSubRecipes++
            }

            // Pass 4: Queue menu dish writes
            val existingMenuKeys = existingRecipes
                .filter { it.recipeTypeEnum == RecipeType.MENU_ITEM }
                .map { BarrioRecipeBookImporter.normalizePosKey(it.nameEn) }
                .toHashSet()

            for (dish in book.menuDishes) {
                val nk = BarrioRecipeBookImporter.normalizePosKey(dish.name)
                if (nk in existingMenuKeys) {
                    skippedExisting++
                    continue
                }
                existingMenuKeys.add(nk)
                val ref = recipeCol.document()
                val lines = resolveLines(dish.ingredients)
                val recipe = Recipe(
                    userId = uid,
                    nameEn = dish.name.take(100),
                    nameAr = "",
                    recipeType = RecipeType.MENU_ITEM.id,
                    servings = 1,
                    yieldQuantity = 1.0,
                    yieldUnit = UnitType.PCS.symbol,
                    menuPrice = 0.0,
                    targetFoodCostPct = safeTargetFoodCost,
                    posSku = dish.posSku.take(64),
                    category = dish.category.take(50)
                )
                val createMap = recipe.toCreateMap(uid, lines)
                ops.add { batch -> batch.set(ref, createMap) }
                createdMenuDishes++
            }

            // Commit in safe chunks of 200 operations per Firestore WriteBatch (limit is 500)
            ops.chunked(200).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { op -> op(batch) }
                batch.commit().await()
            }

            Result.success(
                BundledBookImportResult(
                    createdInventory = createdInventory,
                    createdSubRecipes = createdSubRecipes,
                    createdMenuDishes = createdMenuDishes,
                    skippedExisting = skippedExisting
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    // --- Production Batch & Sub-Recipe Stock Management ---

    /**
     * Logs a newly prepared production batch of a sub-recipe (e.g. Garlic Sauce, Marinade, Stock).
     * Adds the produced yield to [subRecipe.productionStock] and optionally deducts all raw
     * ingredients (flattening nested sub-recipes) from raw kitchen stock in a single atomic batch.
     */
    suspend fun produceSubRecipeBatch(
        subRecipe: Recipe,
        producedQuantityInYieldUnit: Double,
        deductRawIngredients: Boolean,
        inventory: List<InventoryItem>,
        allRecipes: List<Recipe>,
        loggedByRole: StaffRole,
        staffName: String?,
        workspaceOwnerId: String? = null
    ): Result<Int> {
        var docPath = "users/unauthenticated/recipes/${subRecipe.id}"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            require(subRecipe.id.isNotBlank()) { "Production recipe ID required" }
            require(producedQuantityInYieldUnit > 0.0) { "Production quantity must be positive" }

            val recipeRef = workspaceDoc(uid).collection("recipes").document(subRecipe.id)
            docPath = recipeRef.path
            val batch = db.batch()

            val newProductionStock = (subRecipe.productionStock + producedQuantityInYieldUnit)
                .coerceIn(0.0, 1_000_000.0)
            batch.update(
                recipeRef,
                mapOf(
                    "productionStock" to newProductionStock,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            var deductedRawCount = 0
            if (deductRawIngredients) {
                val baseYield = subRecipe.yieldQuantity.coerceAtLeast(0.001)
                val batchMultiplier = producedQuantityInYieldUnit / baseYield
                val inventoryById = inventory.associateBy { it.id }
                val recipesById = allRecipes.associateBy { it.id }
                val rawConsumption = mutableMapOf<String, Double>()

                RecipeCostEngine.flattenRawInventoryUsage(
                    recipe = subRecipe,
                    multiplier = batchMultiplier,
                    inventoryById = inventoryById,
                    recipesById = recipesById,
                    output = rawConsumption
                )

                for ((itemId, consumedDelta) in rawConsumption) {
                    val invItem = inventoryById[itemId] ?: continue
                    if (consumedDelta <= 0.0001) continue
                    val itemRef = workspaceDoc(uid).collection("inventory").document(itemId)
                    val newConsumed = (invItem.consumedQuantity + consumedDelta).coerceIn(0.0, 1_000_000.0)
                    batch.update(
                        itemRef,
                        mapOf(
                            "consumedQuantity" to newConsumed,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )

                    val entryRef = workspaceDoc(uid).collection("ledger_entries").document()
                    val entry = LedgerEntry(
                        userId = uid,
                        itemId = invItem.id,
                        itemNameEn = invItem.nameEn,
                        itemNameAr = invItem.nameAr,
                        rawScannedLabel = "PREP:${subRecipe.nameEn}".take(120),
                        entryType = LedgerEntryType.USAGE.id,
                        category = invItem.category,
                        unit = invItem.unit,
                        grossDelta = consumedDelta,
                        wasteDelta = 0.0,
                        costAmount = (consumedDelta * invItem.effectiveNetUnitCost).coerceIn(0.0, 10_000_000.0),
                        wasteCostAmount = 0.0,
                        loggedByRole = loggedByRole.id,
                        hourOfDay = currentHourOfDay(),
                        reason = "Production batch: ${subRecipe.nameEn}",
                        staffName = staffName
                    )
                    batch.set(entryRef, entry.toCreateMap(uid))
                    deductedRawCount++
                }
            }

            batch.commit().await()
            Result.success(deductedRawCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /** Adjusts or sets exact prepared stock on hand for a production sub-recipe. */
    suspend fun adjustProductionStock(
        subRecipe: Recipe,
        mode: StockAdjustMode,
        amountInInputUnit: Double,
        inputUnit: UnitType,
        newMinProductionStock: Double? = null,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/recipes/${subRecipe.id}"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            require(subRecipe.id.isNotBlank()) { "Production recipe ID required" }
            val converted = UnitType.convert(amountInInputUnit.coerceAtLeast(0.0), inputUnit, subRecipe.yieldUnitEnum)
            val newStock = when (mode) {
                StockAdjustMode.ADD -> (subRecipe.productionStock + converted).coerceIn(0.0, 1_000_000.0)
                StockAdjustMode.DEDUCT -> (subRecipe.productionStock - converted).coerceIn(0.0, 1_000_000.0)
                StockAdjustMode.SET_EXACT -> converted.coerceIn(0.0, 1_000_000.0)
            }
            val ref = workspaceDoc(uid).collection("recipes").document(subRecipe.id)
            docPath = ref.path
            val updates = mutableMapOf<String, Any>(
                "productionStock" to newStock,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (newMinProductionStock != null) {
                updates["minProductionStock"] = newMinProductionStock.coerceIn(0.0, 1_000_000.0)
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

    // --- POS Sales Deduction & Sync Audit ---

    suspend fun applyPosSalesDeductions(
        provider: PosProvider,
        syncMode: String,
        soldRecipes: List<Pair<Recipe, Int>>,
        inventory: List<InventoryItem>,
        allRecipes: List<Recipe> = emptyList(),
        loggedByRole: StaffRole,
        staffName: String?,
        workspaceOwnerId: String? = null
    ): Result<PosSyncRecord> {
        var docPath = "users/unauthenticated/pos_syncs"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val batch = db.batch()

            // Accumulate consumption across sold plates:
            // When a dish uses a sub-recipe that has prepared productionStock on hand, deduct from
            // productionStock first (raw ingredients were already deducted when the batch was cooked),
            // and fall back to raw stock only for any remaining shortfall.
            val inventoryById = inventory.associateBy { it.id }
            val recipesById = allRecipes.associateBy { it.id }
            val consumptionByItemId = mutableMapOf<String, Double>()
            val remainingProdStock = allRecipes
                .filter { it.recipeTypeEnum == RecipeType.SUB_RECIPE }
                .associate { it.id to it.productionStock }
                .toMutableMap()
            val touchedSubRecipeIds = mutableSetOf<String>()

            var totalRevenue = 0.0
            var totalOrders = 0

            for ((recipe, platesSold) in soldRecipes) {
                if (platesSold <= 0) continue
                totalOrders += platesSold
                totalRevenue += recipe.menuPrice * platesSold
                val servingsMultiplier = platesSold.toDouble() / max(1, recipe.servings).toDouble()

                for (line in recipe.parsedLines()) {
                    if (line.sourceEnum == RecipeLineSource.SUB_RECIPE) {
                        val sub = recipesById[line.itemId] ?: continue
                        val neededInYieldUnit = UnitType.convert(
                            line.quantity * servingsMultiplier,
                            UnitType.fromSymbol(line.unit),
                            sub.yieldUnitEnum
                        )
                        val availableProd = remainingProdStock[sub.id] ?: 0.0
                        val takenFromProd = minOf(availableProd, neededInYieldUnit)
                        if (takenFromProd > 0.0001) {
                            remainingProdStock[sub.id] = (availableProd - takenFromProd).coerceAtLeast(0.0)
                            touchedSubRecipeIds.add(sub.id)
                        }
                        val shortfall = (neededInYieldUnit - takenFromProd).coerceAtLeast(0.0)
                        if (shortfall > 0.0001) {
                            val subBatchMult = shortfall / sub.yieldQuantity.coerceAtLeast(0.001)
                            RecipeCostEngine.flattenRawInventoryUsage(
                                recipe = sub,
                                multiplier = subBatchMult,
                                inventoryById = inventoryById,
                                recipesById = recipesById,
                                output = consumptionByItemId
                            )
                        }
                    } else {
                        val invItem = inventoryById[line.itemId] ?: continue
                        val qtyInInvUnit = UnitType.convert(
                            line.quantity * servingsMultiplier,
                            UnitType.fromSymbol(line.unit),
                            invItem.unitEnum
                        )
                        consumptionByItemId[invItem.id] = (consumptionByItemId[invItem.id] ?: 0.0) + qtyInInvUnit
                    }
                }
            }

            // Update touched sub-recipe production stock counts
            for (subId in touchedSubRecipeIds) {
                val updatedProd = (remainingProdStock[subId] ?: 0.0).coerceIn(0.0, 1_000_000.0)
                val subRef = workspaceDoc(uid).collection("recipes").document(subId)
                batch.update(
                    subRef,
                    mapOf(
                        "productionStock" to updatedProd,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            for ((itemId, consumedDelta) in consumptionByItemId) {
                val invItem = inventory.firstOrNull { it.id == itemId } ?: continue
                val itemRef = workspaceDoc(uid).collection("inventory").document(itemId)
                val newConsumed = (invItem.consumedQuantity + consumedDelta).coerceIn(0.0, 1_000_000.0)
                batch.update(
                    itemRef,
                    mapOf(
                        "consumedQuantity" to newConsumed,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                val ledgerRef = workspaceDoc(uid).collection("ledger_entries").document()
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

            val syncRef = workspaceDoc(uid).collection("pos_syncs").document()
            docPath = syncRef.path
            val totalDeductedCount = consumptionByItemId.size + touchedSubRecipeIds.size
            val summaryText = buildString {
                append("$totalOrders plates synced across ${consumptionByItemId.size} raw items")
                if (touchedSubRecipeIds.isNotEmpty()) {
                    append(" & ${touchedSubRecipeIds.size} prep items")
                }
            }
            val record = PosSyncRecord(
                userId = uid,
                provider = provider.displayName.take(40),
                syncMode = syncMode,
                ordersCount = totalOrders,
                totalRevenue = totalRevenue.coerceIn(0.0, 100_000_000.0),
                deductedItemsCount = totalDeductedCount,
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
        member: StaffMember,
        workspaceOwnerId: String? = null
    ): Result<Unit> {
        var docPath = "users/unauthenticated/staff_roles"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val col = workspaceDoc(uid).collection("staff_roles")
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

    suspend fun deleteStaffMember(staffId: String, workspaceOwnerId: String? = null): Result<Unit> {
        var docPath = "users/unauthenticated/staff_roles/$staffId"
        return try {
            val uid = resolveWorkspaceOwnerId(workspaceOwnerId)
            val ref = workspaceDoc(uid).collection("staff_roles").document(staffId)
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
