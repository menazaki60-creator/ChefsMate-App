package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CsvExportService
import com.example.data.CsvPeriod
import com.example.data.InvoiceScannerService
import com.example.data.KitchenRepository
import com.example.data.LocalAppPreferences
import com.example.data.PosSyncService
import com.example.data.PreferencesRepository
import com.example.domain.PlateProfitabilityReport
import com.example.domain.RecipeCostEngine
import com.example.model.ActiveRolePermissions
import com.example.model.AppLanguage
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
import com.example.notifications.KitchenAlertNotifier
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val VM_TAG = "KitchenViewModel"

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Scanning : ScanUiState
    data class Results(val lines: List<ScannedInvoiceLine>) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

data class DailyTrendPoint(
    val dayLabel: String,
    val costAmount: Double,
    val wasteCostAmount: Double
)

data class HourlyBurnPoint(
    val hour: Int,
    val label: String,
    val totalUsageCost: Double,
    val wasteCost: Double
)

data class UrgentAlert(
    val id: String,
    val title: String,
    val detail: String,
    val severity: String // "CRITICAL" or "WARNING"
)

data class KitchenUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
    val profile: KitchenProfile? = null,
    val inventory: List<InventoryItem> = emptyList(),
    val ledgerEntries: List<LedgerEntry> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val recipeReports: Map<String, PlateProfitabilityReport> = emptyMap(),
    val flaggedScans: List<FlaggedScanItem> = emptyList(),
    val pendingFlaggedScans: List<FlaggedScanItem> = emptyList(),
    val staffMembers: List<StaffMember> = emptyList(),
    val posSyncs: List<PosSyncRecord> = emptyList(),
    val syncStatus: SyncStatus = SyncStatus(),
    val preferences: LocalAppPreferences = LocalAppPreferences(),
    val activePermissions: ActiveRolePermissions = ActiveRolePermissions(),
    val urgentAlerts: List<UrgentAlert> = emptyList(),
    val dailyTrends: List<DailyTrendPoint> = emptyList(),
    val hourlyBurn: List<HourlyBurnPoint> = emptyList(),
    val totalInventoryValue: Double = 0.0,
    val totalWasteCost: Double = 0.0,
    val overallNetYieldPct: Double = 100.0,
    val averageRecipeMarginPct: Double = 0.0,
    val scanState: ScanUiState = ScanUiState.Idle,
    val posSyncBusy: Boolean = false
)

class KitchenViewModel(
    private val repository: KitchenRepository,
    private val preferencesRepository: PreferencesRepository,
    private val scannerService: InvoiceScannerService,
    private val posSyncService: PosSyncService,
    private val currentUserId: String,
    private val currentUserName: String,
    private val appContext: Context
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _posSyncBusy = MutableStateFlow(false)
    private val _streamError = MutableStateFlow<String?>(null)

    private fun <T> Flow<T>.safeCatch(fallback: T, label: String): Flow<T> = catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(VM_TAG, "Error observing $label", root)
        _streamError.value = root.message ?: "Failed to load $label"
        emit(fallback)
    }

    private val coreDataFlow = combine(
        repository.observeProfile(currentUserId).safeCatch(null, "profile"),
        repository.observeInventory(currentUserId)
            .map { it.data to it.syncStatus }
            .safeCatch(emptyList<InventoryItem>() to SyncStatus(), "inventory"),
        repository.observeLedgerEntries(currentUserId).safeCatch(emptyList(), "ledger"),
        repository.observeRecipes(currentUserId).safeCatch(emptyList(), "recipes"),
        repository.observeFlaggedScans(currentUserId).safeCatch(emptyList(), "flagged_scans")
    ) { profile, invPair, ledger, recipes, flagged ->
        CoreBundle(profile, invPair.first, invPair.second, ledger, recipes, flagged)
    }

    private val opsDataFlow = combine(
        repository.observeStaff(currentUserId).safeCatch(emptyList(), "staff"),
        repository.observePosSyncs(currentUserId).safeCatch(emptyList(), "pos_syncs"),
        preferencesRepository.preferencesFlow.catch { emit(LocalAppPreferences()) },
        _scanState,
        _snackbarMessage
    ) { staff, posSyncs, prefs, scanState, snackbar ->
        OpsBundle(staff, posSyncs, prefs, scanState, snackbar)
    }

    val uiState: StateFlow<KitchenUiState> = combine(
        coreDataFlow,
        opsDataFlow,
        _posSyncBusy,
        _streamError
    ) { core, ops, posBusy, streamErr ->
        val inventory = core.inventory
        val recipes = core.recipes
        val ledger = core.ledger
        val pendingFlags = core.flagged.filter { it.status == FlagStatus.PENDING.id }

        val recipeReports = recipes.associate { recipe ->
            recipe.id to RecipeCostEngine.evaluateRecipe(recipe, inventory)
        }

        val totalValue = inventory.sumOf { it.totalCost }
        val totalWaste = inventory.sumOf { it.wasteCost }
        val totalGross = inventory.sumOf { it.grossQuantity }
        val totalWasteQty = inventory.sumOf { it.wasteQuantity }
        val netYieldPct = if (totalGross > 0.0001) {
            ((totalGross - totalWasteQty).coerceAtLeast(0.0) / totalGross) * 100.0
        } else 100.0

        val avgMargin = if (recipeReports.isNotEmpty()) {
            recipeReports.values.map { it.actualProfitMarginPercent }.average()
        } else 0.0

        val alerts = buildUrgentAlerts(inventory, pendingFlags, core.profile?.currencySymbol ?: "$")
        if (core.profile?.lowStockAlertsEnabled != false) {
            dispatchNotifications(alerts)
        }

        val activePerms = resolveActivePermissions(ops.prefs, ops.staff, core.profile?.displayName ?: currentUserName)

        KitchenUiState(
            loading = false,
            errorMessage = streamErr,
            snackbarMessage = ops.snackbar,
            profile = core.profile,
            inventory = inventory,
            ledgerEntries = ledger,
            recipes = recipes,
            recipeReports = recipeReports,
            flaggedScans = core.flagged,
            pendingFlaggedScans = pendingFlags,
            staffMembers = ops.staff,
            posSyncs = ops.posSyncs,
            syncStatus = core.syncStatus,
            preferences = ops.prefs,
            activePermissions = activePerms,
            urgentAlerts = alerts,
            dailyTrends = computeDailyTrends(ledger),
            hourlyBurn = computeHourlyBurn(ledger),
            totalInventoryValue = totalValue,
            totalWasteCost = totalWaste,
            overallNetYieldPct = netYieldPct,
            averageRecipeMarginPct = avgMargin,
            scanState = ops.scanState,
            posSyncBusy = posBusy
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = KitchenUiState(loading = true)
    )

    init {
        viewModelScope.launch {
            repository.ensureKitchenProfile(currentUserName)
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun clearStreamError() {
        _streamError.value = null
    }

    // --- Preferences ---

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setDarkMode(enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { preferencesRepository.setLanguage(language) }
    }

    fun switchTerminalRole(role: StaffRole, staffId: String = "") {
        viewModelScope.launch {
            preferencesRepository.setActiveTerminalRole(role, staffId)
            _snackbarMessage.value = "Switched terminal role to ${role.name.replace('_', ' ')}"
        }
    }

    fun verifyManagerPin(pin: String): Boolean {
        val storedHash = uiState.value.profile?.managerPinHash.orEmpty()
        if (storedHash.isBlank()) return true
        return sha256(pin.trim()) == storedHash
    }

    fun setManagerPin(pin: String) {
        val profile = uiState.value.profile ?: return
        viewModelScope.launch {
            val hash = if (pin.isBlank()) "" else sha256(pin.trim())
            repository.updateKitchenProfile(
                kitchenName = profile.kitchenName,
                currencySymbol = profile.currencySymbol,
                targetFoodCostPct = profile.targetFoodCostPct,
                lowStockAlertsEnabled = profile.lowStockAlertsEnabled,
                posProvider = PosProvider.fromRaw(profile.posProvider),
                posEndpointUrl = profile.posEndpointUrl,
                managerPinHash = hash,
                updatePin = true
            ).onSuccess {
                _snackbarMessage.value = if (pin.isBlank()) "Manager PIN cleared" else "Manager PIN saved"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to save Manager PIN"
            }
        }
    }

    // --- Inventory & Waste Mutations ---

    fun addOrMergeItem(
        name: String,
        arabicName: String,
        category: IngredientCategory,
        quantity: Double,
        unit: UnitType,
        totalCost: Double,
        wasteQty: Double,
        wasteUnit: UnitType,
        minStockLevel: Double,
        supplierName: String
    ) {
        val state = uiState.value
        viewModelScope.launch {
            repository.addOrMergeInventoryItem(
                rawName = name,
                arabicNameInput = arabicName,
                category = category,
                quantity = quantity,
                unit = unit,
                totalCost = totalCost,
                wasteQuantityInWasteUnit = wasteQty,
                wasteUnit = wasteUnit,
                minStockLevel = minStockLevel,
                supplierName = supplierName,
                existingInventory = state.inventory,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName
            ).onSuccess {
                _snackbarMessage.value = "Saved & synced to cloud ledger"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to save item"
            }
        }
    }

    fun logWaste(item: InventoryItem, wasteQty: Double, wasteUnit: UnitType, reason: String) {
        val perms = uiState.value.activePermissions
        viewModelScope.launch {
            repository.logWasteForItem(
                item = item,
                wasteAmount = wasteQty,
                wasteUnit = wasteUnit,
                reason = reason,
                loggedByRole = perms.role,
                staffName = perms.activeStaffName
            ).onSuccess {
                _snackbarMessage.value = "Waste logged for ${item.nameEn}"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to log waste"
            }
        }
    }

    fun updateMinStock(itemId: String, newMin: Double) {
        viewModelScope.launch {
            repository.updateMinStockLevel(itemId, newMin)
                .onSuccess { _snackbarMessage.value = "Low-stock alert level updated" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Update failed" }
        }
    }

    fun deleteInventoryItem(itemId: String) {
        viewModelScope.launch {
            repository.deleteInventoryItem(itemId)
                .onSuccess { _snackbarMessage.value = "Ingredient removed" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Delete failed" }
        }
    }

    // --- Bilingual AI Invoice & Label Scanner ---

    fun scanInvoiceImage(bitmap: Bitmap) {
        val existing = uiState.value.inventory
        viewModelScope.launch {
            _scanState.value = ScanUiState.Scanning
            scannerService.scanInvoiceBitmap(bitmap, existing)
                .onSuccess { lines ->
                    if (lines.isEmpty()) {
                        _scanState.value = ScanUiState.Error("No food items detected. Try a clearer photo or paste invoice text.")
                    } else {
                        _scanState.value = ScanUiState.Results(lines)
                    }
                }
                .onFailure { e ->
                    _scanState.value = ScanUiState.Error(e.localizedMessage ?: "AI invoice scan failed")
                }
        }
    }

    fun scanInvoiceText(rawText: String) {
        val existing = uiState.value.inventory
        viewModelScope.launch {
            _scanState.value = ScanUiState.Scanning
            scannerService.scanInvoiceText(rawText, existing)
                .onSuccess { lines ->
                    if (lines.isEmpty()) {
                        _scanState.value = ScanUiState.Error("No ingredient line items found in text.")
                    } else {
                        _scanState.value = ScanUiState.Results(lines)
                    }
                }
                .onFailure { e ->
                    _scanState.value = ScanUiState.Error(e.localizedMessage ?: "AI scan failed")
                }
        }
    }

    fun commitScannedLines(lines: List<ScannedInvoiceLine>) {
        val state = uiState.value
        viewModelScope.launch {
            var addedCount = 0
            var flaggedCount = 0
            var workingInventory = state.inventory
            for (line in lines) {
                if (line.needsManualReview) {
                    repository.saveFlaggedScanItem(line, sourceNote = "AI Bilingual Scanner")
                    flaggedCount++
                } else {
                    val res = repository.addOrMergeInventoryItem(
                        rawName = line.nameEn.ifBlank { line.rawLabel },
                        arabicNameInput = line.nameAr,
                        category = line.category,
                        quantity = line.quantity,
                        unit = line.unit,
                        totalCost = line.totalCost,
                        wasteQuantityInWasteUnit = 0.0,
                        wasteUnit = line.unit,
                        minStockLevel = 0.5,
                        supplierName = "Scanned Invoice",
                        existingInventory = workingInventory,
                        loggedByRole = state.activePermissions.role,
                        staffName = state.activePermissions.activeStaffName,
                        entryType = LedgerEntryType.INVOICE_SCAN,
                        rawScannedLabel = line.rawLabel
                    )
                    if (res.isSuccess) {
                        addedCount++
                        // Refresh local snapshot list for subsequent lines in the same invoice
                        workingInventory = uiState.value.inventory
                    }
                }
            }
            _scanState.value = ScanUiState.Idle
            _snackbarMessage.value = "Imported $addedCount item(s), flagged $flaggedCount for manual review"
        }
    }

    fun resetScanState() {
        _scanState.value = ScanUiState.Idle
    }

    fun approveFlaggedScan(
        flagged: FlaggedScanItem,
        nameEn: String,
        nameAr: String,
        category: IngredientCategory,
        linkToExisting: InventoryItem?
    ) {
        val state = uiState.value
        viewModelScope.launch {
            repository.approveFlaggedScan(
                flagged = flagged,
                targetNameEn = nameEn,
                targetNameAr = nameAr,
                category = category,
                linkToExisting = linkToExisting,
                existingInventory = state.inventory,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName
            ).onSuccess {
                _snackbarMessage.value = "Reviewed & merged into inventory"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Approval failed"
            }
        }
    }

    fun dismissFlaggedScan(flaggedId: String) {
        viewModelScope.launch {
            repository.dismissFlaggedScan(flaggedId)
                .onSuccess { _snackbarMessage.value = "Flagged item dismissed" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Dismiss failed" }
        }
    }

    // --- Recipes ---

    fun saveRecipe(
        existingId: String?,
        nameEn: String,
        nameAr: String,
        servings: Int,
        menuPrice: Double,
        desiredProfitMarginPct: Double,
        posSku: String,
        category: String,
        lines: List<RecipeIngredientLine>
    ) {
        val targetFoodCostPct = (100.0 - desiredProfitMarginPct).coerceIn(5.0, 95.0)
        viewModelScope.launch {
            repository.saveRecipe(
                existingId = existingId,
                nameEn = nameEn,
                nameAr = nameAr,
                servings = servings,
                menuPrice = menuPrice,
                targetFoodCostPct = targetFoodCostPct,
                posSku = posSku,
                category = category,
                lines = lines
            ).onSuccess {
                _snackbarMessage.value = "Recipe saved"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to save recipe"
            }
        }
    }

    fun deleteRecipe(recipeId: String) {
        viewModelScope.launch {
            repository.deleteRecipe(recipeId)
                .onSuccess { _snackbarMessage.value = "Recipe deleted" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Delete failed" }
        }
    }

    // --- POS Integration & Kitchen Settings ---

    fun saveKitchenSettings(
        kitchenName: String,
        currencySymbol: String,
        targetFoodCostPct: Double,
        lowStockAlertsEnabled: Boolean,
        posProvider: PosProvider,
        posEndpointUrl: String,
        posBearerToken: String
    ) {
        viewModelScope.launch {
            preferencesRepository.setPosApiToken(posBearerToken)
            repository.updateKitchenProfile(
                kitchenName = kitchenName,
                currencySymbol = currencySymbol,
                targetFoodCostPct = targetFoodCostPct,
                lowStockAlertsEnabled = lowStockAlertsEnabled,
                posProvider = posProvider,
                posEndpointUrl = posEndpointUrl
            ).onSuccess {
                _snackbarMessage.value = "Kitchen & POS settings saved"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to update settings"
            }
        }
    }

    fun syncLivePosEndpoint() {
        val state = uiState.value
        val endpoint = state.profile?.posEndpointUrl.orEmpty()
        if (endpoint.isBlank()) {
            _snackbarMessage.value = "Enter a live POS REST Endpoint URL first"
            return
        }
        viewModelScope.launch {
            _posSyncBusy.value = true
            val fetchResult = posSyncService.fetchLivePosSales(endpoint, state.preferences.posApiToken)
            fetchResult.onSuccess { sales ->
                val matched = posSyncService.matchSalesToRecipes(sales, state.recipes)
                if (matched.isEmpty()) {
                    _snackbarMessage.value = "POS reachable (${sales.size} sales), but no SKUs matched your recipes"
                } else {
                    repository.applyPosSalesDeductions(
                        provider = PosProvider.fromRaw(state.profile?.posProvider),
                        syncMode = "API_ENDPOINT",
                        soldRecipes = matched,
                        inventory = state.inventory,
                        loggedByRole = state.activePermissions.role,
                        staffName = state.activePermissions.activeStaffName
                    ).onSuccess { record ->
                        _snackbarMessage.value = "POS synced: ${record.summary}"
                    }.onFailure { err ->
                        _snackbarMessage.value = err.localizedMessage ?: "Failed to deduct POS inventory"
                    }
                }
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "POS endpoint sync failed"
            }
            _posSyncBusy.value = false
        }
    }

    fun recordPosShiftBatch(soldCountsByRecipeId: Map<String, Int>) {
        val state = uiState.value
        val pairs = soldCountsByRecipeId.mapNotNull { (id, count) ->
            if (count <= 0) null
            else state.recipes.firstOrNull { it.id == id }?.let { it to count }
        }
        if (pairs.isEmpty()) {
            _snackbarMessage.value = "Select at least one sold plate to deduct"
            return
        }
        viewModelScope.launch {
            _posSyncBusy.value = true
            repository.applyPosSalesDeductions(
                provider = PosProvider.fromRaw(state.profile?.posProvider).let {
                    if (it == PosProvider.NONE) PosProvider.CUSTOM else it
                },
                syncMode = "Z_REPORT_BATCH",
                soldRecipes = pairs,
                inventory = state.inventory,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName
            ).onSuccess { record ->
                _snackbarMessage.value = "Shift sales synced: ${record.summary}"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Shift deduction failed"
            }
            _posSyncBusy.value = false
        }
    }

    // --- Staff RBAC ---

    fun saveStaffMember(existingId: String?, member: StaffMember) {
        viewModelScope.launch {
            repository.saveStaffMember(existingId, member)
                .onSuccess { _snackbarMessage.value = "Staff permissions saved" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Failed to save staff" }
        }
    }

    fun deleteStaffMember(staffId: String) {
        viewModelScope.launch {
            repository.deleteStaffMember(staffId)
                .onSuccess { _snackbarMessage.value = "Staff member removed" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Delete failed" }
        }
    }

    // --- CSV Reporting ---

    fun generateCsv(period: CsvPeriod): String {
        val state = uiState.value
        return CsvExportService.buildReportCsv(
            period = period,
            inventory = state.inventory,
            entries = state.ledgerEntries,
            currencySymbol = state.profile?.currencySymbol ?: "$"
        )
    }

    // --- Internal Analytics & Alert Builders ---

    private fun resolveActivePermissions(
        prefs: LocalAppPreferences,
        staff: List<StaffMember>,
        ownerName: String
    ): ActiveRolePermissions {
        if (prefs.activeTerminalRole == StaffRole.MANAGER && prefs.activeStaffId.isBlank()) {
            return ActiveRolePermissions(
                role = StaffRole.MANAGER,
                activeStaffName = ownerName.ifBlank { "Manager" },
                canViewReports = true,
                canExportCsv = true,
                canApproveScans = true,
                canEditRecipes = true,
                canManagePos = true
            )
        }
        val matchedStaff = staff.firstOrNull { it.id == prefs.activeStaffId && it.active }
        if (matchedStaff != null) {
            return ActiveRolePermissions(
                role = matchedStaff.roleEnum,
                activeStaffName = matchedStaff.fullName,
                canViewReports = matchedStaff.canViewReports,
                canExportCsv = matchedStaff.canExportCsv,
                canApproveScans = matchedStaff.canApproveScans,
                canEditRecipes = matchedStaff.canEditRecipes,
                canManagePos = matchedStaff.canManagePos
            )
        }
        return when (prefs.activeTerminalRole) {
            StaffRole.MANAGER -> ActiveRolePermissions(
                role = StaffRole.MANAGER,
                activeStaffName = ownerName.ifBlank { "Manager" },
                canViewReports = true,
                canExportCsv = true,
                canApproveScans = true,
                canEditRecipes = true,
                canManagePos = true
            )
            StaffRole.SOUS_CHEF -> ActiveRolePermissions(
                role = StaffRole.SOUS_CHEF,
                activeStaffName = "Sous Chef",
                canViewReports = false,
                canExportCsv = false,
                canApproveScans = true,
                canEditRecipes = true,
                canManagePos = false
            )
            StaffRole.INVENTORY_CLERK -> ActiveRolePermissions(
                role = StaffRole.INVENTORY_CLERK,
                activeStaffName = "Inventory Clerk",
                canViewReports = false,
                canExportCsv = true,
                canApproveScans = true,
                canEditRecipes = false,
                canManagePos = false
            )
            StaffRole.LINE_COOK -> ActiveRolePermissions(
                role = StaffRole.LINE_COOK,
                activeStaffName = "Line Cook",
                canViewReports = false,
                canExportCsv = false,
                canApproveScans = false,
                canEditRecipes = false,
                canManagePos = false
            )
        }
    }

    private fun buildUrgentAlerts(
        inventory: List<InventoryItem>,
        pendingFlags: List<FlaggedScanItem>,
        currency: String
    ): List<UrgentAlert> {
        val list = mutableListOf<UrgentAlert>()
        inventory.filter { it.lowStock }.forEach { item ->
            list.add(
                UrgentAlert(
                    id = "low_${item.id}_${String.format(Locale.US, "%.2f", item.currentStock)}",
                    title = "Low Stock: ${item.nameEn}${if (item.nameAr.isNotBlank()) " (${item.nameAr})" else ""}",
                    detail = "${String.format(Locale.US, "%.2f", item.currentStock)} ${item.unit} left (min ${String.format(Locale.US, "%.2f", item.minStockLevel)} ${item.unit})",
                    severity = "CRITICAL"
                )
            )
        }
        inventory.filter { it.grossQuantity > 0.0 && it.wastePercent >= 15.0 }.forEach { item ->
            list.add(
                UrgentAlert(
                    id = "waste_${item.id}_${item.wastePercent.toInt()}",
                    title = "High Waste Spike: ${item.nameEn}",
                    detail = "${String.format(Locale.US, "%.1f", item.wastePercent)}% waste ($currency${String.format(Locale.US, "%.2f", item.wasteCost)} lost)",
                    severity = "WARNING"
                )
            )
        }
        if (pendingFlags.isNotEmpty()) {
            list.add(
                UrgentAlert(
                    id = "flag_count_${pendingFlags.size}",
                    title = "${pendingFlags.size} Invoice Item(s) Need Review",
                    detail = "Unrecognized bilingual invoice labels awaiting operator approval",
                    severity = "WARNING"
                )
            )
        }
        return list
    }

    private fun dispatchNotifications(alerts: List<UrgentAlert>) {
        alerts.take(3).forEach { alert ->
            KitchenAlertNotifier.notifyOnce(
                context = appContext,
                dedupKey = alert.id,
                title = alert.title,
                body = alert.detail
            )
        }
    }

    private fun computeDailyTrends(entries: List<LedgerEntry>): List<DailyTrendPoint> {
        val dayKeyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dayLabelFmt = SimpleDateFormat("EEE", Locale.US)
        val cal = Calendar.getInstance()
        val buckets = LinkedHashMap<String, Pair<String, MutableList<LedgerEntry>>>()

        for (daysAgo in 6 downTo 0) {
            val c = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
            val date = c.time
            buckets[dayKeyFmt.format(date)] = dayLabelFmt.format(date) to mutableListOf()
        }

        for (entry in entries) {
            val d = entry.createdAt?.toDate() ?: Date()
            val key = dayKeyFmt.format(d)
            buckets[key]?.second?.add(entry)
        }

        return buckets.values.map { (label, list) ->
            DailyTrendPoint(
                dayLabel = label,
                costAmount = list.sumOf { it.costAmount },
                wasteCostAmount = list.sumOf { it.wasteCostAmount }
            )
        }
    }

    private fun computeHourlyBurn(entries: List<LedgerEntry>): List<HourlyBurnPoint> {
        val peakSlots = listOf(
            8 to "8a", 11 to "11a", 13 to "1p", 16 to "4p", 19 to "7p", 21 to "9p"
        )
        return peakSlots.map { (startHour, label) ->
            val window = entries.filter { it.hourOfDay in startHour..(startHour + 2) }
            HourlyBurnPoint(
                hour = startHour,
                label = label,
                totalUsageCost = window.sumOf { it.costAmount },
                wasteCost = window.sumOf { it.wasteCostAmount }
            )
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class CoreBundle(
        val profile: KitchenProfile?,
        val inventory: List<InventoryItem>,
        val syncStatus: SyncStatus,
        val ledger: List<LedgerEntry>,
        val recipes: List<Recipe>,
        val flagged: List<FlaggedScanItem>
    )

    private data class OpsBundle(
        val staff: List<StaffMember>,
        val posSyncs: List<PosSyncRecord>,
        val prefs: LocalAppPreferences,
        val scanState: ScanUiState,
        val snackbar: String?
    )
}
