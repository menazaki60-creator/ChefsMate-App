package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.example.firebase.authStateFlow
import com.example.firebase.configureAppCheck
import com.example.firebase.signOutGoogle
import com.example.i18n.stringsFor
import com.example.model.StaffRole
import com.example.ui.components.ManagerPinDialog
import com.example.ui.components.SyncStatusBadge
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.KitchenSettingsScreen
import com.example.ui.screens.LedgerScreen
import com.example.ui.screens.RecipesScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.theme.ChefLedgerTheme
import com.example.viewmodel.KitchenViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

private enum class ChefTab(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    LEDGER("ledger", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
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
            ChefLedgerRoot()
        }
    }
}

@Composable
private fun ChefLedgerRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsRepo = remember(context) { PreferencesRepository(context.applicationContext) }
    val localPrefs by prefsRepo.preferencesFlow.collectAsStateWithLifecycle(initialValue = LocalAppPreferences())

    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val language = localPrefs.language
    val strings = remember(language) { stringsFor(language) }
    val layoutDir = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        ChefLedgerTheme(
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
                        onAuthSuccess = {}
                    )
                } else {
                    // Key by UID so account switching tears down and recreates Firestore listeners cleanly
                    key(user.uid) {
                        AuthenticatedKitchenShell(
                            currentUserId = user.uid,
                            currentUserName = user.displayName ?: user.email ?: "Head Chef"
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
    currentUserName: String
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
                    preferencesRepository = PreferencesRepository(app),
                    scannerService = InvoiceScannerService(),
                    posSyncService = PosSyncService(),
                    currentUserId = currentUserId,
                    currentUserName = currentUserName,
                    appContext = app
                )
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = remember(uiState.preferences.language) { stringsFor(uiState.preferences.language) }
    val snackbarHostState = remember { SnackbarHostState() }

    var activeTab by remember { mutableStateOf(ChefTab.LEDGER) }
    var showManagerPinPrompt by remember { mutableStateOf(false) }

    // Support system back gesture from secondary tabs back to Ledger
    BackHandler(enabled = activeTab != ChefTab.LEDGER) {
        activeTab = ChefTab.LEDGER
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
        ChefTab.LEDGER -> strings.navLedger
        ChefTab.SCAN -> strings.navScan
        ChefTab.RECIPES -> strings.navRecipes
        ChefTab.INSIGHTS -> strings.navInsights
        ChefTab.KITCHEN -> strings.navKitchen
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_chefsmate_icon),
                                contentDescription = strings.appTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Column {
                                Text(
                                    text = uiState.profile?.kitchenName?.ifBlank { strings.appTitle } ?: strings.appTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${uiState.activePermissions.activeStaffName} • ${uiState.activePermissions.role.name.replace('_', ' ')}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        SyncStatusBadge(
                            syncStatus = uiState.syncStatus,
                            strings = strings,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
            },
            bottomBar = {
                if (!isExpanded) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        ChefTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            val badgeCount = if (tab == ChefTab.SCAN) uiState.pendingFlaggedScans.size else 0
                            NavigationBarItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                modifier = Modifier.testTag("nav_${tab.route}"),
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
                                label = {
                                    Text(
                                        text = tabLabel(tab),
                                        maxLines = 1
                                    )
                                }
                            )
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
                // Canonical Adaptive NavigationRail on tablets, foldables & desktops
                if (isExpanded) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        ChefTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            val badgeCount = if (tab == ChefTab.SCAN) uiState.pendingFlaggedScans.size else 0
                            NavigationRailItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                modifier = Modifier.testTag("rail_${tab.route}"),
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

                // Centered fluid content pane on wide screens
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
                            when (activeTab) {
                                ChefTab.LEDGER -> LedgerScreen(
                                    uiState = uiState,
                                    strings = strings,
                                    isExpanded = isExpanded,
                                    onAddOrMergeItem = viewModel::addOrMergeItem,
                                    onLogWaste = viewModel::logWaste,
                                    onDeleteItem = viewModel::deleteInventoryItem,
                                    onNavigateToAlertsTarget = {
                                        activeTab = if (uiState.pendingFlaggedScans.isNotEmpty()) ChefTab.SCAN else ChefTab.INSIGHTS
                                    }
                                )

                                ChefTab.SCAN -> ScannerScreen(
                                    uiState = uiState,
                                    strings = strings,
                                    onScanBitmap = viewModel::scanInvoiceImage,
                                    onScanText = viewModel::scanInvoiceText,
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
                                    onDeleteRecipe = viewModel::deleteRecipe
                                )

                                ChefTab.INSIGHTS -> DashboardScreen(
                                    uiState = uiState,
                                    strings = strings,
                                    isExpanded = isExpanded,
                                    onGenerateCsv = viewModel::generateCsv,
                                    onCsvFeedback = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                    onRequestManagerUnlock = { showManagerPinPrompt = true }
                                )

                                ChefTab.KITCHEN -> KitchenSettingsScreen(
                                    uiState = uiState,
                                    strings = strings,
                                    onToggleDarkMode = viewModel::toggleDarkMode,
                                    onSelectLanguage = viewModel::setLanguage,
                                    onSwitchTerminalRole = viewModel::switchTerminalRole,
                                    onRequestManagerUnlock = { showManagerPinPrompt = true },
                                    onSetManagerPin = viewModel::setManagerPin,
                                    onSaveKitchenPosSettings = viewModel::saveKitchenSettings,
                                    onSyncLivePos = viewModel::syncLivePosEndpoint,
                                    onRecordShiftSales = viewModel::recordPosShiftBatch,
                                    onSaveStaff = viewModel::saveStaffMember,
                                    onDeleteStaff = viewModel::deleteStaffMember,
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

    if (showManagerPinPrompt) {
        ManagerPinDialog(
            onDismiss = { showManagerPinPrompt = false },
            onVerify = viewModel::verifyManagerPin,
            onUnlocked = {
                showManagerPinPrompt = false
                viewModel.switchTerminalRole(StaffRole.MANAGER, "")
            }
        )
    }
}
