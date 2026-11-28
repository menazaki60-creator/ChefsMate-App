package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.WorkgroupInviteHelper
import com.example.model.JoinedWorkgroup
import com.example.model.PosProvider
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.model.Workgroup
import com.example.model.WorkgroupInvite
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reusable circular/rounded avatar showing a group's uploaded logo or clean initials fallback. */
@Composable
fun GroupLogoAvatar(
    logoBase64: String?,
    fallbackName: String,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(logoBase64) { WorkgroupInviteHelper.decodeLogoBitmap(logoBase64) }
    val shape = RoundedCornerShape(10.dp)
    if (bitmap != null) {
        val img = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = img,
            contentDescription = "$fallbackName logo",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        val initials = remember(fallbackName) {
            fallbackName.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifBlank { "CM" }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = initials,
                style = if (size >= 44.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 1–2 click Multi-Group Switcher accessible from the top bar on any screen. */
@Composable
fun GroupSwitcherDialog(
    ownedGroups: List<Workgroup>,
    joinedGroups: List<JoinedWorkgroup>,
    activeWorkspaceId: String,
    fallbackOwnKitchenName: String,
    fallbackOwnLogoBase64: String?,
    primaryUserId: String,
    onSelectGroup: (String) -> Unit,
    onCreateNewGroup: () -> Unit,
    onEditActiveGroup: () -> Unit,
    onDismiss: () -> Unit
) {
    val isActiveOwned = activeWorkspaceId == primaryUserId || ownedGroups.any { it.id == activeWorkspaceId }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Storefront,
                contentDescription = "Switch Kitchen Group",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Switch Kitchen Group") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Manage multiple locations or switch between teams in one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "MY MANAGED GROUPS (${ownedGroups.size.coerceAtLeast(1)})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (ownedGroups.isEmpty()) {
                    GroupSwitcherRow(
                        name = fallbackOwnKitchenName.ifBlank { "Main Kitchen" },
                        subtitle = "Manager • Primary Kitchen",
                        logoBase64 = fallbackOwnLogoBase64,
                        selected = activeWorkspaceId == primaryUserId,
                        showEdit = activeWorkspaceId == primaryUserId,
                        onEdit = {
                            onDismiss()
                            onEditActiveGroup()
                        },
                        onClick = {
                            onSelectGroup(primaryUserId)
                            onDismiss()
                        },
                        tag = "switcher_group_primary"
                    )
                } else {
                    ownedGroups.forEach { group ->
                        val isSelected = activeWorkspaceId == group.id
                        val handle = group.resolvedUsername
                        GroupSwitcherRow(
                            name = group.name.ifBlank { "Main Kitchen" },
                            subtitle = buildString {
                                if (handle.isNotBlank()) append("@$handle · ")
                                append("Manager")
                                if (group.groupCode.isNotBlank()) append(" · Code: ${group.groupCode}")
                            },
                            logoBase64 = group.logoBase64,
                            selected = isSelected,
                            showEdit = isSelected,
                            onEdit = {
                                onDismiss()
                                onEditActiveGroup()
                            },
                            onClick = {
                                onSelectGroup(group.id)
                                onDismiss()
                            },
                            tag = "switcher_group_${group.id}"
                        )
                    }
                }

                if (joinedGroups.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "JOINED TEAM GROUPS (${joinedGroups.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    joinedGroups.forEach { wg ->
                        val targetId = wg.resolvedWorkspaceId
                        val isSelected = activeWorkspaceId == targetId
                        GroupSwitcherRow(
                            name = wg.kitchenName,
                            subtitle = "Team Role: ${wg.role.replace('_', ' ')}",
                            logoBase64 = wg.logoBase64,
                            selected = isSelected,
                            showEdit = false,
                            onEdit = {},
                            onClick = {
                                onSelectGroup(targetId)
                                onDismiss()
                            },
                            tag = "switcher_joined_$targetId"
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Button(
                    onClick = {
                        onDismiss()
                        onCreateNewGroup()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("switcher_create_new_group_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create New Group", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create New Group / Location")
                }

                if (isActiveOwned) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onEditActiveGroup()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("switcher_edit_active_group_btn")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename Active Group", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rename Group, @username & Logo")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun GroupSwitcherRow(
    name: String,
    subtitle: String,
    logoBase64: String?,
    selected: Boolean,
    showEdit: Boolean,
    onEdit: () -> Unit,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GroupLogoAvatar(logoBase64 = logoBase64, fallbackName = name, size = 38.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showEdit) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit group branding",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Active group",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Dialog to create a new group/location or rename an existing group, set its unique @username handle, and upload a logo. */
@Composable
fun CreateOrEditWorkgroupDialog(
    existingGroup: Workgroup?,
    busy: Boolean,
    errorText: String? = null,
    onClearError: () -> Unit = {},
    onSubmit: (name: String, username: String, currency: String, logoUri: Uri?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isEdit = existingGroup != null
    var name by remember(existingGroup?.id) { mutableStateOf(existingGroup?.name.orEmpty()) }
    var username by remember(existingGroup?.id) {
        val initial = existingGroup?.resolvedUsername?.takeIf { it.isNotBlank() }
            ?: existingGroup?.name?.takeIf { it.isNotBlank() }?.let { WorkgroupInviteHelper.suggestUsernameFromName(it) }
            ?: ""
        mutableStateOf(initial)
    }
    var usernameTouched by remember(existingGroup?.id) { mutableStateOf(isEdit && username.isNotBlank()) }
    var currency by remember(existingGroup?.id) { mutableStateOf(existingGroup?.currencySymbol ?: "$") }
    var selectedLogoUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val finalizedHandle = WorkgroupInviteHelper.finalizeUsername(username)
    val isHandleValid = WorkgroupInviteHelper.isValidUsername(finalizedHandle)

    // Zero-permission Android Photo Picker (Google Play compliant)
    val logoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedLogoUri = uri
        }
    }

    LaunchedEffect(selectedLogoUri) {
        val uri = selectedLogoUri
        previewBitmap = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, _, _ ->
                        dec.setTargetSize(192, 192)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!busy) {
                onClearError()
                onDismiss()
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Storefront,
                contentDescription = if (isEdit) "Rename & Edit Group" else "Create New Group",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(if (isEdit) "Rename Group & @username" else "Create New Group") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo preview + upload button
                if (previewBitmap != null) {
                    val img = remember(previewBitmap) { previewBitmap!!.asImageBitmap() }
                    Image(
                        bitmap = img,
                        contentDescription = "Selected group logo preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    GroupLogoAvatar(
                        logoBase64 = existingGroup?.logoBase64,
                        fallbackName = name.ifBlank { "New Group" },
                        size = 72.dp
                    )
                }

                FilledTonalButton(
                    onClick = {
                        logoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("pick_group_logo_btn")
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Upload Group Logo", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (selectedLogoUri != null || !existingGroup?.logoBase64.isNullOrBlank()) "Change Group Logo" else "Upload Group Logo")
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { updated ->
                        name = updated.take(64)
                        if (!usernameTouched) {
                            username = WorkgroupInviteHelper.sanitizeUsername(updated)
                        }
                        if (!errorText.isNullOrBlank()) onClearError()
                    },
                    label = { Text("Group / Location Name") },
                    placeholder = { Text("e.g. Beirut Downtown Kitchen") },
                    supportingText = {
                        Text("Display name shown across your dashboard & team invites.")
                    },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workgroup_name_input")
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { updated ->
                        usernameTouched = true
                        username = WorkgroupInviteHelper.sanitizeUsername(updated)
                        if (!errorText.isNullOrBlank()) onClearError()
                    },
                    label = { Text("Unique Group Username") },
                    prefix = {
                        Text(
                            text = "@",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = JetBrainsMonoFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    placeholder = { Text("beirut_downtown") },
                    supportingText = {
                        Text(
                            text = if (username.isNotBlank() && !isHandleValid) {
                                "Use 3–32 lowercase letters, numbers, or underscores (_)."
                            } else {
                                "Globally unique handle — no two groups can use the same @username."
                            },
                            color = if (username.isNotBlank() && !isHandleValid) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    isError = (username.isNotBlank() && !isHandleValid) || !errorText.isNullOrBlank(),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workgroup_username_input")
                )

                if (!errorText.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("workgroup_error_banner")
                    ) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (!isEdit) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.take(4) },
                        label = { Text("Default Currency Symbol") },
                        placeholder = { Text("$") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        name.trim(),
                        finalizedHandle,
                        currency.trim().ifBlank { "$" },
                        selectedLogoUri
                    )
                },
                enabled = !busy && name.trim().length >= 2 && isHandleValid,
                modifier = Modifier.testTag("save_workgroup_btn")
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (isEdit) "Save Changes" else "Create Group")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onClearError()
                    onDismiss()
                },
                enabled = !busy
            ) { Text("Cancel") }
        }
    )
}

/** Manager-only dialog to update a team member's role and granular permissions. */
@Composable
fun EditStaffRoleDialog(
    staff: StaffMember,
    onSave: (StaffRole, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var role by remember(staff.id) { mutableStateOf(staff.roleEnum) }
    var canViewReports by remember(staff.id) { mutableStateOf(staff.canViewReports) }
    var canExportCsv by remember(staff.id) { mutableStateOf(staff.canExportCsv) }
    var canApproveScans by remember(staff.id) { mutableStateOf(staff.canApproveScans) }
    var canEditRecipes by remember(staff.id) { mutableStateOf(staff.canEditRecipes) }
    var canManagePos by remember(staff.id) { mutableStateOf(staff.canManagePos) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Edit Staff Role",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Edit Role: ${staff.fullName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Only the group manager can assign or modify roles and permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DialogPermissionToggleRow("Financial Reports & Insights", canViewReports) { canViewReports = it }
                DialogPermissionToggleRow("Export Weekly / Monthly CSV", canExportCsv) { canExportCsv = it }
                DialogPermissionToggleRow("Approve Flagged Scans", canApproveScans) { canApproveScans = it }
                DialogPermissionToggleRow("Edit Recipes & Pricing", canEditRecipes) { canEditRecipes = it }
                DialogPermissionToggleRow("Configure POS Sync", canManagePos) { canManagePos = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(role, canViewReports, canExportCsv, canApproveScans, canEditRecipes, canManagePos)
                },
                modifier = Modifier.testTag("save_staff_role_btn")
            ) {
                Text("Save Role")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DialogPermissionToggleRow(
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

/** Strictly email-only invite dialog showing recipient email, manager-assigned role, and Group Verification Code. */
@Composable
fun ShareWorkgroupInviteDialog(
    invite: WorkgroupInvite,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copiedCode by remember(invite.id) { mutableStateOf(false) }
    val groupCode = invite.requiredVerificationCode

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = "Send email workgroup invite",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Email Invite Ready") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Only ${invite.inviteEmail} can accept this invitation to join ${invite.kitchenName} as ${invite.role.replace('_', ' ')} (assigned by Manager).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { WorkgroupInviteHelper.sendInviteByEmail(context, invite) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("send_email_invite_btn")
                ) {
                    Icon(Icons.Default.Email, contentDescription = "Send email invite", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send Email to ${invite.inviteEmail}", maxLines = 1)
                }

                // Group Verification Code required when staff confirms joining
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "GROUP VERIFICATION CODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = groupCode,
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedButton(
                                onClick = {
                                    WorkgroupInviteHelper.copyToClipboard(context, "Group Code", groupCode)
                                    copiedCode = true
                                },
                                modifier = Modifier.testTag("copy_invite_code_btn")
                            ) {
                                Icon(
                                    imageVector = if (copiedCode) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy group code",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (copiedCode) "Copied!" else "Copy Code")
                            }
                        }
                        Text(
                            text = "Your staff member will be asked to confirm and enter this Group Code before joining.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "How your staff joins:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("1. Tap 'Send Email' above so they receive the Group Code.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("2. Staff signs into ChefsMate with ${invite.inviteEmail}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("3. They tap 'Review & Join', confirm, and type $groupCode.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/** Strictly email-only Pending Invitations inbox dialog (never accepts open anonymous codes/links). */
@Composable
fun JoinWorkgroupDialog(
    userEmail: String,
    incomingEmailInvites: List<WorkgroupInvite>,
    busy: Boolean,
    onReviewInvite: (WorkgroupInvite) -> Unit,
    onDeclineInvite: (WorkgroupInvite) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = "Email Invitations",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Email Group Invitations") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Invitations are strictly by email. Signed-in account: ${userEmail.ifBlank { "Google Account" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                if (incomingEmailInvites.isEmpty()) {
                    Text(
                        text = "No pending email invitations yet. Ask your group manager to invite your exact email ($userEmail) and send you the Group Verification Code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    incomingEmailInvites.forEach { inv ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
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
                                        onClick = {
                                            onDismiss()
                                            onReviewInvite(inv)
                                        },
                                        enabled = !busy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("review_email_invite_${inv.id}")
                                    ) {
                                        Text("Review & Join")
                                    }
                                    OutlinedButton(
                                        onClick = { onDeclineInvite(inv) },
                                        enabled = !busy,
                                        modifier = Modifier.testTag("decline_email_invite_${inv.id}")
                                    ) {
                                        Text("Decline")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Explicit Confirmation + Group Verification Code Dialog.
 * Always shown before an email-invited user joins a group (prevents accidental or unverified joins).
 */
@Composable
fun ConfirmWorkgroupInviteDialog(
    invite: WorkgroupInvite,
    busy: Boolean,
    errorText: String?,
    onConfirmWithCode: (WorkgroupInvite, String) -> Unit,
    onDecline: (WorkgroupInvite) -> Unit,
    onDismiss: () -> Unit
) {
    var enteredCode by remember(invite.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.GroupAdd,
                contentDescription = "Confirm Group Invitation",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Join ${invite.kitchenName}?") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InvitePreviewCard(invite = invite)

                Text(
                    text = "Do you want to join this group? Enter the Group Verification Code from your manager's email to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = enteredCode,
                    onValueChange = { enteredCode = it.uppercase().take(24) },
                    label = { Text("Enter Group Code") },
                    placeholder = { Text("e.g. GRP-7K4M") },
                    singleLine = true,
                    enabled = !busy,
                    isError = !errorText.isNullOrBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_group_code_input")
                )

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmWithCode(invite, enteredCode.trim()) },
                enabled = !busy && enteredCode.trim().length >= 4,
                modifier = Modifier.testTag("confirm_join_with_code_btn")
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Confirm & Join")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onDecline(invite) },
                    enabled = !busy,
                    modifier = Modifier.testTag("decline_invite_dialog_btn")
                ) {
                    Text("Decline", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("Later")
                }
            }
        }
    )
}

@Composable
private fun InvitePreviewCard(invite: WorkgroupInvite) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GroupLogoAvatar(
                    logoBase64 = invite.logoBase64,
                    fallbackName = invite.kitchenName,
                    size = 42.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = invite.kitchenName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Invited by ${invite.invitedByName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Text(
                text = "Assigned Role (set by Manager): ${invite.role.replace('_', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (!invite.inviteEmail.isNullOrBlank()) {
                Text(
                    text = "Invited Email: ${invite.inviteEmail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val perms = buildList {
                add("Recipes & Sub-Recipes")
                add("Stock & Waste")
                if (invite.canEditRecipes) add("Edit Recipes")
                if (invite.canApproveScans) add("Review Scans")
                if (invite.canViewReports) add("Financial Reports")
                if (invite.canExportCsv) add("CSV Export")
                if (invite.canManagePos) add("POS Sync")
            }
            Text(
                text = "Permissions: ${perms.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ImportPosZReportDialog(
    provider: PosProvider,
    busy: Boolean,
    onImport: (String, PosProvider) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var reportText by remember { mutableStateOf("") }
    var loadedFileName by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.onSuccess { text ->
                if (!text.isNullOrBlank()) {
                    reportText = text
                    loadedFileName = uri.lastPathSegment ?: "Selected POS export"
                }
            }
        }
    }

    val effectiveProvider = if (provider == PosProvider.NONE) PosProvider.OMEGA_POS else provider

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import ${effectiveProvider.displayName} Z-Report") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Works offline/locally in Lebanon with Omega POS & BIM POS BackOffice exports. Pick a CSV/JSON Item Sales report or paste rows below (PLU/SKU, Dish Name, Qty Sold).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_pos_zreport_file_btn")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Pick CSV/JSON file", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(loadedFileName?.let { "Loaded: $it" } ?: "Pick CSV / JSON Export File")
                }

                OutlinedTextField(
                    value = reportText,
                    onValueChange = { reportText = it },
                    label = { Text("Item Sales CSV / JSON") },
                    placeholder = { Text("PLU,ItemName,QtySold\n1001,Grilled Chicken Plate,14\n1002,Tawouk Sandwich,22") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pos_zreport_paste_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(reportText, effectiveProvider) },
                enabled = !busy && reportText.isNotBlank(),
                modifier = Modifier.testTag("import_pos_zreport_confirm_btn")
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Match & Deduct Stock")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
