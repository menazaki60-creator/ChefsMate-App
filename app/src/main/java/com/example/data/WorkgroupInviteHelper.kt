package com.example.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import android.widget.Toast
import com.example.model.WorkgroupInvite
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.math.min

object WorkgroupInviteHelper {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LOWER_ALNUM = "abcdefghjkmnpqrstuvwxyz23456789"
    private val secureRandom = SecureRandom()
    private val codePattern = Regex("CM-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")
    private val validCodeRegex = Regex("^[A-Z0-9\\-]{10,32}$")
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    private val logoBitmapCache = object : LruCache<Int, Bitmap>(24) {}

    /** Generates a cryptographically strong, readable single-use invite ID (60 bits of entropy). */
    fun generateInviteCode(): String {
        fun block(): String = buildString(4) {
            repeat(4) { append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]) }
        }
        return "CM-${block()}-${block()}-${block()}"
    }

    /** Generates a unique multi-location workgroup document ID (always prefixed with grp_). */
    fun generateGroupId(): String {
        val token = buildString(12) {
            repeat(12) { append(LOWER_ALNUM[secureRandom.nextInt(LOWER_ALNUM.length)]) }
        }
        return "grp_$token"
    }

    /** Generates a short, memorable Group Verification Code (e.g. GRP-7K4M) required when invitees confirm joining. */
    fun generateGroupCode(): String {
        val block = buildString(4) {
            repeat(4) { append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]) }
        }
        return "GRP-$block"
    }

    private val usernameRegex = Regex("^[a-z0-9_]{3,32}$")

    /**
     * Sanitizes user-typed workgroup username input into lowercase [a-z0-9_] characters (max 32).
     * Strips any leading '@' so users can type either "beirut_grill" or "@beirut_grill".
     */
    fun sanitizeUsername(raw: String): String {
        return raw.trim()
            .removePrefix("@")
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .take(32)
    }

    /** Final canonical @username handle (trims leading/trailing underscores). */
    fun finalizeUsername(raw: String): String {
        return sanitizeUsername(raw).trim('_').take(32)
    }

    fun isValidUsername(raw: String): Boolean {
        val cleaned = finalizeUsername(raw)
        return usernameRegex.matches(cleaned)
    }

    /** Suggests a valid 3..32 char lowercase [a-z0-9_] username handle from a display name. */
    fun suggestUsernameFromName(rawName: String, fallbackSuffix: String = ""): String {
        val cleaned = finalizeUsername(rawName)
        if (cleaned.length >= 3) return cleaned.take(32)
        val safeSuffix = fallbackSuffix.lowercase().replace(Regex("[^a-z0-9]"), "").take(6)
        if (cleaned.isNotEmpty() && safeSuffix.isNotEmpty()) {
            val combined = "${cleaned}_${safeSuffix}".take(32)
            if (combined.length >= 3) return combined
        }
        val hash = rawName.trim().lowercase().hashCode().toUInt().toString(36).take(6)
        return "kitchen_${safeSuffix.ifBlank { hash }}".take(32)
    }

    /**
     * Normalizes a group name or handle into a globally unique Firestore document key (2..64 chars, [a-z0-9_]).
     * Prevents two managers or locations from claiming the same group handle regardless of spacing/casing.
     */
    fun normalizeGroupName(raw: String): String {
        val cleaned = raw.trim().removePrefix("@").lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(64)
        return if (cleaned.length >= 2) cleaned else {
            // Fallback for non-Latin names (e.g. Arabic-only names): deterministic safe key
            val hash = raw.trim().lowercase().hashCode().toUInt().toString(36)
            "grp_name_$hash".take(64)
        }
    }

    fun isValidEmail(email: String?): Boolean {
        val trimmed = email?.trim()?.lowercase().orEmpty()
        return trimmed.length in 5..160 && emailRegex.matches(trimmed)
    }

    /** Safely extracts an invite code from a deep link URI or raw code. */
    fun extractInviteCode(rawInput: String?): String? {
        if (rawInput.isNullOrBlank()) return null
        val trimmed = rawInput.trim()

        runCatching {
            val uri = Uri.parse(trimmed)
            val queryCode = uri.getQueryParameter("code")?.trim()?.uppercase()
            if (!queryCode.isNullOrBlank() && validCodeRegex.matches(queryCode)) {
                return queryCode
            }
        }

        val upper = trimmed.uppercase()
        codePattern.find(upper)?.value?.let { return it }

        val compact = upper.replace(" ", "")
        return if (validCodeRegex.matches(compact)) compact else null
    }

    /**
     * Decodes a user-selected image URI (from zero-permission Android Photo Picker) into a crisp
     * 192x192 center-cropped JPEG Base64 string (~10-25 KB) suitable for Firestore storage.
     */
    fun encodeLogoToBase64(context: Context, uri: Uri): Result<String> = runCatching {
        val rawBitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val maxSide = maxOf(w, h)
                if (maxSide > 512 && w > 0 && h > 0) {
                    val ratio = 512f / maxSide.toFloat()
                    decoder.setTargetSize(
                        (w * ratio).toInt().coerceAtLeast(1),
                        (h * ratio).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: throw IllegalStateException("Could not read selected logo image.")
        }

        val side = min(rawBitmap.width, rawBitmap.height).coerceAtLeast(1)
        val xOffset = ((rawBitmap.width - side) / 2).coerceAtLeast(0)
        val yOffset = ((rawBitmap.height - side) / 2).coerceAtLeast(0)
        val square = Bitmap.createBitmap(rawBitmap, xOffset, yOffset, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 192, 192, true)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 76, out)
        var bytes = out.toByteArray()
        if (bytes.size > 140_000) {
            out.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
            bytes = out.toByteArray()
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        require(b64.length <= 200_000) { "Selected logo image is too large." }
        b64
    }

    /** Decodes a Firestore Base64 group logo with lightweight in-memory caching. */
    fun decodeLogoBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val key = base64.hashCode()
        logoBitmapCache.get(key)?.let { return it }
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) logoBitmapCache.put(key, bmp)
            bmp
        }.getOrNull()
    }

    /** Builds a strictly email-targeted invitation message with the manager-assigned role and Group Verification Code. */
    fun buildEmailInviteBody(invite: WorkgroupInvite): String {
        val roleLabel = invite.role.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
        val groupCode = invite.requiredVerificationCode
        val targetEmail = invite.inviteEmail?.trim().orEmpty()
        return buildString {
            appendLine("Hello ${invite.staffName},")
            appendLine()
            appendLine("${invite.invitedByName} invited you to join the ChefsMate group \"${invite.kitchenName}\".")
            appendLine("Assigned Role (set by Manager): $roleLabel")
            appendLine("Authorized Google Email: $targetEmail")
            appendLine()
            appendLine("Group Verification Code: $groupCode")
            appendLine()
            appendLine("How to join:")
            appendLine("1. Open ChefsMate and sign in with Google using $targetEmail")
            appendLine("2. Tap \"Review & Join\" on the pending invitation banner")
            appendLine("3. Enter the Group Verification Code ($groupCode) and confirm to join.")
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    /** Opens the manager's email app pre-addressed strictly to the invited staff member's email with the Group Code. */
    fun sendInviteByEmail(context: Context, invite: WorkgroupInvite) {
        val recipient = invite.inviteEmail?.trim().orEmpty()
        val subject = "ChefsMate Group Invite: ${invite.kitchenName} (Code: ${invite.requiredVerificationCode})"
        val body = buildEmailInviteBody(invite)
        val mailtoUri = Uri.parse(
            "mailto:${Uri.encode(recipient)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
        )
        val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(emailIntent)
        }.onFailure {
            copyToClipboard(context, "Email Invite", body)
            Toast.makeText(context, "No email app found — invite copied to clipboard", Toast.LENGTH_LONG).show()
        }
    }
}
