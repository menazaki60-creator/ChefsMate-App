package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BarrioRecipeBookImporter
import com.example.data.BasicIngredientSpec
import com.example.data.BilingualIngredientMatcher
import com.example.data.CsvExportService
import com.example.data.CsvPeriod
import com.example.data.InvoiceScannerService
import com.example.data.ParsedRecipeDraft
import com.example.data.RecipeDocumentService
import com.example.notifications.KitchenAlertNotifier
import com.example.data.KitchenRepository
import com.example.data.LocalAppPreferences
import com.example.data.LocalOcrAndDocumentParser
import com.example.data.PosSyncService
import com.example.data.PreferencesRepository
import com.example.data.WorkgroupInviteHelper
import com.example.data.WorkgroupRepository
import com.example.domain.PlateProfitabilityReport
import com.example.domain.RecipeCostEngine
import com.example.domain.SubRecipeCostSummary
import com.example.model.ActiveRolePermissions
import com.example.model.AppLanguage
import com.example.model.FlagStatus
import com.example.model.FlaggedScanItem
import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.JoinedWorkgroup
import com.example.model.KitchenProfile
import com.example.model.LedgerEntry
import com.example.model.LedgerEntryType
import com.example.model.PosProvider
import com.example.model.PosSyncRecord
import com.example.model.Recipe
import com.example.model.RecipeIngredientLine
import com.example.model.RecipeType
import com.example.model.ScannedInvoiceLine
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.model.StockAdjustMode
import com.example.model.SyncStatus
import com.example.model.UnitType
import com.example.model.Workgroup
import com.example.model.WorkgroupInvite
import com.example.model.WorkgroupMember
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
    data class Scanning(
        val statusText: String = "Scanning invoice locally...",
        val preview: Bitmap? = null
    ) : ScanUiState
    data class Results(
        val lines: List<ScannedInvoiceLine>,
        val preview: Bitmap? = null,
        val elapsedMs: Long? = null,
        val usedLocalScan: Boolean = true,
        val canRetryWithCloudAi: Boolean = false
    ) : ScanUiState
    data class Error(
        val message: String,
        val preview: Bitmap? = null,
        val canRetryWithCloudAi: Boolean = false,
        val rawExtractedText: String = ""
    ) : ScanUiState
}

sealed interface RecipeImportUiState {
    data object Idle : RecipeImportUiState
    data class Importing(
        val statusText: String = "Reading recipe file locally..."
    ) : RecipeImportUiState
    data class Preview(
        val drafts: List<ParsedRecipeDraft>,
        val sourceLabel: String,
        val canRetryWithCloudAi: Boolean
    ) : RecipeImportUiState
    data class Error(
        val message: String,
        val canRetryWithCloudAi: Boolean
    ) : RecipeImportUiState
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
    val currentUserId: String = "",
    val currentUserEmail: String = "",
    val activeKitchenOwnerId: String = "",
    val isViewingJoinedWorkgroup: Boolean = false,
    val ownedWorkgroups: List<Workgroup> = emptyList(),
    val activeWorkgroup: Workgroup? = null,
    val myMembership: WorkgroupMember? = null,
    val joinedWorkgroups: List<JoinedWorkgroup> = emptyList(),
    val workgroupMembers: List<WorkgroupMember> = emptyList(),
    val sentInvites: List<WorkgroupInvite> = emptyList(),
    val incomingEmailInvites: List<WorkgroupInvite> = emptyList(),
    val latestCreatedInvite: WorkgroupInvite? = null,
    val lookedUpInvite: WorkgroupInvite? = null,
    val inviteLookupBusy: Boolean = false,
    val inviteLookupError: String? = null,
    val groupActionBusy: Boolean = false,
    val groupActionError: String? = null,
    val profile: KitchenProfile? = null,
    val inventory: List<InventoryItem> = emptyList(),
    val ledgerEntries: List<LedgerEntry> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val menuRecipes: List<Recipe> = emptyList(),
    val subRecipes: List<Recipe> = emptyList(),
    val recipeReports: Map<String, PlateProfitabilityReport> = emptyMap(),
    val subRecipeReports: Map<String, SubRecipeCostSummary> = emptyMap(),
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
    val recipeImportState: RecipeImportUiState = RecipeImportUiState.Idle,
    val canAccessBarrio67Book: Boolean = false,
    val barrioBookImportBusy: Boolean = false,
    val posSyncBusy: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class KitchenViewModel(
    private val repository: KitchenRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val preferencesRepository: PreferencesRepository,
    private val scannerService: InvoiceScannerService,
    private val posSyncService: PosSyncService,
    private val currentUserId: String,
    private val currentUserName: String,
    private val currentUserEmail: String?,
    private val appContext: Context,
    private val recipeDocumentService: RecipeDocumentService = RecipeDocumentService(scannerService)
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    private val _recipeImportState = MutableStateFlow<RecipeImportUiState>(RecipeImportUiState.Idle)
    private val _barrioBookImportBusy = MutableStateFlow(false)
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _posSyncBusy = MutableStateFlow(false)
    private val _streamError = MutableStateFlow<String?>(null)

    private var _lastScannedUri: Uri? = null
    private var _lastScannedText: String? = null
    private var _lastRecipeImportUri: Uri? = null

    private val _latestCreatedInvite = MutableStateFlow<WorkgroupInvite?>(null)
    private val _lookedUpInvite = MutableStateFlow<WorkgroupInvite?>(null)
    private val _inviteLookupBusy = MutableStateFlow(false)
    private val _inviteLookupError = MutableStateFlow<String?>(null)
    private val _groupActionBusy = MutableStateFlow(false)
    private val _groupActionError = MutableStateFlow<String?>(null)

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

    private val prefsFlow: Flow<LocalAppPreferences> = preferencesRepository.preferencesFlow
        .catch { emit(LocalAppPreferences()) }
        .onStart { emit(LocalAppPreferences()) }

    private val activeOwnerFlow: Flow<String> = prefsFlow
        .map { it.activeWorkgroupOwnerId.trim().ifBlank { currentUserId } }
        .distinctUntilChanged()

    private val ownedWorkgroupsFlow: Flow<List<Workgroup>> =
        workgroupRepository.observeOwnedWorkgroups(currentUserId)
            .safeCatch(emptyList(), "owned_workgroups")
            .onStart { emit(emptyList()) }

    private val workspaceAccessFlow: Flow<WorkspaceAccess> = combine(
        activeOwnerFlow,
        ownedWorkgroupsFlow
    ) { workspaceId, ownedGroups ->
        val isOwned = workspaceId == currentUserId || ownedGroups.any { it.id == workspaceId }
        Triple(workspaceId, isOwned, ownedGroups.firstOrNull { it.id == workspaceId })
    }.distinctUntilChanged().flatMapLatest { (workspaceId, isOwned, ownedGroup) ->
        if (isOwned) {
            flowOf(WorkspaceAccess(workspaceId, isOwned = true, ownedGroup = ownedGroup, membership = null))
        } else {
            workgroupRepository.observeMyMembership(workspaceId, currentUserId)
                .safeCatch(null, "workgroup_membership")
                .onStart { emit(null) }
                .map { membership ->
                    WorkspaceAccess(workspaceId, isOwned = false, ownedGroup = null, membership = membership)
                }
        }
    }

    private val coreDataFlow: Flow<CoreBundle> = workspaceAccessFlow.flatMapLatest { access ->
        val workspaceId = access.workspaceId
        val canReadWorkspace = access.isOwned || (access.membership != null && access.membership.active)
        if (!canReadWorkspace) {
            flowOf(
                CoreBundle(
                    ownerId = workspaceId,
                    isOwned = access.isOwned,
                    activeWorkgroup = access.ownedGroup,
                    myMembership = access.membership,
                    profile = null,
                    inventory = emptyList(),
                    syncStatus = SyncStatus(fromCache = false),
                    ledger = emptyList(),
                    recipes = emptyList(),
                    flagged = emptyList()
                )
            )
        } else {
            combine(
                repository.observeProfile(workspaceId)
                    .safeCatch(null, "profile")
                    .onStart { emit(null) },
                repository.observeInventory(workspaceId)
                    .map { it.data to it.syncStatus }
                    .safeCatch(emptyList<InventoryItem>() to SyncStatus(fromCache = true), "inventory")
                    .onStart { emit(emptyList<InventoryItem>() to SyncStatus(fromCache = true)) },
                repository.observeLedgerEntries(workspaceId)
                    .safeCatch(emptyList(), "ledger")
                    .onStart { emit(emptyList()) },
                repository.observeRecipes(workspaceId)
                    .safeCatch(emptyList(), "recipes")
                    .onStart { emit(emptyList()) },
                repository.observeFlaggedScans(workspaceId)
                    .safeCatch(emptyList(), "flagged_scans")
                    .onStart { emit(emptyList()) }
            ) { profile, invPair, ledger, recipes, flagged ->
                CoreBundle(
                    ownerId = workspaceId,
                    isOwned = access.isOwned,
                    activeWorkgroup = access.ownedGroup,
                    myMembership = access.membership,
                    profile = profile,
                    inventory = invPair.first,
                    syncStatus = invPair.second,
                    ledger = ledger,
                    recipes = recipes,
                    flagged = flagged
                )
            }
        }
    }

    private val opsDataFlow: Flow<OpsBundle> = workspaceAccessFlow.flatMapLatest { access ->
        val workspaceId = access.workspaceId
        val isOwner = access.isOwned
        val membership = access.membership
        val canReadPosSyncs = isOwner || (membership != null && membership.active && (membership.canViewReports || membership.canManagePos))

        val staffFlow = if (isOwner) {
            repository.observeStaff(workspaceId)
                .safeCatch(emptyList(), "staff")
                .onStart { emit(emptyList()) }
        } else {
            flowOf(emptyList())
        }

        val posFlow = if (canReadPosSyncs) {
            repository.observePosSyncs(workspaceId)
                .safeCatch(emptyList(), "pos_syncs")
                .onStart { emit(emptyList()) }
        } else {
            flowOf(emptyList())
        }

        combine(
            staffFlow,
            posFlow,
            prefsFlow,
            _scanState,
            _snackbarMessage
        ) { staff, posSyncs, prefs, scanState, snackbar ->
            OpsBundle(staff, posSyncs, prefs, scanState, snackbar)
        }
    }

    private val activeWorkgroupMembersFlow: Flow<List<WorkgroupMember>> =
        workspaceAccessFlow.flatMapLatest { access ->
            if (access.isOwned) {
                workgroupRepository.observeWorkgroupMembers(access.workspaceId)
                    .safeCatch(emptyList(), "workgroup_members")
                    .onStart { emit(emptyList()) }
            } else {
                flowOf(emptyList())
            }
        }

    private val workgroupDataFlow: Flow<WorkgroupBundle> = combine(
        ownedWorkgroupsFlow,
        workgroupRepository.observeJoinedWorkgroups(currentUserId)
            .safeCatch(emptyList(), "joined_workgroups")
            .onStart { emit(emptyList()) },
        activeWorkgroupMembersFlow,
        workgroupRepository.observeSentInvites(currentUserId)
            .safeCatch(emptyList(), "sent_invites")
            .onStart { emit(emptyList()) },
        workgroupRepository.observeIncomingEmailInvites(currentUserId, currentUserEmail)
            .safeCatch(emptyList(), "incoming_invites")
            .onStart { emit(emptyList()) }
    ) { owned, joined, members, sent, incoming ->
        WorkgroupBundle(owned, joined, members, sent, incoming)
    }

    private val inviteDialogFlow: Flow<InviteDialogBundle> = combine(
        _latestCreatedInvite,
        _lookedUpInvite,
        _inviteLookupBusy,
        _inviteLookupError,
        combine(_groupActionBusy, _groupActionError) { busy, err -> busy to err }
    ) { created, lookedUp, busy, err, groupPair ->
        InviteDialogBundle(created, lookedUp, busy, err, groupPair.first, groupPair.second)
    }

    val uiState: StateFlow<KitchenUiState> = combine(
        coreDataFlow,
        opsDataFlow,
        workgroupDataFlow,
        inviteDialogFlow,
        combine(_posSyncBusy, _streamError, _recipeImportState, _barrioBookImportBusy) { b, e, r, bookBusy ->
            Pair(Pair(b, e), Pair(r, bookBusy))
        }
    ) { core, ops, wg, dlg, statusNested ->
        val posBusy = statusNested.first.first
        val streamErr = statusNested.first.second
        val recipeImport = statusNested.second.first
        val barrioBookBusy = statusNested.second.second
        val inventory = core.inventory
        val recipes = core.recipes
        val ledger = core.ledger
        val pendingFlags = core.flagged.filter { it.status == FlagStatus.PENDING.id }

        val menuRecipes = recipes.filter { it.recipeTypeEnum == RecipeType.MENU_ITEM }
        val subRecipes = recipes.filter { it.recipeTypeEnum == RecipeType.SUB_RECIPE }

        val recipeReports = menuRecipes.associate { recipe ->
            recipe.id to RecipeCostEngine.evaluateRecipe(recipe, inventory, recipes)
        }
        val subRecipeReports = subRecipes.associate { sub ->
            sub.id to RecipeCostEngine.evaluateSubRecipe(sub, inventory, recipes)
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

        val alerts = buildUrgentAlerts(inventory, subRecipes, pendingFlags, core.profile?.currencySymbol ?: "$")
        if (core.profile?.lowStockAlertsEnabled != false) {
            dispatchNotifications(alerts)
        }

        val isJoinedView = !core.isOwned
        val activePerms = resolveActivePermissions(
            isViewingJoinedWorkgroup = isJoinedView,
            myMembership = core.myMembership,
            ownerName = core.profile?.displayName ?: currentUserName
        )

        val scopedSentInvites = wg.sentInvites.filter { it.resolvedWorkspaceId == core.ownerId }
        val resolvedActiveGroup = core.activeWorkgroup
            ?: wg.ownedWorkgroups.firstOrNull { it.id == core.ownerId }

        KitchenUiState(
            loading = false,
            errorMessage = streamErr,
            snackbarMessage = ops.snackbar,
            currentUserId = currentUserId,
            currentUserEmail = currentUserEmail.orEmpty(),
            activeKitchenOwnerId = core.ownerId,
            isViewingJoinedWorkgroup = isJoinedView,
            ownedWorkgroups = wg.ownedWorkgroups,
            activeWorkgroup = resolvedActiveGroup,
            myMembership = core.myMembership,
            joinedWorkgroups = wg.joinedWorkgroups,
            workgroupMembers = wg.workgroupMembers,
            sentInvites = scopedSentInvites,
            incomingEmailInvites = wg.incomingEmailInvites,
            latestCreatedInvite = dlg.latestCreatedInvite,
            lookedUpInvite = dlg.lookedUpInvite,
            inviteLookupBusy = dlg.inviteLookupBusy,
            inviteLookupError = dlg.inviteLookupError,
            groupActionBusy = dlg.groupActionBusy,
            groupActionError = dlg.groupActionError,
            profile = core.profile,
            inventory = inventory,
            ledgerEntries = ledger,
            recipes = recipes,
            menuRecipes = menuRecipes,
            subRecipes = subRecipes,
            recipeReports = recipeReports,
            subRecipeReports = subRecipeReports,
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
            recipeImportState = recipeImport,
            canAccessBarrio67Book = BarrioRecipeBookImporter.isAuthorizedForWorkspace(
                userEmail = currentUserEmail,
                isViewingJoinedWorkgroup = isJoinedView
            ),
            barrioBookImportBusy = barrioBookBusy,
            posSyncBusy = posBusy
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = KitchenUiState(
            loading = true,
            currentUserId = currentUserId,
            currentUserEmail = currentUserEmail.orEmpty(),
            activeKitchenOwnerId = currentUserId
        )
    )

    init {
        LocalOcrAndDocumentParser.warmUp()
        viewModelScope.launch {
            repository.ensureKitchenProfile(currentUserName)
            workgroupRepository.ensurePrimaryWorkgroup(
                ownerUid = currentUserId,
                defaultKitchenName = "${currentUserName.trim().ifBlank { "Chef" }}'s Kitchen"
            )
        }
    }

    private fun activeWorkspaceId(): String =
        uiState.value.activeKitchenOwnerId.ifBlank { currentUserId }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun clearStreamError() {
        _streamError.value = null
    }

    // --- Preferences & Workspace Switching ---

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setDarkMode(enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { preferencesRepository.setLanguage(language) }
    }

    fun switchWorkspace(workspaceId: String) {
        viewModelScope.launch {
            val target = if (workspaceId == currentUserId) "" else workspaceId.trim()
            preferencesRepository.setActiveWorkgroupOwnerId(target)
            val resolvedName = uiState.value.ownedWorkgroups.firstOrNull { it.id == workspaceId }?.name
                ?: uiState.value.joinedWorkgroups.firstOrNull { it.resolvedWorkspaceId == workspaceId }?.kitchenName
                ?: if (target.isBlank()) "Main Kitchen" else "Group Workspace"
            _snackbarMessage.value = "Switched to $resolvedName"
        }
    }

    // --- Multi-Group Creation & Branding (Editable Name + Unique @username + Uploaded Logo) ---

    fun clearGroupActionError() {
        _groupActionError.value = null
    }

    fun createNewWorkgroup(
        name: String,
        username: String,
        currencySymbol: String = "$",
        logoUri: Uri? = null,
        onComplete: (Result<Workgroup>) -> Unit = {}
    ) {
        viewModelScope.launch {
            _groupActionBusy.value = true
            _groupActionError.value = null
            val logoBase64 = if (logoUri != null) {
                val encoded = withContext(Dispatchers.IO) {
                    WorkgroupInviteHelper.encodeLogoToBase64(appContext, logoUri)
                }
                encoded.getOrElse { err ->
                    _groupActionBusy.value = false
                    val msg = err.localizedMessage ?: "Failed to process logo image"
                    _groupActionError.value = msg
                    _snackbarMessage.value = msg
                    onComplete(Result.failure(err))
                    return@launch
                }
            } else null

            val result = workgroupRepository.createNewWorkgroup(
                name = name,
                username = username,
                currencySymbol = currencySymbol,
                logoBase64 = logoBase64
            )
            result.onSuccess { created ->
                _groupActionError.value = null
                preferencesRepository.setActiveWorkgroupOwnerId(created.id)
                _snackbarMessage.value = "Created \"${created.name}\" (@${created.resolvedUsername}) · Code: ${created.groupCode}"
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Could not create group"
                _groupActionError.value = msg
                _snackbarMessage.value = msg
            }
            _groupActionBusy.value = false
            onComplete(result)
        }
    }

    fun updateActiveGroupBranding(
        newName: String,
        newUsername: String,
        newLogoUri: Uri? = null,
        onComplete: (Result<Workgroup>) -> Unit = {}
    ) {
        val state = uiState.value
        if (state.isViewingJoinedWorkgroup) {
            val err = IllegalStateException("Only the group manager can edit group name, @username, or logo.")
            _groupActionError.value = err.message
            _snackbarMessage.value = err.message
            onComplete(Result.failure(err))
            return
        }
        viewModelScope.launch {
            _groupActionBusy.value = true
            _groupActionError.value = null
            val targetGroup = state.activeWorkgroup ?: workgroupRepository.ensurePrimaryWorkgroup(
                ownerUid = currentUserId,
                defaultKitchenName = state.profile?.kitchenName ?: "Main Kitchen",
                currencySymbol = state.profile?.currencySymbol ?: "$",
                logoBase64 = state.profile?.logoBase64
            ).getOrElse { err ->
                _groupActionBusy.value = false
                val msg = err.localizedMessage ?: "Could not load group settings"
                _groupActionError.value = msg
                _snackbarMessage.value = msg
                onComplete(Result.failure(err))
                return@launch
            }

            val logoBase64 = if (newLogoUri != null) {
                val encoded = withContext(Dispatchers.IO) {
                    WorkgroupInviteHelper.encodeLogoToBase64(appContext, newLogoUri)
                }
                encoded.getOrElse { err ->
                    _groupActionBusy.value = false
                    val msg = err.localizedMessage ?: "Failed to encode group logo"
                    _groupActionError.value = msg
                    _snackbarMessage.value = msg
                    onComplete(Result.failure(err))
                    return@launch
                }
            } else null

            val result = workgroupRepository.updateWorkgroupBranding(
                workgroup = targetGroup,
                newName = newName,
                newUsername = newUsername,
                newLogoBase64 = logoBase64
            )
            result.onSuccess { updated ->
                _groupActionError.value = null
                _snackbarMessage.value = "Updated \"${updated.name}\" (@${updated.resolvedUsername})"
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to update group"
                _groupActionError.value = msg
                _snackbarMessage.value = msg
            }
            _groupActionBusy.value = false
            onComplete(result)
        }
    }

    // --- Strictly Email-Only Workgroup Invites & Manager-Only Roles ---

    fun createStaffAndInvite(member: StaffMember) {
        val state = uiState.value
        if (state.isViewingJoinedWorkgroup) {
            _snackbarMessage.value = "Only the group manager can invite staff members"
            return
        }
        val workspaceId = activeWorkspaceId()
        val activeGroup = state.activeWorkgroup
        val kitchenName = activeGroup?.name?.ifBlank { state.profile?.kitchenName }
            ?: state.profile?.kitchenName?.ifBlank { "Main Kitchen" } ?: "Main Kitchen"
        val groupCode = activeGroup?.groupCode?.ifBlank { state.profile?.groupCode.orEmpty() }
            ?: state.profile?.groupCode.orEmpty()
        val logoBase64 = activeGroup?.logoBase64 ?: state.profile?.logoBase64
        val inviterName = state.profile?.displayName?.ifBlank { currentUserName } ?: currentUserName

        viewModelScope.launch {
            val resolvedCode = groupCode.ifBlank {
                workgroupRepository.ensurePrimaryWorkgroup(currentUserId, kitchenName)
                    .getOrNull()?.groupCode ?: WorkgroupInviteHelper.generateGroupCode()
            }
            workgroupRepository.createStaffWithInvite(
                workspaceId = workspaceId,
                member = member,
                kitchenName = kitchenName,
                groupCode = resolvedCode,
                logoBase64 = logoBase64,
                invitedByName = inviterName
            ).onSuccess { invite ->
                _latestCreatedInvite.value = invite
                _snackbarMessage.value = "Email invite created for ${invite.inviteEmail}"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to create invite"
            }
        }
    }

    fun showInviteShareSheet(invite: WorkgroupInvite) {
        _latestCreatedInvite.value = invite
    }

    fun dismissCreatedInvite() {
        _latestCreatedInvite.value = null
    }

    fun regenerateInviteForStaff(staff: StaffMember) {
        val state = uiState.value
        if (state.isViewingJoinedWorkgroup) return
        val workspaceId = activeWorkspaceId()
        val activeGroup = state.activeWorkgroup
        val kitchenName = activeGroup?.name?.ifBlank { state.profile?.kitchenName }
            ?: state.profile?.kitchenName?.ifBlank { "Main Kitchen" } ?: "Main Kitchen"
        val groupCode = activeGroup?.groupCode?.ifBlank { state.profile?.groupCode.orEmpty() }
            ?: state.profile?.groupCode.orEmpty()
        val logoBase64 = activeGroup?.logoBase64 ?: state.profile?.logoBase64
        val inviterName = state.profile?.displayName?.ifBlank { currentUserName } ?: currentUserName
        val existingPendingCode = state.sentInvites
            .firstOrNull { it.staffRecordId == staff.id && it.status == "PENDING" }?.id
        viewModelScope.launch {
            workgroupRepository.regenerateStaffInvite(
                workspaceId = workspaceId,
                staff = staff,
                kitchenName = kitchenName,
                groupCode = groupCode.ifBlank { WorkgroupInviteHelper.generateGroupCode() },
                logoBase64 = logoBase64,
                invitedByName = inviterName,
                existingPendingInviteCode = existingPendingCode
            ).onSuccess { invite ->
                _latestCreatedInvite.value = invite
                _snackbarMessage.value = "Fresh email invite ready for ${staff.inviteEmail}"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to regenerate invite"
            }
        }
    }

    fun updateStaffRoleAndPermissions(
        staffId: String,
        linkedMemberUid: String?,
        role: StaffRole,
        canViewReports: Boolean,
        canExportCsv: Boolean,
        canApproveScans: Boolean,
        canEditRecipes: Boolean,
        canManagePos: Boolean
    ) {
        if (uiState.value.isViewingJoinedWorkgroup) {
            _snackbarMessage.value = "Only the group manager can change staff roles"
            return
        }
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            workgroupRepository.updateStaffRoleAndPermissions(
                workspaceId = workspaceId,
                staffId = staffId,
                linkedMemberUid = linkedMemberUid,
                role = role,
                canViewReports = canViewReports,
                canExportCsv = canExportCsv,
                canApproveScans = canApproveScans,
                canEditRecipes = canEditRecipes,
                canManagePos = canManagePos
            ).onSuccess {
                _snackbarMessage.value = "Role updated to ${role.name.replace('_', ' ')}"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to update role"
            }
        }
    }

    fun revokeInvite(inviteCode: String) {
        viewModelScope.launch {
            workgroupRepository.revokeInvite(inviteCode)
                .onSuccess { _snackbarMessage.value = "Invite revoked" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Revoke failed" }
        }
    }

    /** Opens the explicit confirmation + Group Code dialog before an invited user can join. */
    fun openConfirmEmailInvite(invite: WorkgroupInvite) {
        _inviteLookupError.value = null
        _lookedUpInvite.value = invite
    }

    fun clearLookedUpInvite() {
        _lookedUpInvite.value = null
        _inviteLookupError.value = null
    }

    fun acceptInviteWithCode(invite: WorkgroupInvite, enteredGroupCode: String) {
        viewModelScope.launch {
            _inviteLookupBusy.value = true
            _inviteLookupError.value = null
            workgroupRepository.acceptWorkgroupInvite(
                invite = invite,
                enteredGroupCode = enteredGroupCode,
                memberDisplayName = currentUserName,
                memberEmail = currentUserEmail
            ).onSuccess { joined ->
                _lookedUpInvite.value = null
                preferencesRepository.setActiveWorkgroupOwnerId(joined.resolvedWorkspaceId)
                _snackbarMessage.value = "Joined ${joined.kitchenName} as ${joined.role.replace('_', ' ')}!"
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to join group"
                _inviteLookupError.value = msg
            }
            _inviteLookupBusy.value = false
        }
    }

    fun declineInvite(invite: WorkgroupInvite) {
        viewModelScope.launch {
            _inviteLookupBusy.value = true
            workgroupRepository.declineWorkgroupInvite(invite.id)
                .onSuccess {
                    if (_lookedUpInvite.value?.id == invite.id) {
                        _lookedUpInvite.value = null
                    }
                    _snackbarMessage.value = "Declined invitation to ${invite.kitchenName}"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to decline invitation"
                }
            _inviteLookupBusy.value = false
        }
    }

    fun leaveWorkgroup(workspaceId: String) {
        viewModelScope.launch {
            workgroupRepository.leaveWorkgroup(workspaceId)
                .onSuccess {
                    if (uiState.value.activeKitchenOwnerId == workspaceId) {
                        preferencesRepository.setActiveWorkgroupOwnerId("")
                    }
                    _snackbarMessage.value = "Left group and returned to Main Kitchen"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to leave group"
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
        supplierName: String,
        allowAutoCorrectTypos: Boolean = true
    ) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        val previewMatch = BilingualIngredientMatcher.resolve(
            rawName = name,
            existingInventory = state.inventory,
            fallbackArabic = arabicName,
            fallbackCategory = category,
            allowAutoCorrectTypos = allowAutoCorrectTypos
        )
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
                staffName = state.activePermissions.activeStaffName,
                allowAutoCorrectTypos = allowAutoCorrectTypos,
                workspaceOwnerId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = if (previewMatch.autoCorrectedFrom != null) {
                    "Auto-corrected '${previewMatch.autoCorrectedFrom}' → ${previewMatch.nameEn} & synced"
                } else {
                    "Saved & synced to ChefsMate cloud"
                }
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to save item"
            }
        }
    }

    fun addBasicIngredients(specs: List<BasicIngredientSpec>) {
        if (specs.isEmpty()) return
        val existing = uiState.value.inventory
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.addBasicIngredientsBatch(specs, existing, workspaceOwnerId = workspaceId)
                .onSuccess { addedCount ->
                    _snackbarMessage.value = if (addedCount == 0) {
                        "All selected basic ingredients are already in your stock"
                    } else {
                        "Added $addedCount basic ingredient(s) to stock"
                    }
                }
                .onFailure {
                    _snackbarMessage.value = it.localizedMessage ?: "Failed to add basic ingredients"
                }
        }
    }

    fun adjustRawItemStock(
        item: InventoryItem,
        mode: StockAdjustMode,
        amount: Double,
        unit: UnitType,
        extraCost: Double,
        newMinStock: Double?,
        reason: String
    ) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.adjustRawItemStock(
                item = item,
                mode = mode,
                amountInInputUnit = amount,
                inputUnit = unit,
                extraCost = extraCost,
                newMinStockLevel = newMinStock,
                reason = reason,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = when (mode) {
                    StockAdjustMode.ADD -> "Restocked ${item.nameEn}"
                    StockAdjustMode.DEDUCT -> "Deducted stock from ${item.nameEn}"
                    StockAdjustMode.SET_EXACT -> "Updated stock count for ${item.nameEn}"
                }
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to update stock"
            }
        }
    }

    fun produceSubRecipeBatch(
        subRecipe: Recipe,
        producedQuantityInYieldUnit: Double,
        deductRawIngredients: Boolean
    ) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.produceSubRecipeBatch(
                subRecipe = subRecipe,
                producedQuantityInYieldUnit = producedQuantityInYieldUnit,
                deductRawIngredients = deductRawIngredients,
                inventory = state.inventory,
                allRecipes = state.recipes,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess { deductedRawCount ->
                val qtyStr = String.format(Locale.US, "%.2f", producedQuantityInYieldUnit)
                    .trimEnd('0').trimEnd('.')
                _snackbarMessage.value = if (deductRawIngredients && deductedRawCount > 0) {
                    "Produced $qtyStr ${subRecipe.yieldUnit} of ${subRecipe.nameEn} • deducted $deductedRawCount raw item(s)"
                } else {
                    "Added $qtyStr ${subRecipe.yieldUnit} of ${subRecipe.nameEn} to production stock"
                }
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to log production batch"
            }
        }
    }

    fun adjustProductionStock(
        subRecipe: Recipe,
        mode: StockAdjustMode,
        amount: Double,
        unit: UnitType,
        newMinProductionStock: Double?
    ) {
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.adjustProductionStock(
                subRecipe = subRecipe,
                mode = mode,
                amountInInputUnit = amount,
                inputUnit = unit,
                newMinProductionStock = newMinProductionStock,
                workspaceOwnerId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = "Updated production stock for ${subRecipe.nameEn}"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to update production stock"
            }
        }
    }

    fun logWaste(item: InventoryItem, amount: Double, unit: UnitType, reason: String) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.logWasteForItem(
                item = item,
                wasteAmount = amount,
                wasteUnit = unit,
                reason = reason,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = "Waste logged & net usage updated"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to log waste"
            }
        }
    }

    fun deleteInventoryItem(itemId: String) {
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.deleteInventoryItem(itemId, workspaceOwnerId = workspaceId)
                .onSuccess { _snackbarMessage.value = "Ingredient removed" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Delete failed" }
        }
    }

    // --- Fast Local On-Device Invoice Scanner (+ Optional Cloud AI Fallback) ---

    fun reportScanError(message: String) {
        _scanState.value = ScanUiState.Error(message)
    }

    fun scanInvoiceUri(uri: Uri) {
        val existing = uiState.value.inventory
        _lastScannedUri = uri
        _lastScannedText = null
        viewModelScope.launch {
            _scanState.value = ScanUiState.Scanning(statusText = "Scanning invoice locally on device...")
            scannerService.scanInvoiceLocallyFromUri(appContext, uri, existing)
                .onSuccess { output ->
                    if (output.lines.isEmpty()) {
                        val hasRawText = output.rawExtractedText.isNotBlank()
                        _scanState.value = ScanUiState.Error(
                            message = if (hasRawText) {
                                "Local scan finished in ${output.elapsedMs} ms, but couldn't match quantity/price columns automatically. Tap 'Edit Detected Text Locally' below to adjust and scan instantly."
                            } else {
                                "No readable text was detected locally (${output.elapsedMs} ms). Try a sharper, well-lit photo or upload a PDF, Excel, or Word invoice."
                            },
                            preview = output.previewThumbnail,
                            canRetryWithCloudAi = false,
                            rawExtractedText = output.rawExtractedText
                        )
                    } else {
                        _scanState.value = ScanUiState.Results(
                            lines = output.lines,
                            preview = output.previewThumbnail,
                            elapsedMs = output.elapsedMs,
                            usedLocalScan = true,
                            canRetryWithCloudAi = false
                        )
                    }
                }
                .onFailure { err ->
                    _scanState.value = ScanUiState.Error(
                        message = err.localizedMessage ?: "Could not scan this invoice locally. Please try a clearer photo or file.",
                        canRetryWithCloudAi = false
                    )
                }
        }
    }

    fun scanInvoiceText(rawText: String) {
        val existing = uiState.value.inventory
        _lastScannedUri = null
        _lastScannedText = rawText
        viewModelScope.launch {
            _scanState.value = ScanUiState.Scanning(statusText = "Parsing invoice text locally...")
            scannerService.scanInvoiceTextLocally(rawText, existing)
                .onSuccess { output ->
                    if (output.lines.isEmpty()) {
                        _scanState.value = ScanUiState.Error(
                            message = "No ingredient lines matched locally. Try adding quantity or price on each line (e.g. '2kg Chicken $14').",
                            canRetryWithCloudAi = false
                        )
                    } else {
                        _scanState.value = ScanUiState.Results(
                            lines = output.lines,
                            preview = null,
                            elapsedMs = output.elapsedMs,
                            usedLocalScan = true,
                            canRetryWithCloudAi = false
                        )
                    }
                }
                .onFailure { e ->
                    _scanState.value = ScanUiState.Error(
                        message = e.localizedMessage ?: "Local text parse failed",
                        canRetryWithCloudAi = false
                    )
                }
        }
    }

    /**
     * Explicit optional Cloud AI fallback (useful for cursive Arabic or handwritten invoices).
     */
    fun rescanLastInvoiceWithCloudAi() {
        val existing = uiState.value.inventory
        val uri = _lastScannedUri
        val text = _lastScannedText
        if (uri == null && text.isNullOrBlank()) {
            _snackbarMessage.value = "Capture or pick an invoice first"
            return
        }
        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            if (uri != null) {
                _scanState.value = ScanUiState.Scanning(statusText = "Analyzing invoice with Cloud AI (Arabic / Handwritten)...")
                val mediaResult = scannerService.loadMediaFromUri(appContext, uri)
                mediaResult.onFailure { err ->
                    _scanState.value = ScanUiState.Error(
                        message = err.localizedMessage ?: "Could not open the invoice file for Cloud AI."
                    )
                    return@launch
                }
                val media = mediaResult.getOrThrow()
                _scanState.value = ScanUiState.Scanning(
                    statusText = if (media.isPdf) "Reading ${media.pages.size} PDF page(s) with Cloud AI..."
                    else "Reading Arabic & handwritten text with Cloud AI...",
                    preview = media.previewThumbnail
                )
                scannerService.scanInvoiceBitmapsWithCloudAi(media.pages, existing)
                    .onSuccess { lines ->
                        val elapsed = System.currentTimeMillis() - startMs
                        if (lines.isEmpty()) {
                            _scanState.value = ScanUiState.Error(
                                message = "Cloud AI could not detect food items in this invoice.",
                                preview = media.previewThumbnail,
                                canRetryWithCloudAi = false
                            )
                        } else {
                            _scanState.value = ScanUiState.Results(
                                lines = lines,
                                preview = media.previewThumbnail,
                                elapsedMs = elapsed,
                                usedLocalScan = false,
                                canRetryWithCloudAi = false
                            )
                        }
                    }
                    .onFailure { e ->
                        _scanState.value = ScanUiState.Error(
                            message = e.localizedMessage ?: "Cloud AI invoice scan failed.",
                            preview = media.previewThumbnail,
                            canRetryWithCloudAi = true
                        )
                    }
            } else if (!text.isNullOrBlank()) {
                _scanState.value = ScanUiState.Scanning(statusText = "Analyzing invoice text with Cloud AI...")
                scannerService.scanInvoiceTextWithCloudAi(text, existing)
                    .onSuccess { lines ->
                        val elapsed = System.currentTimeMillis() - startMs
                        if (lines.isEmpty()) {
                            _scanState.value = ScanUiState.Error("Cloud AI found no ingredient line items in this text.")
                        } else {
                            _scanState.value = ScanUiState.Results(
                                lines = lines,
                                elapsedMs = elapsed,
                                usedLocalScan = false,
                                canRetryWithCloudAi = false
                            )
                        }
                    }
                    .onFailure { e ->
                        _scanState.value = ScanUiState.Error(e.localizedMessage ?: "Cloud AI scan failed")
                    }
            }
        }
    }

    fun commitScannedLines(lines: List<ScannedInvoiceLine>) {
        if (lines.isEmpty()) {
            _scanState.value = ScanUiState.Idle
            return
        }
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.commitScannedLinesBatch(
                lines = lines,
                existingInventory = state.inventory,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess { summary ->
                _scanState.value = ScanUiState.Idle
                _snackbarMessage.value = if (summary.flaggedCount > 0) {
                    "Imported ${summary.addedCount} item(s), flagged ${summary.flaggedCount} for manual review"
                } else {
                    "Imported ${summary.addedCount} item(s) to stock"
                }
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to import scanned items"
            }
        }
    }

    fun updateScannedLine(index: Int, updatedLine: ScannedInvoiceLine) {
        val current = _scanState.value as? ScanUiState.Results ?: return
        if (index !in current.lines.indices) return
        val mutable = current.lines.toMutableList()
        mutable[index] = updatedLine
        _scanState.value = current.copy(lines = mutable)
    }

    fun removeScannedLine(index: Int) {
        val current = _scanState.value as? ScanUiState.Results ?: return
        if (index !in current.lines.indices) return
        val mutable = current.lines.toMutableList()
        mutable.removeAt(index)
        _scanState.value = if (mutable.isEmpty()) ScanUiState.Idle else current.copy(lines = mutable)
    }

    /** Converts all scanned line prices into USD ($) using a chef-specified exchange divisor (e.g. 89500 for LBP -> USD). */
    fun convertAllScannedPricesToUsd(divisor: Double) {
        val current = _scanState.value as? ScanUiState.Results ?: return
        if (divisor <= 0.0) return
        val converted = current.lines.map { line ->
            val usdPrice = kotlin.math.round((line.totalCost / divisor) * 100.0) / 100.0
            line.copy(totalCost = usdPrice.coerceAtLeast(0.0), detectedCurrency = "$")
        }
        _scanState.value = current.copy(lines = converted)
        _snackbarMessage.value = "Converted ${converted.size} invoice price(s) to $ (÷ ${String.format(Locale.US, "%,.2f", divisor)})"
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
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.approveFlaggedScan(
                flagged = flagged,
                targetNameEn = nameEn,
                targetNameAr = nameAr,
                category = category,
                linkToExisting = linkToExisting,
                existingInventory = state.inventory,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = "Flagged item approved & merged into inventory"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Approval failed"
            }
        }
    }

    fun dismissFlaggedScan(flaggedId: String) {
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.dismissFlaggedScan(flaggedId, workspaceOwnerId = workspaceId)
                .onSuccess { _snackbarMessage.value = "Flagged item dismissed" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Dismiss failed" }
        }
    }

    // --- Recipes & Production Sub-Recipes ---

    fun saveRecipe(
        existingId: String?,
        nameEn: String,
        nameAr: String,
        recipeType: RecipeType = RecipeType.MENU_ITEM,
        servings: Int,
        yieldQuantity: Double = 1.0,
        yieldUnit: UnitType = UnitType.G,
        menuPrice: Double,
        desiredProfitMarginPct: Double,
        posSku: String,
        category: String,
        lines: List<RecipeIngredientLine>
    ) {
        val targetFoodCostPct = (100.0 - desiredProfitMarginPct).coerceIn(5.0, 95.0)
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.saveRecipe(
                existingId = existingId,
                nameEn = nameEn,
                nameAr = nameAr,
                recipeType = recipeType,
                servings = servings,
                yieldQuantity = yieldQuantity,
                yieldUnit = yieldUnit,
                menuPrice = menuPrice,
                targetFoodCostPct = targetFoodCostPct,
                posSku = posSku,
                category = category,
                lines = lines,
                workspaceOwnerId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = if (recipeType == RecipeType.SUB_RECIPE) {
                    "Production sub-recipe saved"
                } else {
                    "Recipe saved"
                }
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to save recipe"
            }
        }
    }

    fun deleteRecipe(recipeId: String) {
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            repository.deleteRecipe(recipeId, workspaceOwnerId = workspaceId)
                .onSuccess { _snackbarMessage.value = "Recipe deleted" }
                .onFailure { _snackbarMessage.value = it.localizedMessage ?: "Delete failed" }
        }
    }

    // --- Recipe Upload (Excel / Word / PDF / Photo) & Download (PDF / Excel) ---

    fun importRecipesFromUri(uri: Uri) {
        val state = uiState.value
        _lastRecipeImportUri = uri
        viewModelScope.launch {
            _recipeImportState.value = RecipeImportUiState.Importing("Reading recipe file locally...")
            recipeDocumentService.importRecipesLocallyFromUri(
                context = appContext,
                uri = uri,
                inventory = state.inventory,
                existingRecipes = state.recipes
            ).onSuccess { result ->
                if (result.drafts.isEmpty()) {
                    _recipeImportState.value = RecipeImportUiState.Error(
                        message = "No recipes or ingredient lines were detected in ${result.sourceLabel}. " +
                            if (result.canRetryWithCloudAi) "If this is a handwritten or Arabic recipe photo/PDF, tap 'Read with Cloud AI'."
                            else "Check that your Excel/Word file lists ingredients with quantities (e.g. '250g Chicken Breast').",
                        canRetryWithCloudAi = result.canRetryWithCloudAi
                    )
                } else {
                    _recipeImportState.value = RecipeImportUiState.Preview(
                        drafts = result.drafts,
                        sourceLabel = result.sourceLabel,
                        canRetryWithCloudAi = result.canRetryWithCloudAi
                    )
                }
            }.onFailure { err ->
                _recipeImportState.value = RecipeImportUiState.Error(
                    message = err.localizedMessage ?: "Could not read recipe file locally.",
                    canRetryWithCloudAi = false
                )
            }
        }
    }

    fun retryRecipeImportWithCloudAi() {
        val uri = _lastRecipeImportUri ?: return
        val state = uiState.value
        viewModelScope.launch {
            _recipeImportState.value = RecipeImportUiState.Importing("Reading recipe with Cloud AI (Arabic / Handwritten)...")
            recipeDocumentService.importRecipesWithCloudAiFromUri(
                context = appContext,
                uri = uri,
                inventory = state.inventory,
                existingRecipes = state.recipes
            ).onSuccess { result ->
                if (result.drafts.isEmpty()) {
                    _recipeImportState.value = RecipeImportUiState.Error(
                        message = "Cloud AI could not detect any recipes on this page. Try a clearer photo or upload an Excel/Word file.",
                        canRetryWithCloudAi = false
                    )
                } else {
                    _recipeImportState.value = RecipeImportUiState.Preview(
                        drafts = result.drafts,
                        sourceLabel = result.sourceLabel,
                        canRetryWithCloudAi = false
                    )
                }
            }.onFailure { err ->
                _recipeImportState.value = RecipeImportUiState.Error(
                    message = err.localizedMessage ?: "Cloud AI recipe import failed.",
                    canRetryWithCloudAi = false
                )
            }
        }
    }

    fun commitImportedRecipes(drafts: List<ParsedRecipeDraft>) {
        if (drafts.isEmpty()) {
            _recipeImportState.value = RecipeImportUiState.Idle
            return
        }
        val workspaceId = activeWorkspaceId()
        val defaultTargetFoodCost = uiState.value.profile?.targetFoodCostPct ?: 30.0
        viewModelScope.launch {
            var savedCount = 0
            var failedCount = 0
            for (draft in drafts) {
                val res = repository.saveRecipe(
                    existingId = null,
                    nameEn = draft.nameEn,
                    nameAr = draft.nameAr,
                    recipeType = draft.recipeType,
                    servings = draft.servings,
                    yieldQuantity = draft.yieldQuantity,
                    yieldUnit = draft.yieldUnit,
                    menuPrice = draft.menuPrice,
                    targetFoodCostPct = defaultTargetFoodCost,
                    posSku = draft.posSku,
                    category = draft.category,
                    lines = draft.lines,
                    workspaceOwnerId = workspaceId
                )
                if (res.isSuccess) savedCount++ else failedCount++
            }
            _recipeImportState.value = RecipeImportUiState.Idle
            _snackbarMessage.value = if (failedCount > 0) {
                "Imported $savedCount recipe(s), $failedCount failed"
            } else {
                "Imported $savedCount recipe(s) into ChefsMate"
            }
        }
    }

    fun resetRecipeImport() {
        _recipeImportState.value = RecipeImportUiState.Idle
    }

    /**
     * Loads the bundled Barrio 67 Omega POS recipe book (187 menu dishes, 72 production sub-recipes,
     * and 265 raw POS ingredients) into the currently active kitchen/workgroup idempotently.
     */
    fun importBarrio67RecipeBook() {
        if (_barrioBookImportBusy.value) return
        val state = uiState.value
        if (!BarrioRecipeBookImporter.isAuthorizedForWorkspace(currentUserEmail, state.isViewingJoinedWorkgroup)) {
            return
        }
        val workspaceId = activeWorkspaceId()
        val targetFoodCost = state.profile?.targetFoodCostPct ?: 30.0
        viewModelScope.launch {
            _barrioBookImportBusy.value = true
            try {
                val book = withContext(Dispatchers.IO) {
                    BarrioRecipeBookImporter.loadBook(appContext)
                }
                repository.importBundledRecipeBook(
                    book = book,
                    existingInventory = state.inventory,
                    existingRecipes = state.recipes,
                    targetFoodCostPct = targetFoodCost,
                    workspaceOwnerId = workspaceId
                ).onSuccess { result ->
                    _snackbarMessage.value = if (result.totalCreated == 0) {
                        "Barrio 67 recipe book is already loaded in this kitchen"
                    } else {
                        "Loaded Barrio 67: ${result.createdMenuDishes} dishes, ${result.createdSubRecipes} sub-recipes & ${result.createdInventory} POS ingredients"
                    }
                }.onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to load Barrio 67 recipe book"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbarMessage.value = e.localizedMessage ?: "Could not read Barrio 67 recipe book"
            } finally {
                _barrioBookImportBusy.value = false
            }
        }
    }

    fun generateRecipesPdfBytes(recipesToExport: List<Recipe>): ByteArray {
        val state = uiState.value
        return recipeDocumentService.generateRecipesPdfBytes(
            kitchenName = state.profile?.kitchenName?.ifBlank { "ChefsMate Kitchen" } ?: "ChefsMate Kitchen",
            recipesToExport = recipesToExport,
            allRecipes = state.recipes,
            inventory = state.inventory,
            recipeReports = state.recipeReports,
            subRecipeReports = state.subRecipeReports,
            currencySymbol = state.profile?.currencySymbol ?: "$"
        )
    }

    fun generateRecipesXlsxBytes(recipesToExport: List<Recipe>): ByteArray {
        val state = uiState.value
        return recipeDocumentService.generateRecipesXlsxBytes(
            recipesToExport = recipesToExport,
            allRecipes = state.recipes,
            inventory = state.inventory,
            recipeReports = state.recipeReports,
            subRecipeReports = state.subRecipeReports,
            currencySymbol = state.profile?.currencySymbol ?: "$"
        )
    }

    // --- POS Integration (Omega POS, BIM POS, Square, Toast, Clover & CSV Z-Report) ---

    fun saveKitchenSettings(
        kitchenName: String,
        currencySymbol: String,
        targetFoodCostPct: Double,
        lowStockAlertsEnabled: Boolean,
        posProvider: PosProvider,
        posEndpointUrl: String,
        posBearerToken: String
    ) {
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            preferencesRepository.setPosApiToken(posBearerToken)
            if (uiState.value.isViewingJoinedWorkgroup) {
                _snackbarMessage.value = "POS API token saved locally (group settings are managed by owner)"
                return@launch
            }
            repository.updateKitchenProfile(
                kitchenName = kitchenName,
                currencySymbol = currencySymbol,
                targetFoodCostPct = targetFoodCostPct,
                lowStockAlertsEnabled = lowStockAlertsEnabled,
                posProvider = posProvider,
                posEndpointUrl = posEndpointUrl,
                workspaceId = workspaceId
            ).onSuccess {
                _snackbarMessage.value = "Group & ${posProvider.displayName} settings saved"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to update settings"
            }
        }
    }

    fun syncLivePosEndpoint() {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        val provider = PosProvider.fromRaw(state.profile?.posProvider)
        val endpoint = state.profile?.posEndpointUrl.orEmpty()
        if (endpoint.isBlank()) {
            _snackbarMessage.value = "Enter a live ${provider.displayName} Endpoint URL first"
            return
        }
        viewModelScope.launch {
            _posSyncBusy.value = true
            val fetchResult = posSyncService.fetchLivePosSales(
                endpointUrl = endpoint,
                bearerToken = state.preferences.posApiToken,
                provider = provider
            )
            fetchResult.onSuccess { sales ->
                val matched = posSyncService.matchSalesToRecipes(sales, state.menuRecipes)
                if (matched.isEmpty()) {
                    _snackbarMessage.value = "${provider.displayName} reachable (${sales.size} items), but no PLU/SKUs or names matched your menu dishes"
                } else {
                    repository.applyPosSalesDeductions(
                        provider = provider,
                        syncMode = "API_ENDPOINT",
                        soldRecipes = matched,
                        inventory = state.inventory,
                        allRecipes = state.recipes,
                        loggedByRole = state.activePermissions.role,
                        staffName = state.activePermissions.activeStaffName,
                        workspaceOwnerId = workspaceId
                    ).onSuccess { record ->
                        _snackbarMessage.value = "${provider.displayName} synced: ${record.summary}"
                    }.onFailure { err ->
                        _snackbarMessage.value = err.localizedMessage ?: "Failed to deduct POS inventory"
                    }
                }
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "${provider.displayName} endpoint sync failed"
            }
            _posSyncBusy.value = false
        }
    }

    /**
     * Imports a local Omega POS / BIM POS BackOffice Item Sales CSV or JSON Z-Report export,
     * matches PLU/ItemCode or Dish Name to menu recipes, and deducts raw ingredients (including sub-recipes).
     */
    fun importPosZReportFile(rawReportText: String, selectedProvider: PosProvider) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        val trimmed = rawReportText.trim()
        if (trimmed.isEmpty()) {
            _snackbarMessage.value = "Paste or select a POS Z-Report CSV / JSON export first"
            return
        }
        viewModelScope.launch {
            _posSyncBusy.value = true
            val parsedSales = runCatching {
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    posSyncService.parseSalesJson(trimmed)
                } else {
                    posSyncService.parseSalesCsvOrReport(trimmed)
                }
            }.getOrElse { emptyList() }

            if (parsedSales.isEmpty()) {
                _snackbarMessage.value = "No sold items detected. Use format: PLU, ItemName, QtySold"
                _posSyncBusy.value = false
                return@launch
            }

            val matched = posSyncService.matchSalesToRecipes(parsedSales, state.menuRecipes)
            if (matched.isEmpty()) {
                _snackbarMessage.value = "Parsed ${parsedSales.size} POS row(s), but none matched your dish POS SKU/PLU or name"
                _posSyncBusy.value = false
                return@launch
            }

            val provider = if (selectedProvider == PosProvider.NONE) PosProvider.OMEGA_POS else selectedProvider
            repository.applyPosSalesDeductions(
                provider = provider,
                syncMode = "Z_REPORT_BATCH",
                soldRecipes = matched,
                inventory = state.inventory,
                allRecipes = state.recipes,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess { record ->
                _snackbarMessage.value = "${provider.displayName} Z-Report imported: ${record.summary}"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "POS Z-Report import failed"
            }
            _posSyncBusy.value = false
        }
    }

    fun recordPosShiftBatch(soldCountsByRecipeId: Map<String, Int>) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        val pairs = soldCountsByRecipeId.mapNotNull { (id, count) ->
            if (count <= 0) null
            else state.menuRecipes.firstOrNull { it.id == id }?.let { it to count }
        }
        if (pairs.isEmpty()) {
            _snackbarMessage.value = "Select at least one sold plate to deduct"
            return
        }
        viewModelScope.launch {
            _posSyncBusy.value = true
            repository.applyPosSalesDeductions(
                provider = PosProvider.fromRaw(state.profile?.posProvider).let {
                    if (it == PosProvider.NONE) PosProvider.OMEGA_POS else it
                },
                syncMode = "Z_REPORT_BATCH",
                soldRecipes = pairs,
                inventory = state.inventory,
                allRecipes = state.recipes,
                loggedByRole = state.activePermissions.role,
                staffName = state.activePermissions.activeStaffName,
                workspaceOwnerId = workspaceId
            ).onSuccess { record ->
                _snackbarMessage.value = "Shift sales synced: ${record.summary}"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Shift deduction failed"
            }
            _posSyncBusy.value = false
        }
    }

    // --- Staff RBAC ---

    fun deleteStaffMember(staffId: String) {
        val state = uiState.value
        val workspaceId = activeWorkspaceId()
        viewModelScope.launch {
            workgroupRepository.removeStaffAndRevokeAccess(
                workspaceId = workspaceId,
                staffId = staffId,
                members = state.workgroupMembers,
                invites = state.sentInvites
            ).onSuccess {
                _snackbarMessage.value = "Staff removed & device access revoked"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Delete failed"
            }
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
        isViewingJoinedWorkgroup: Boolean,
        myMembership: WorkgroupMember?,
        ownerName: String
    ): ActiveRolePermissions {
        if (isViewingJoinedWorkgroup) {
            return if (myMembership != null && myMembership.active) {
                ActiveRolePermissions(
                    role = myMembership.roleEnum,
                    activeStaffName = myMembership.fullName.ifBlank { currentUserName },
                    canViewReports = myMembership.canViewReports,
                    canExportCsv = myMembership.canExportCsv,
                    canApproveScans = myMembership.canApproveScans,
                    canEditRecipes = myMembership.canEditRecipes,
                    canManagePos = myMembership.canManagePos
                )
            } else {
                ActiveRolePermissions(
                    role = StaffRole.LINE_COOK,
                    activeStaffName = currentUserName,
                    canViewReports = false,
                    canExportCsv = false,
                    canApproveScans = false,
                    canEditRecipes = false,
                    canManagePos = false
                )
            }
        }

        // Owned group: manager always holds full manager authority (roles are strictly manager-controlled)
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

    private fun buildUrgentAlerts(
        inventory: List<InventoryItem>,
        subRecipes: List<Recipe>,
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
        subRecipes.filter { it.lowProductionStock }.forEach { sub ->
            list.add(
                UrgentAlert(
                    id = "low_prod_${sub.id}_${String.format(Locale.US, "%.2f", sub.productionStock)}",
                    title = "Low Production: ${sub.nameEn}${if (sub.nameAr.isNotBlank()) " (${sub.nameAr})" else ""}",
                    detail = "${String.format(Locale.US, "%.2f", sub.productionStock)} ${sub.yieldUnit} prepared (min ${String.format(Locale.US, "%.2f", sub.minProductionStock)} ${sub.yieldUnit})",
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

    private data class WorkspaceAccess(
        val workspaceId: String,
        val isOwned: Boolean,
        val ownedGroup: Workgroup?,
        val membership: WorkgroupMember?
    )

    private data class CoreBundle(
        val ownerId: String,
        val isOwned: Boolean,
        val activeWorkgroup: Workgroup?,
        val myMembership: WorkgroupMember?,
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

    private data class WorkgroupBundle(
        val ownedWorkgroups: List<Workgroup>,
        val joinedWorkgroups: List<JoinedWorkgroup>,
        val workgroupMembers: List<WorkgroupMember>,
        val sentInvites: List<WorkgroupInvite>,
        val incomingEmailInvites: List<WorkgroupInvite>
    )

    private data class InviteDialogBundle(
        val latestCreatedInvite: WorkgroupInvite?,
        val lookedUpInvite: WorkgroupInvite?,
        val inviteLookupBusy: Boolean,
        val inviteLookupError: String?,
        val groupActionBusy: Boolean,
        val groupActionError: String?
    )
}
