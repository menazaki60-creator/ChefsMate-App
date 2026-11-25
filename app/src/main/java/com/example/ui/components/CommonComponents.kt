package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.AppStrings
import com.example.model.SyncStatus
import com.example.model.UnitType
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.UrgentAlert
import java.util.Locale

fun formatMoney(amount: Double, symbol: String = "$"): String =
    "$symbol${String.format(Locale.US, "%.2f", amount)}"

fun formatQtyWithSmartUnit(qty: Double, unit: UnitType): String {
    return when {
        unit == UnitType.KG && qty > 0.0 && qty < 1.0 ->
            "${String.format(Locale.US, "%.0f", qty * 1000.0)} g"
        unit == UnitType.L && qty > 0.0 && qty < 1.0 ->
            "${String.format(Locale.US, "%.0f", qty * 1000.0)} mL"
        qty % 1.0 == 0.0 -> "${qty.toInt()} ${unit.symbol}"
        else -> "${String.format(Locale.US, "%.2f", qty)} ${unit.symbol}"
    }
}

@Composable
fun SyncStatusBadge(
    syncStatus: SyncStatus,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val (label, icon, tint, containerColor) = when {
        syncStatus.hasPendingWrites -> Quadruple(
            strings.syncingPending,
            Icons.Default.CloudSync,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        )
        syncStatus.fromCache -> Quadruple(
            strings.offlineCache,
            Icons.Default.CloudOff,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        )
        else -> Quadruple(
            strings.synced,
            Icons.Default.CloudDone,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
        )
    }

    Surface(
        modifier = modifier.testTag("sync_status_badge"),
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1
            )
        }
    }
}

@Composable
fun UrgentAlertBanner(
    alerts: List<UrgentAlert>,
    onAlertClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val topAlert = alerts.firstOrNull() ?: return
    val isCritical = topAlert.severity == "CRITICAL"
    val accent = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    val container = if (isCritical) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onAlertClick)
            .testTag("urgent_alert_banner"),
        color = container,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCritical) Icons.Default.Warning else Icons.Default.NotificationsActive,
                    contentDescription = topAlert.title,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = topAlert.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = topAlert.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (alerts.size > 1) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent
                ) {
                    Text(
                        text = "+${alerts.size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun KpiTile(
    label: String,
    value: String,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    sublabel: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!sublabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun EmptyStatePanel(
    icon: ImageVector,
    title: String,
    hint: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.testTag("empty_state_action_button")
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ManagerPinDialog(
    onDismiss: () -> Unit,
    onVerify: (String) -> Boolean,
    onUnlocked: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Manager PIN",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Manager Access Verification") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter your Manager PIN to unlock financial reports, CSV exports, and POS controls.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter { ch -> ch.isDigit() }.take(8)
                        invalid = false
                    },
                    label = { Text("Manager PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text("Incorrect PIN. Try again.") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manager_pin_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onVerify(pin)) {
                        onUnlocked()
                    } else {
                        invalid = true
                    }
                },
                modifier = Modifier.testTag("manager_pin_confirm_button")
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
