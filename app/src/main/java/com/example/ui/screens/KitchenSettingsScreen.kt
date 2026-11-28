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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.WorkgroupInviteHelper
import com.example.i18n.AppStrings
import com.example.model.AppLanguage
import com.example.model.InviteStatus
import com.example.model.PosProvider
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.model.WorkgroupInvite
import com.example.ui.components.formatMoney
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KitchenUiState

@Composable
fun KitchenSettingsScreen(
    uiState: KitchenUiState,
    strings: AppStrings,
    onToggleDarkMode: (Boolean) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
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
    onImportPosZReport: (String, PosProvider) -> Unit,
    onRecordShiftSales: (Map<String, Int>) -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenEditActiveGroup: () -> Unit,
    onCreateStaffAndInvite: (StaffMember) -> Unit,
    onUpdateStaffRole: (
        staffId: String,
        linkedMemberUid: String?,
        role: StaffRole,
        canViewReports: Boolean,
        canExportCsv: Boolean,
        canApproveScans: Boolean,
        canEditRecipes: Boolean,
        canManagePos: Boolean
    ) -> Unit,
    onShareExistingInvite: (WorkgroupInvite) -> Unit,
    onRegenerateInviteForStaff: (StaffMember) -> Unit,
    onDeleteStaff: (String) -> Unit,
    onSwitchWorkspace: (String) -> Unit,
    onReviewEmailInvite: (WorkgroupInvite) -> Unit,
    onDeclineInvite: (WorkgroupInvite) -> Unit,
    onLeaveWorkgroup: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val profile = uiState.profile
    val activeGroup = uiState.activeWorkgroup
    val activeGroupName = activeGroup?.name?.ifBlank { profile?.kitchenName }
        ?: profile?.kitchenName?.ifBlank { "Main Kitchen" } ?: "Main Kitchen"
    val activeGroupLogo = activeGroup?.logoBase64 ?: profile?.logoBase64
    val activeGroupCode = activeGroup?.groupCode?.ifBlank { profile?.groupCode.orEmpty() }
        ?: profile?.groupCode.orEmpty()
    val currency = profile?.currencySymbol ?: "$"
    val isOwnerWorkspace = !uiState.isViewingJoinedWorkgroup
    val isManager = isOwnerWorkspace && uiState.activePermissions.role == StaffRole.MANAGER

    var currencyInput by remember(profile?.currencySymbol) { mutableStateOf(profile?.currencySymbol ?: "$") }
    var alertsEnabled by remember(profile?.lowStockAlertsEnabled) { mutableStateOf(profile?.lowStockAlertsEnabled ?: true) }
    var posProvider by remember(profile?.posProvider) {
        mutableStateOf(
            PosProvider.fromRaw(profile?.posProvider).let {
                if (it == PosProvider.NONE) PosProvider.OMEGA_POS else it
            }
        )
    }
    var posUrl by remember(profile?.posEndpointUrl) { mutableStateOf(profile?.posEndpointUrl ?: "") }
    var posToken by remember(uiState.preferences.posApiToken) { mutableStateOf(uiState.preferences.posApiToken) }

    var showStaffDialog by remember { mutableStateOf(false) }
    var editRoleTarget by remember { mutableStateOf<Pair<StaffMember, String?>?>(null) }
    var showEmailInvitesDialog by remember { mutableStateOf(false) }
    var showPosShiftDialog by remember { mutableStateOf(false) }
    var showPosCsvImportDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("kitchen_settings_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "settings_title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = strings.kitchenSettings,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Groups, email invites, POS & preferences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 0. Multi-Group & Strictly Email-Only Team Workgroup Hub
        item(key = "workgroup_switcher_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Active Group Identity (Logo + Name + Unique @username + Role + Edit)
                    val activeGroupHandle = activeGroup?.resolvedUsername.orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            GroupLogoAvatar(
                                logoBase64 = activeGroupLogo,
                                fallbackName = activeGroupName,
                                size = 46.dp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    text = activeGroupName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (activeGroupHandle.isNotBlank()) {
                                    Text(
                                        text = "@$activeGroupHandle",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = if (uiState.isViewingJoinedWorkgroup) {
                                        "Team Member · ${uiState.activePermissions.role.name.replace('_', ' ')} (set by Manager)"
                                    } else {
                                        "Manager · ${uiState.workgroupMembers.size} member(s) connected"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isManager) {
                            IconButton(
                                onClick = onOpenEditActiveGroup,
                                modifier = Modifier.testTag("edit_active_group_branding_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename Group, @username and Logo",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (isManager) {
                        OutlinedButton(
                            onClick = onOpenEditActiveGroup,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("rename_active_group_btn")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename group and username", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Rename Group, @username & Logo")
                        }
                    }

                    // Manager's Group Verification Code Banner (needed by email-invited staff to confirm joining)
                    if (isManager && activeGroupCode.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "GROUP VERIFICATION CODE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = activeGroupCode,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        WorkgroupInviteHelper.copyToClipboard(context, "Group Code", activeGroupCode)
                                    },
                                    modifier = Modifier.testTag("copy_group_code_btn")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Group Code", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy")
                                }
                            }
                        }
                    }

                    // Manager Multi-Group + Invite Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isManager) {
                            Button(
                                onClick = { showStaffDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("add_staff_btn")
                            ) {
                                Icon(Icons.Default.Email, contentDescription = "Invite by Email", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Invite by Email", maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = onOpenCreateGroup,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("create_new_group_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "New Group", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Group", maxLines = 1)
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onOpenCreateGroup,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("create_new_group_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "New Group", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Create My Group", maxLines = 1)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showEmailInvitesDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("open_email_invites_btn")
                    ) {
                        Icon(Icons.Default.MailOutline, contentDescription = "Email Invitations", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (uiState.incomingEmailInvites.isNotEmpty()) {
                                "Email Invitations (${uiState.incomingEmailInvites.size} Waiting)"
                            } else {
                                "Check My Email Invitations"
                            }
                        )
                    }

                    // Pending Email Invitations: ALWAYS requires explicit confirmation + Group Code entry
                    if (uiState.incomingEmailInvites.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "Email Invitations Waiting for You (${uiState.incomingEmailInvites.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        uiState.incomingEmailInvites.forEach { inv ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        GroupLogoAvatar(
                                            logoBase64 = inv.logoBase64,
                                            fallbackName = inv.kitchenName,
                                            size = 38.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = inv.kitchenName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "From ${inv.invitedByName} • Role: ${inv.role.replace('_', ' ')}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { onReviewEmailInvite(inv) },
                                            enabled = !uiState.inviteLookupBusy,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("review_email_invite_card_${inv.id}")
                                        ) {
                                            Text("Review & Enter Code")
                                        }
                                        OutlinedButton(
                                            onClick = { onDeclineInvite(inv) },
                                            enabled = !uiState.inviteLookupBusy,
                                            modifier = Modifier.testTag("decline_email_invite_card_${inv.id}")
                                        ) {
                                            Text("Decline")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 1-Tap Switcher Chips across ALL owned groups + joined team groups
                    val totalGroups = uiState.ownedWorkgroups.size + uiState.joinedWorkgroups.size
                    if (totalGroups > 1 || uiState.joinedWorkgroups.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "Quick Switch Group (1 Tap)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.ownedWorkgroups.forEach { group ->
                                FilterChip(
                                    selected = uiState.activeKitchenOwnerId == group.id,
                                    onClick = { onSwitchWorkspace(group.id) },
                                    label = { Text(group.name.ifBlank { "Main Kitchen" }) },
                                    modifier = Modifier.testTag("workspace_chip_${group.id}")
                                )
                            }
                            uiState.joinedWorkgroups.forEach { wg ->
                                val targetId = wg.resolvedWorkspaceId
                                FilterChip(
                                    selected = uiState.activeKitchenOwnerId == targetId,
                                    onClick = { onSwitchWorkspace(targetId) },
                                    label = { Text("${wg.kitchenName} (${wg.role.replace('_', ' ')})") },
                                    modifier = Modifier.testTag("workspace_chip_$targetId")
                                )
                            }
                        }
                    }

                    if (uiState.isViewingJoinedWorkgroup) {
                        OutlinedButton(
                            onClick = { onLeaveWorkgroup(uiState.activeKitchenOwnerId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("leave_workgroup_btn")
                        ) {
                            Text("Leave This Group & Return to My Kitchen")
                        }
                    }

                    // Manager-Only Staff Roster & Email Invitations
                    if (isOwnerWorkspace) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (uiState.staffMembers.isEmpty()) {
                            Text(
                                text = "No team members in $activeGroupName yet. Tap 'Invite by Email' above to invite staff strictly by email and assign their role.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Group Team Roster (${uiState.staffMembers.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Roles are controlled strictly by the group manager.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            uiState.staffMembers.forEach { staff ->
                                val linkedMember = uiState.workgroupMembers.firstOrNull { it.staffRecordId == staff.id }
                                val pendingInvite = uiState.sentInvites.firstOrNull {
                                    it.staffRecordId == staff.id && it.statusEnum == InviteStatus.PENDING
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = staff.fullName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Surface(
                                                        color = if (linkedMember != null) {
                                                            MaterialTheme.colorScheme.secondaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.tertiaryContainer
                                                        },
                                                        shape = RoundedCornerShape(999.dp)
                                                    ) {
                                                        Text(
                                                            text = if (linkedMember != null) "Connected" else "Pending Email",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (linkedMember != null) {
                                                                MaterialTheme.colorScheme.onSecondaryContainer
                                                            } else {
                                                                MaterialTheme.colorScheme.onTertiaryContainer
                                                            },
                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = buildString {
                                                        append(staff.role.replace('_', ' '))
                                                        if (!staff.station.isNullOrBlank()) append(" • ${staff.station}")
                                                        val contact = linkedMember?.email ?: staff.inviteEmail ?: pendingInvite?.inviteEmail
                                                        if (!contact.isNullOrBlank()) append(" • $contact")
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isManager) {
                                                IconButton(
                                                    onClick = { onDeleteStaff(staff.id) },
                                                    modifier = Modifier.testTag("delete_staff_${staff.id}")
                                                ) {
                                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove staff & revoke access")
                                                }
                                            }
                                        }

                                        if (isManager) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedButton(
                                                    onClick = { editRoleTarget = staff to linkedMember?.memberUid?.ifBlank { linkedMember.id } },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("edit_staff_role_${staff.id}")
                                                ) {
                                                    Icon(Icons.Default.Security, contentDescription = "Edit Role", modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Edit Role", style = MaterialTheme.typography.labelMedium)
                                                }

                                                if (pendingInvite != null) {
                                                    FilledTonalButton(
                                                        onClick = { onShareExistingInvite(pendingInvite) },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .testTag("share_staff_invite_${staff.id}")
                                                    ) {
                                                        Icon(Icons.Default.Email, contentDescription = "Send Email Invite", modifier = Modifier.size(15.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Send Email", style = MaterialTheme.typography.labelMedium)
                                                    }
                                                } else if (linkedMember == null) {
                                                    FilledTonalButton(
                                                        onClick = { onRegenerateInviteForStaff(staff) },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .testTag("regen_staff_invite_${staff.id}")
                                                    ) {
                                                        Icon(Icons.Default.Refresh, contentDescription = "Resend Email Invite", modifier = Modifier.size(15.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Resend Email", style = MaterialTheme.typography.labelMedium)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                            enabled = isOwnerWorkspace,
                            onCheckedChange = { enabled ->
                                alertsEnabled = enabled
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                onSaveKitchenPosSettings(
                                    activeGroupName,
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

        // 2. POS Systems Integration (Omega POS & BIM POS First — Lebanon/MENA + Global)
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
                        Column {
                            Text(strings.posIntegration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Omega POS & BIM POS (Lebanon) + Square, Toast, Clover",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PosProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = posProvider == provider,
                                onClick = { if (uiState.activePermissions.canManagePos) posProvider = provider },
                                label = { Text(provider.displayName) },
                                modifier = Modifier.testTag("pos_provider_${provider.id}")
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = posProvider.setupHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }

                    OutlinedTextField(
                        value = posUrl,
                        onValueChange = { posUrl = it },
                        enabled = uiState.activePermissions.canManagePos && isOwnerWorkspace,
                        label = { Text("${posProvider.displayName} Sales Endpoint URL") },
                        placeholder = { Text(posProvider.sampleEndpoint.ifBlank { "https://api.yourpos.com/v1/sales/today" }) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pos_endpoint_input")
                    )

                    OutlinedTextField(
                        value = posToken,
                        onValueChange = { posToken = it },
                        enabled = uiState.activePermissions.canManagePos,
                        label = { Text("Bearer Token / X-API-Key (stored locally)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isOwnerWorkspace) {
                        OutlinedTextField(
                            value = currencyInput,
                            onValueChange = { currencyInput = it.take(4) },
                            enabled = uiState.activePermissions.canManagePos,
                            label = { Text("Default Group Currency Symbol") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (uiState.activePermissions.canManagePos) {
                        FilledTonalButton(
                            onClick = {
                                onSaveKitchenPosSettings(
                                    activeGroupName,
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
                            Text("Save ${posProvider.displayName} Config")
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
                                onClick = { showPosCsvImportDialog = true },
                                enabled = !uiState.posSyncBusy && uiState.menuRecipes.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_pos_csv_btn")
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = "Import Z-Report CSV", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Z-Report CSV")
                            }
                        }

                        OutlinedButton(
                            onClick = { showPosShiftDialog = true },
                            enabled = !uiState.posSyncBusy && uiState.menuRecipes.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("record_shift_sales_btn")
                        ) {
                            Text(strings.recordShiftSales)
                        }
                    } else {
                        Text(
                            text = "POS integration configuration is restricted to managers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

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

        // 3. Sign Out
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
                onCreateStaffAndInvite(member)
                showStaffDialog = false
            }
        )
    }

    editRoleTarget?.let { (staff, linkedUid) ->
        EditStaffRoleDialog(
            staff = staff,
            onSave = { role, reports, csv, scans, recipes, pos ->
                onUpdateStaffRole(staff.id, linkedUid, role, reports, csv, scans, recipes, pos)
                editRoleTarget = null
            },
            onDismiss = { editRoleTarget = null }
        )
    }

    if (showEmailInvitesDialog) {
        JoinWorkgroupDialog(
            userEmail = uiState.currentUserEmail,
            incomingEmailInvites = uiState.incomingEmailInvites,
            busy = uiState.inviteLookupBusy,
            onReviewInvite = onReviewEmailInvite,
            onDeclineInvite = onDeclineInvite,
            onDismiss = { showEmailInvitesDialog = false }
        )
    }

    if (showPosCsvImportDialog) {
        ImportPosZReportDialog(
            provider = posProvider,
            busy = uiState.posSyncBusy,
            onImport = { csvText, prov ->
                onImportPosZReport(csvText, prov)
                showPosCsvImportDialog = false
            },
            onDismiss = { showPosCsvImportDialog = false }
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
}

@Composable
private fun AddStaffRoleDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (StaffMember) -> Unit
) {
    var fullName by remember { mutableStateOf("") }
    var inviteEmail by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(StaffRole.SOUS_CHEF) }
    var showAdvancedPermissions by remember { mutableStateOf(false) }
    var canViewReports by remember { mutableStateOf(false) }
    var canExportCsv by remember { mutableStateOf(false) }
    var canApproveScans by remember { mutableStateOf(true) }
    var canEditRecipes by remember { mutableStateOf(true) }
    var canManagePos by remember { mutableStateOf(false) }

    val emailTrimmed = inviteEmail.trim().lowercase()
    val emailValid = WorkgroupInviteHelper.isValidEmail(emailTrimmed)

    val roleDescription = when (role) {
        StaffRole.LINE_COOK -> "Can view recipes & sub-recipes and log daily stock or waste."
        StaffRole.SOUS_CHEF -> "Can view & edit recipes, produce batches, and approve invoice scans."
        StaffRole.INVENTORY_CLERK -> "Can manage stock counts, scan invoices, and export stock CSVs."
        StaffRole.MANAGER -> "Full access to recipes, stock, POS sync, and financial reports."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.GroupAdd,
                contentDescription = "Invite Staff by Email",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Invite Team Member by Email") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Invitations are strictly by Google email. Only the manager can choose and change roles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Staff Name *") },
                    placeholder = { Text("e.g. Chef Rami") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("staff_name_input")
                )

                OutlinedTextField(
                    value = inviteEmail,
                    onValueChange = { inviteEmail = it },
                    label = { Text("Staff Google Email (Required) *") },
                    placeholder = { Text("chef.rami@gmail.com") },
                    isError = inviteEmail.isNotBlank() && !emailValid,
                    supportingText = {
                        if (inviteEmail.isNotBlank() && !emailValid) {
                            Text("Enter a valid email address.")
                        } else {
                            Text("They must sign in with this email and enter the Group Code to join.")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("staff_email_input")
                )

                Text(
                    text = "Assign Role (Manager Only)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
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

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = roleDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                TextButton(
                    onClick = { showAdvancedPermissions = !showAdvancedPermissions },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showAdvancedPermissions) "Hide Custom Permissions & Station" else "Customize Permissions & Station (Optional)")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showAdvancedPermissions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (showAdvancedPermissions) {
                    OutlinedTextField(
                        value = station,
                        onValueChange = { station = it },
                        label = { Text("Kitchen Station (Grill, Pastry, Prep...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PermissionToggleRow("Manager Financial Reports", canViewReports) { canViewReports = it }
                    PermissionToggleRow("Export Weekly/Monthly CSV", canExportCsv) { canExportCsv = it }
                    PermissionToggleRow("Approve Flagged Invoice Scans", canApproveScans) { canApproveScans = it }
                    PermissionToggleRow("Edit Recipes & Menu Pricing", canEditRecipes) { canEditRecipes = it }
                    PermissionToggleRow("Configure POS Sales Sync", canManagePos) { canManagePos = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        StaffMember(
                            fullName = fullName.trim(),
                            role = role.id,
                            canViewReports = canViewReports,
                            canExportCsv = canExportCsv,
                            canApproveScans = canApproveScans,
                            canEditRecipes = canEditRecipes,
                            canManagePos = canManagePos,
                            active = true,
                            station = station.takeIf { it.isNotBlank() },
                            inviteEmail = emailTrimmed
                        )
                    )
                },
                enabled = fullName.isNotBlank() && emailValid,
                modifier = Modifier.testTag("confirm_create_staff_invite_btn")
            ) {
                Text("Send Email Invite")
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
                    text = "Enter plates sold from your POS Z-Report to automatically deduct exact recipe & sub-recipe ingredients from live inventory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                uiState.menuRecipes.forEach { recipe ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(recipe.nameEn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (!recipe.posSku.isNullOrBlank()) {
                                Text("PLU/SKU: ${recipe.posSku}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
