package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.i18n.AppStrings
import com.example.model.AppLanguage
import com.example.model.PosProvider
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.ui.components.formatMoney
import com.example.viewmodel.KitchenUiState

@Composable
fun KitchenSettingsScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    onToggleDarkMode: (Boolean) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSwitchTerminalRole: (StaffRole, String) -> Unit,
    onRequestManagerUnlock: () -> Unit,
    onSetManagerPin: (String) -> Unit,
    onSaveKitchenPosSettings: (
        kitchenName: String,
        currency: String,
        targetFoodCost: Double,
        alertsEnabled: Boolean,
        posProvider: PosProvider,
        posUrl: String,
        posToken: String
    ) -> Unit,
    onSyncLivePos: () -> Unit,
    onRecordShiftSales: (Map<String, Int>) -> Unit,
    onSaveStaff: (String?, StaffMember) -> Unit,
    onDeleteStaff: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val profile = uiState.profile
    val currency = profile?.currencySymbol ?: "$"
    val isManager = uiState.activePermissions.role == StaffRole.MANAGER

    var kitchenName by remember(profile?.kitchenName) { mutableStateOf(profile?.kitchenName ?: "Main Kitchen") }
    var currencyInput by remember(profile?.currencySymbol) { mutableStateOf(profile?.currencySymbol ?: "$") }
    var alertsEnabled by remember(profile?.lowStockAlertsEnabled) { mutableStateOf(profile?.lowStockAlertsEnabled ?: true) }
    var posProvider by remember(profile?.posProvider) { mutableStateOf(PosProvider.fromRaw(profile?.posProvider)) }
    var posUrl by remember(profile?.posEndpointUrl) { mutableStateOf(profile?.posEndpointUrl ?: "") }
    var posToken by remember(uiState.preferences.posApiToken) { mutableStateOf(uiState.preferences.posApiToken) }

    var showStaffDialog by remember { mutableStateOf(false) }
    var showPosShiftDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("kitchen_settings_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "settings_title") {
            Text(
                text = strings.kitchenSettings,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. Display, Dark Mode & Multilingual Switcher
        item(key = "appearance_and_language") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.DarkMode, contentDescription = strings.darkMode, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(strings.darkMode, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("High-contrast visibility for kitchen lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = uiState.preferences.darkMode,
                            onCheckedChange = onToggleDarkMode,
                            modifier = Modifier.testTag("dark_mode_switch")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Translate, contentDescription = strings.languageLabel, tint = MaterialTheme.colorScheme.secondary)
                        Text(strings.languageLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = uiState.preferences.language == lang,
                                onClick = { onSelectLanguage(lang) },
                                label = { Text(lang.displayLabel) },
                                modifier = Modifier.testTag("lang_chip_${lang.code}")
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Real-time push alert permission
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = strings.urgentAlerts, tint = MaterialTheme.colorScheme.tertiary)
                            Column {
                                Text(strings.urgentAlerts, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Real-time low stock & waste spike alerts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = alertsEnabled,
                            onCheckedChange = { enabled ->
                                alertsEnabled = enabled
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                onSaveKitchenPosSettings(
                                    kitchenName,
                                    currencyInput,
                                    profile?.targetFoodCostPct ?: 30.0,
                                    enabled,
                                    posProvider,
                                    posUrl,
                                    posToken
                                )
                            },
                            modifier = Modifier.testTag("alerts_enabled_switch")
                        )
                    }
                }
            }
        }

        // 2. Role-Based Access Control (RBAC) & Active Terminal Mode
        item(key = "rbac_section") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = strings.staffRolesAndAccess, tint = MaterialTheme.colorScheme.primary)
                            Text(strings.staffRolesAndAccess, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (isManager) {
                            FilledTonalButton(
                                onClick = { showStaffDialog = true },
                                modifier = Modifier.testTag("add_staff_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = strings.addStaffMember, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.addStaffMember)
                            }
                        }
                    }

                    Text(
                        text = "Active Terminal Profile: ${uiState.activePermissions.activeStaffName} (${uiState.activePermissions.role.name.replace('_', ' ')})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StaffRole.entries.forEach { role ->
                            FilterChip(
                                selected = uiState.activePermissions.role == role && uiState.preferences.activeStaffId.isBlank(),
                                onClick = {
                                    if (role == StaffRole.MANAGER && !isManager) {
                                        onRequestManagerUnlock()
                                    } else {
                                        onSwitchTerminalRole(role, "")
                                    }
                                },
                                label = { Text(role.name.replace('_', ' ')) },
                                modifier = Modifier.testTag("switch_role_${role.id}")
                            )
                        }
                    }

                    if (isManager) {
                        OutlinedButton(
                            onClick = { showPinSetupDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Security, contentDescription = "Manager PIN", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (profile?.managerPinHash.isNullOrBlank()) "Set Manager Terminal PIN"
                                else "Update / Clear Manager PIN"
                            )
                        }
                    }

                    // Staff Roster with Granular Permissions
                    if (uiState.staffMembers.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        uiState.staffMembers.forEach { staff ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${staff.fullName} • ${staff.role.replace('_', ' ')}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val permsList = buildList {
                                        if (staff.canViewReports) add("Reports")
                                        if (staff.canExportCsv) add("CSV")
                                        if (staff.canApproveScans) add("Review")
                                        if (staff.canEditRecipes) add("Recipes")
                                        if (staff.canManagePos) add("POS")
                                    }.ifEmpty { listOf("Floor Waste/Stock Only") }
                                    Text(
                                        text = permsList.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        if (staff.roleEnum == StaffRole.MANAGER && !isManager) {
                                            onRequestManagerUnlock()
                                        } else {
                                            onSwitchTerminalRole(staff.roleEnum, staff.id)
                                        }
                                    }
                                ) {
                                    Text("Use")
                                }
                                if (isManager) {
                                    IconButton(onClick = { onDeleteStaff(staff.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove staff")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. POS Systems Integration (Real REST Endpoint + Shift Z-Report Deduction)
        item(key = "pos_section") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PointOfSale, contentDescription = strings.posIntegration, tint = MaterialTheme.colorScheme.primary)
                        Text(strings.posIntegration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PosProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = posProvider == provider,
                                onClick = { if (uiState.activePermissions.canManagePos) posProvider = provider },
                                label = { Text(provider.displayName) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = posUrl,
                        onValueChange = { posUrl = it },
                        enabled = uiState.activePermissions.canManagePos,
                        label = { Text("POS Sales REST Endpoint URL") },
                        placeholder = { Text("https://api.yourpos.com/v1/sales/today") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pos_endpoint_input")
                    )

                    OutlinedTextField(
                        value = posToken,
                        onValueChange = { posToken = it },
                        enabled = uiState.activePermissions.canManagePos,
                        label = { Text("Bearer Token / API Key (stored locally)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = kitchenName,
                            onValueChange = { kitchenName = it },
                            enabled = uiState.activePermissions.canManagePos,
                            label = { Text("Kitchen Name") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = currencyInput,
                            onValueChange = { currencyInput = it.take(4) },
                            enabled = uiState.activePermissions.canManagePos,
                            label = { Text("Currency") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uiState.activePermissions.canManagePos) {
                        FilledTonalButton(
                            onClick = {
                                onSaveKitchenPosSettings(
                                    kitchenName,
                                    currencyInput,
                                    profile?.targetFoodCostPct ?: 30.0,
                                    alertsEnabled,
                                    posProvider,
                                    posUrl,
                                    posToken
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_pos_settings_btn")
                        ) {
                            Text("Save Kitchen & POS Config")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSyncLivePos,
                                enabled = !uiState.posSyncBusy && posUrl.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sync_live_pos_btn")
                            ) {
                                if (uiState.posSyncBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = strings.syncPosNow, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.syncPosNow)
                                }
                            }
                            FilledTonalButton(
                                onClick = { showPosShiftDialog = true },
                                enabled = !uiState.posSyncBusy && uiState.recipes.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("record_shift_sales_btn")
                            ) {
                                Text(strings.recordShiftSales)
                            }
                        }
                    } else {
                        Text(
                            text = "POS integration configuration is restricted to managers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Recent POS Sync Audit Logs
                    if (uiState.posSyncs.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Recent POS Syncs", style = MaterialTheme.typography.labelLarge)
                        uiState.posSyncs.take(4).forEach { sync ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sync.summary, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${sync.provider} • ${sync.syncMode}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    formatMoney(sync.totalRevenue, currency),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Sign Out
        item(key = "sign_out_card") {
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = strings.signOut)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.signOut)
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    if (showStaffDialog) {
        AddStaffRoleDialog(
            strings = strings,
            onDismiss = { showStaffDialog = false },
            onSave = { member ->
                onSaveStaff(null, member)
                showStaffDialog = false
            }
        )
    }

    if (showPosShiftDialog) {
        PosShiftSalesDialog(
            uiState = uiState,
            strings = strings,
            onDismiss = { showPosShiftDialog = false },
            onSubmit = { map ->
                onRecordShiftSales(map)
                showPosShiftDialog = false
            }
        )
    }

    if (showPinSetupDialog) {
        SetupManagerPinDialog(
            onDismiss = { showPinSetupDialog = false },
            onSavePin = { pin ->
                onSetManagerPin(pin)
                showPinSetupDialog = false
            }
        )
    }
}

@Composable
private fun AddStaffRoleDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (StaffMember) -> Unit
) {
    var fullName by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(StaffRole.SOUS_CHEF) }
    var canViewReports by remember { mutableStateOf(false) }
    var canExportCsv by remember { mutableStateOf(false) }
    var canApproveScans by remember { mutableStateOf(true) }
    var canEditRecipes by remember { mutableStateOf(true) }
    var canManagePos by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addStaffMember) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Staff Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = station,
                    onValueChange = { station = it },
                    label = { Text("Station (Grill, Pastry, Prep...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StaffRole.entries.forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = {
                                role = r
                                when (r) {
                                    StaffRole.MANAGER -> {
                                        canViewReports = true; canExportCsv = true; canApproveScans = true; canEditRecipes = true; canManagePos = true
                                    }
                                    StaffRole.SOUS_CHEF -> {
                                        canViewReports = false; canExportCsv = false; canApproveScans = true; canEditRecipes = true; canManagePos = false
                                    }
                                    StaffRole.INVENTORY_CLERK -> {
                                        canViewReports = false; canExportCsv = true; canApproveScans = true; canEditRecipes = false; canManagePos = false
                                    }
                                    StaffRole.LINE_COOK -> {
                                        canViewReports = false; canExportCsv = false; canApproveScans = false; canEditRecipes = false; canManagePos = false
                                    }
                                }
                            },
                            label = { Text(r.name.replace('_', ' ')) }
                        )
                    }
                }
                PermissionToggleRow("Manager Financial Reports", canViewReports) { canViewReports = it }
                PermissionToggleRow("Export Weekly/Monthly CSV", canExportCsv) { canExportCsv = it }
                PermissionToggleRow("Approve Flagged Invoice Scans", canApproveScans) { canApproveScans = it }
                PermissionToggleRow("Edit Recipes & Menu Pricing", canEditRecipes) { canEditRecipes = it }
                PermissionToggleRow("Configure POS Sales Sync", canManagePos) { canManagePos = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        StaffMember(
                            fullName = fullName,
                            role = role.id,
                            canViewReports = canViewReports,
                            canExportCsv = canExportCsv,
                            canApproveScans = canApproveScans,
                            canEditRecipes = canEditRecipes,
                            canManagePos = canManagePos,
                            active = true,
                            station = station.takeIf { it.isNotBlank() }
                        )
                    )
                },
                enabled = fullName.isNotBlank()
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun PermissionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PosShiftSalesDialog(
    uiState: KitchenUiState,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, Int>) -> Unit
) {
    val counts = remember { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.recordShiftSales) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Enter plates sold from your POS Z-Report to automatically deduct exact recipe ingredients from live inventory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                uiState.recipes.forEach { recipe ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(recipe.nameEn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (!recipe.posSku.isNullOrBlank()) {
                                Text("SKU: ${recipe.posSku}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedTextField(
                            value = counts[recipe.id] ?: "",
                            onValueChange = { counts[recipe.id] = it.filter { ch -> ch.isDigit() } },
                            placeholder = { Text("0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(86.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = counts.mapValues { it.value.toIntOrNull() ?: 0 }.filterValues { it > 0 }
                    onSubmit(parsed)
                }
            ) {
                Text("Deduct Stock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun SetupManagerPinDialog(
    onDismiss: () -> Unit,
    onSavePin: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manager Terminal PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Set a 4–8 digit PIN to protect Manager-only financial reports when sharing a kitchen tablet. Leave blank to clear.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("PIN (4–8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSavePin(pin) },
                enabled = pin.isEmpty() || pin.length >= 4
            ) {
                Text("Save PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
