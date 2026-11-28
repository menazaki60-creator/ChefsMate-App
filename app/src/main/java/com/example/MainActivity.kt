package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.InvoiceScannerService
import com.example.data.KitchenRepository
import com.example.data.LocalAppPreferences
import com.example.data.PosSyncService
import com.example.data.PreferencesRepository
import com.example.data.WorkgroupRepository
import com.example.firebase.configureAppCheck
import com.example.firebase.signOutGoogle
import com.example.i18n.stringsFor
import com.example.ui.components.SyncStatusBadge
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ConfirmWorkgroupInviteDialog
import com.example.ui.screens.CreateOrEditWorkgroupDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.GroupLogoAvatar
import com.example.ui.screens.GroupSwitcherDialog
import com.example.ui.screens.KitchenSettingsScreen
import com.example.ui.screens.RecipesScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.ShareWorkgroupInviteDialog
import com.example.ui.screens.StockScreen
import com.example.ui.theme.ChefsMateTheme
import com.example.viewmodel.KitchenViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

private enum class ChefTab(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    STOCK("stock", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    SCAN("scan", Icons.Filled.DocumentScanner, Icons.Outlined.DocumentScanner),
    RECIPES("recipes", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    INSIGHTS("insights", Icons.Filled.Analytics, Icons.Outlined.Analytics),
    KITCHEN("kitchen", Icons.Filled.Tune, Icons.Outlined.Tune)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent BEFORE any UI or Firebase ops
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            ChefsMateRoot()
        }
    }
}

@Composable
private fun ChefsMateRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsRepo = remember(context) { PreferencesRepository(context.applicationContext) }
    val localPrefs by prefsRepo.preferencesFlow.collectAsStateWithLifecycle(initialValue = LocalAppPreferences())

    val auth = remember { Firebase.auth }
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val language = localPrefs.language
    val strings = remember(language) { stringsFor(language) }
    val layoutDir = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        ChefsMateTheme(
            darkTheme = localPrefs.darkMode,
            isArabic = language.isRtl
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                val user = currentUser
                if (user == null) {
                    AuthScreen(
                        strings = strings,
                        currentLanguage = language,
                        darkMode = localPrefs.darkMode,
                        onSelectLanguage = { lang -> scope.launch { prefsRepo.setLanguage(lang) } },
                        onToggleDarkMode = { dark -> scope.launch { prefsRepo.setDarkMode(dark) } },
                        onAuthSuccess = { currentUser = auth.currentUser }
                    )
                } else {
                    // Key by UID so account switching tears down and recreates Firestore listeners cleanly
                    key(user.uid) {
                        AuthenticatedKitchenShell(
                            currentUserId = user.uid,
                            currentUserName = user.displayName ?: user.email ?: "Head Chef",
                            currentUserEmail = user.email
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedKitchenShell(
    currentUserId: String,
    currentUserName: String,
    currentUserEmail: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: KitchenViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val db = KitchenRepository.createConfiguredFirestore(app)
                KitchenViewModel(
                    repository = KitchenRepository(db),
                    workgroupRepository = WorkgroupRepository(db),
                    preferencesRepository = PreferencesRepository(app),
                    scannerService = InvoiceScannerService(),
                    posSyncService = PosSyncService(),
                    currentUserId = currentUserId,
                    currentUserName = currentUserName,
                    currentUserEmail = currentUserEmail,
                    appContext = app
                )
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = remember(uiState.preferences.language) { stringsFor(uiState.preferences.language) }
    val snackbarHostState = remember { SnackbarHostState() }

    var activeTab by rememberSaveable { mutableStateOf(ChefTab.STOCK) }
    var showGroupSwitcher by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showEditGroupDialog by remember { mutableStateOf(false) }

    // Support system back gesture from secondary tabs back to Stock
    BackHandler(enabled = activeTab != ChefTab.STOCK) {
        activeTab = ChefTab.STOCK
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearStreamError()
        }
    }

    fun tabLabel(tab: ChefTab): String = when (tab) {
        ChefTab.STOCK -> strings.navStock
        ChefTab.SCAN -> strings.navScan
        ChefTab.RECIPES -> strings.navRecipes
        ChefTab.INSIGHTS -> strings.navInsights
        ChefTab.KITCHEN -> strings.navKitchen
    }

    val activeGroupName = uiState.activeWorkgroup?.name?.ifBlank { uiState.profile?.kitchenName }
        ?: uiState.profile?.kitchenName?.ifBlank { strings.appTitle }
        ?: strings.appTitle
    val activeGroupLogo = uiState.activeWorkgroup?.logoBase64 ?: uiState.profile?.logoBase64

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        title = {
                            // Minimal 1-tap Group Switcher trigger
                            Surface(
                                onClick = { showGroupSwitcher = true },
                                color = MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("top_group_switcher_pill")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    GroupLogoAvatar(
                                        logoBase64 = activeGroupLogo,
                                        fallbackName = activeGroupName,
                                        size = 34.dp
                                    )
                                    Column(modifier = Modifier.weight(1f, fill = false)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = activeGroupName,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Switch kitchen group",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        val activeHandle = uiState.activeWorkgroup?.resolvedUsername.orEmpty()
                                        Text(
                                            text = buildString {
                                                if (activeHandle.isNotBlank()) append("@$activeHandle · ")
                                                if (uiState.isViewingJoinedWorkgroup) append("Team · ")
                                                append(uiState.activePermissions.role.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                                                val totalGroups = uiState.ownedWorkgroups.size + uiState.joinedWorkgroups.size
                                                if (totalGroups > 1) append(" · $totalGroups groups")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (uiState.isViewingJoinedWorkgroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            SyncStatusBadge(
                                syncStatus = uiState.syncStatus,
                                strings = strings,
                                modifier = Modifier.padding(end = 14.dp)
                            )
                        }
                    )
                    androidx.compose.material3.HorizontalDivider(
                        thickness = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            },
            bottomBar = {
                if (!isExpanded) {
                    Column {
                        androidx.compose.material3.HorizontalDivider(
                            thickness = 0.75.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            ChefTab.entries.forEach { tab ->
                                val selected = activeTab == tab
                                val badgeCount = when (tab) {
                                    ChefTab.SCAN -> uiState.pendingFlaggedScans.size
                                    ChefTab.KITCHEN -> uiState.incomingEmailInvites.size
                                    else -> 0
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { activeTab = tab },
                                    modifier = Modifier.testTag("nav_${tab.route}"),
                                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ),
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (badgeCount > 0) {
                                                    Badge { Text(badgeCount.toString()) }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tabLabel(tab),
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = tabLabel(tab),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpanded) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        ChefTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            val badgeCount = when (tab) {
                                ChefTab.SCAN -> uiState.pendingFlaggedScans.size
                                ChefTab.KITCHEN -> uiState.incomingEmailInvites.size
                                else -> 0
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                modifier = Modifier.testTag("rail_${tab.route}"),
                                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ),
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (badgeCount > 0) {
                                                Badge { Text(badgeCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tabLabel(tab)
                                        )
                                    }
                                },
                                label = { Text(tabLabel(tab)) }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1040.dp)
                    ) {
                        if (uiState.loading) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Incoming Email Invite Banner: ALWAYS asks for confirmation & Group Code before joining
                                val firstIncomingInvite = uiState.incomingEmailInvites.firstOrNull()
                                if (firstIncomingInvite != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .testTag("top_incoming_invite_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                GroupLogoAvatar(
                                                    logoBase64 = firstIncomingInvite.logoBase64,
                                                    fallbackName = firstIncomingInvite.kitchenName,
                                                    size = 34.dp
                                                )
                                                Column {
                                                    Text(
                                                        text = "Email Invite: ${firstIncomingInvite.kitchenName}",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                    Text(
                                                        text = "From ${firstIncomingInvite.invitedByName} • ${firstIncomingInvite.role.replace('_', ' ')}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                                    )
                                                }
                                            }
                                            Button(
                                                onClick = { viewModel.openConfirmEmailInvite(firstIncomingInvite) },
                                                enabled = !uiState.inviteLookupBusy,
                                                modifier = Modifier.testTag("top_banner_review_invite_btn")
                                            ) {
                                                Text("Review")
                                            }
                                        }
                                    }
                                }

                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    when (activeTab) {
                                        ChefTab.STOCK -> StockScreen(
                                            uiState = uiState,
                                            strings = strings,
                                            isExpanded = isExpanded,
                                            onAddOrMergeItem = viewModel::addOrMergeItem,
                                            onAddBasicIngredients = viewModel::addBasicIngredients,
                                            onAdjustRawStock = viewModel::adjustRawItemStock,
                                            onLogWaste = viewModel::logWaste,
                                            onDeleteItem = viewModel::deleteInventoryItem,
                                            onProduceSubRecipe = viewModel::produceSubRecipeBatch,
                                            onAdjustProductionStock = viewModel::adjustProductionStock,
                                            onNavigateToRecipes = { activeTab = ChefTab.RECIPES },
                                            onNavigateToAlertsTarget = {
                                                activeTab = if (uiState.pendingFlaggedScans.isNotEmpty()) ChefTab.SCAN else ChefTab.INSIGHTS
                                            }
                                        )

                                        ChefTab.SCAN -> ScannerScreen(
                                            uiState = uiState,
                                            strings = strings,
                                            onScanUri = viewModel::scanInvoiceUri,
                                            onScanError = viewModel::reportScanError,
                                            onScanText = viewModel::scanInvoiceText,
                                            onRetryWithCloudAi = viewModel::rescanLastInvoiceWithCloudAi,
                                            onUpdateScannedLine = viewModel::updateScannedLine,
                                            onRemoveScannedLine = viewModel::removeScannedLine,
                                            onConvertAllPricesToUsd = viewModel::convertAllScannedPricesToUsd,
                                            onCommitScannedLines = viewModel::commitScannedLines,
                                            onResetScan = viewModel::resetScanState,
                                            onApproveFlagged = viewModel::approveFlaggedScan,
                                            onDismissFlagged = viewModel::dismissFlaggedScan
                                        )

                                        ChefTab.RECIPES -> RecipesScreen(
                                            uiState = uiState,
                                            strings = strings,
                                            isExpanded = isExpanded,
                                            onSaveRecipe = viewModel::saveRecipe,
                                            onDeleteRecipe = viewModel::deleteRecipe,
                                            onImportRecipesUri = viewModel::importRecipesFromUri,
                                            onRetryRecipeImportWithAi = viewModel::retryRecipeImportWithCloudAi,
                                            onCommitImportedRecipes = viewModel::commitImportedRecipes,
                                            onResetRecipeImport = viewModel::resetRecipeImport,
                                            onImportBarrio67Book = viewModel::importBarrio67RecipeBook,
                                            onGenerateRecipesPdf = viewModel::generateRecipesPdfBytes,
                                            onGenerateRecipesXlsx = viewModel::generateRecipesXlsxBytes,
                                            onFeedbackMessage = viewModel::showMessage
                                        )

                                        ChefTab.INSIGHTS -> DashboardScreen(
                                            uiState = uiState,
                                            strings = strings,
                                            isExpanded = isExpanded,
                                            onGenerateCsv = viewModel::generateCsv,
                                            onCsvFeedback = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                        )

                                        ChefTab.KITCHEN -> KitchenSettingsScreen(
                                            uiState = uiState,
                                            strings = strings,
                                            onToggleDarkMode = viewModel::toggleDarkMode,
                                            onSelectLanguage = viewModel::setLanguage,
                                            onSaveKitchenPosSettings = viewModel::saveKitchenSettings,
                                            onSyncLivePos = viewModel::syncLivePosEndpoint,
                                            onImportPosZReport = viewModel::importPosZReportFile,
                                            onRecordShiftSales = viewModel::recordPosShiftBatch,
                                            onOpenCreateGroup = { showCreateGroupDialog = true },
                                            onOpenEditActiveGroup = { showEditGroupDialog = true },
                                            onCreateStaffAndInvite = viewModel::createStaffAndInvite,
                                            onUpdateStaffRole = viewModel::updateStaffRoleAndPermissions,
                                            onShareExistingInvite = viewModel::showInviteShareSheet,
                                            onRegenerateInviteForStaff = viewModel::regenerateInviteForStaff,
                                            onDeleteStaff = viewModel::deleteStaffMember,
                                            onSwitchWorkspace = viewModel::switchWorkspace,
                                            onReviewEmailInvite = viewModel::openConfirmEmailInvite,
                                            onDeclineInvite = viewModel::declineInvite,
                                            onLeaveWorkgroup = viewModel::leaveWorkgroup,
                                            onSignOut = {
                                                signOutGoogle(
                                                    credentialManager = credentialManager,
                                                    scope = scope
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 1–2 Click Multi-Group Switcher Modal
    if (showGroupSwitcher) {
        GroupSwitcherDialog(
            ownedGroups = uiState.ownedWorkgroups,
            joinedGroups = uiState.joinedWorkgroups,
            activeWorkspaceId = uiState.activeKitchenOwnerId.ifBlank { currentUserId },
            fallbackOwnKitchenName = uiState.profile?.kitchenName ?: "Main Kitchen",
            fallbackOwnLogoBase64 = uiState.profile?.logoBase64,
            primaryUserId = currentUserId,
            onSelectGroup = viewModel::switchWorkspace,
            onCreateNewGroup = { showCreateGroupDialog = true },
            onEditActiveGroup = { showEditGroupDialog = true },
            onDismiss = { showGroupSwitcher = false }
        )
    }

    // Create New Group with Unique @username + Optional Logo Upload
    if (showCreateGroupDialog) {
        CreateOrEditWorkgroupDialog(
            existingGroup = null,
            busy = uiState.groupActionBusy,
            errorText = uiState.groupActionError,
            onClearError = viewModel::clearGroupActionError,
            onSubmit = { name, username, currency, logoUri ->
                viewModel.createNewWorkgroup(
                    name = name,
                    username = username,
                    currencySymbol = currency,
                    logoUri = logoUri
                ) { res ->
                    if (res.isSuccess) showCreateGroupDialog = false
                }
            },
            onDismiss = {
                viewModel.clearGroupActionError()
                showCreateGroupDialog = false
            }
        )
    }

    // Rename Active Group, Edit Unique @username & Logo (Manager Only)
    if (showEditGroupDialog) {
        val currentGroup = uiState.activeWorkgroup ?: com.example.model.Workgroup(
            id = currentUserId,
            ownerUserId = currentUserId,
            name = activeGroupName,
            normalizedName = com.example.data.WorkgroupInviteHelper.suggestUsernameFromName(activeGroupName, currentUserId.take(4)),
            username = com.example.data.WorkgroupInviteHelper.suggestUsernameFromName(activeGroupName, currentUserId.take(4)),
            currencySymbol = uiState.profile?.currencySymbol ?: "$",
            logoBase64 = activeGroupLogo,
            primaryKitchen = true
        )
        CreateOrEditWorkgroupDialog(
            existingGroup = currentGroup,
            busy = uiState.groupActionBusy,
            errorText = uiState.groupActionError,
            onClearError = viewModel::clearGroupActionError,
            onSubmit = { name, username, _, logoUri ->
                viewModel.updateActiveGroupBranding(
                    newName = name,
                    newUsername = username,
                    newLogoUri = logoUri
                ) { res ->
                    if (res.isSuccess) showEditGroupDialog = false
                }
            },
            onDismiss = {
                viewModel.clearGroupActionError()
                showEditGroupDialog = false
            }
        )
    }

    // Manager Email Invite Share Sheet (strictly email-only + Group Code)
    uiState.latestCreatedInvite?.let { invite ->
        ShareWorkgroupInviteDialog(
            invite = invite,
            onDismiss = viewModel::dismissCreatedInvite
        )
    }

    // Invited Staff Explicit Confirmation + Group Verification Code Dialog
    uiState.lookedUpInvite?.let { pendingInvite ->
        ConfirmWorkgroupInviteDialog(
            invite = pendingInvite,
            busy = uiState.inviteLookupBusy,
            errorText = uiState.inviteLookupError,
            onConfirmWithCode = { invite, enteredCode ->
                viewModel.acceptInviteWithCode(invite, enteredCode)
            },
            onDecline = { invite ->
                viewModel.declineInvite(invite)
            },
            onDismiss = viewModel::clearLookedUpInvite
        )
    }
}
