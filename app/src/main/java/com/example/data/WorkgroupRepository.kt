package com.example.data

import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.model.InviteStatus
import com.example.model.JoinedWorkgroup
import com.example.model.PosProvider
import com.example.model.StaffMember
import com.example.model.StaffRole
import com.example.model.Workgroup
import com.example.model.WorkgroupInvite
import com.example.model.WorkgroupMember
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Handles multi-location Workgroup Management (globally unique names, logos, Group Codes),
 * strictly Email-Only Workgroup Invitations (requiring confirmation + Group Code entry),
 * and Manager-Only Role & Permission assignments via Firestore.
 */
class WorkgroupRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google.")

    private fun workspaceDoc(workspaceId: String): DocumentReference =
        if (workspaceId.startsWith("grp_")) {
            db.collection("workgroups").document(workspaceId)
        } else {
            db.collection("users").document(workspaceId)
        }

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

    /** Manager observes all groups/locations they own (primary kitchen + any created branches). */
    fun observeOwnedWorkgroups(ownerUid: String): Flow<List<Workgroup>> = flow {
        val path = "workgroups(owner=$ownerUid)"
        emitAll(
            db.collection("workgroups")
                .whereEqualTo("ownerUserId", ownerUid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(Workgroup::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(
                            compareByDescending<Workgroup> { it.primaryKitchen }
                                .thenBy { it.name.lowercase() }
                        )
                }
                .logAndRethrow(ownerUid, OperationType.LIST, path)
        )
    }

    /** Manager observes all invites issued across their groups. */
    fun observeSentInvites(ownerUid: String): Flow<List<WorkgroupInvite>> = flow {
        val path = "workgroup_invites(owner=$ownerUid)"
        emitAll(
            db.collection("workgroup_invites")
                .whereEqualTo("ownerUserId", ownerUid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(WorkgroupInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(ownerUid, OperationType.LIST, path)
        )
    }

    /** Signed-in user automatically discovers pending strictly-email invites sent to their verified Google email. */
    fun observeIncomingEmailInvites(currentUid: String, userEmail: String?): Flow<List<WorkgroupInvite>> = flow {
        val normalized = userEmail?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "workgroup_invites(email=$normalized)"
        emitAll(
            db.collection("workgroup_invites")
                .whereEqualTo("inviteEmail", normalized)
                .snapshots()
                .map { snap ->
                    snap.toObjects(WorkgroupInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.statusEnum == InviteStatus.PENDING && it.ownerUserId != currentUid }
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(currentUid, OperationType.LIST, path)
        )
    }

    /** Manager observes all active multi-device members connected to a specific group/workspace. */
    fun observeWorkgroupMembers(workspaceId: String): Flow<List<WorkgroupMember>> = flow {
        val col = workspaceDoc(workspaceId).collection("members")
        val path = col.path
        emitAll(
            col.snapshots()
                .map { snap ->
                    snap.toObjects(WorkgroupMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.fullName.lowercase() }
                }
                .logAndRethrow(auth.currentUser?.uid ?: workspaceId, OperationType.LIST, path)
        )
    }

    /** Staff member observes their own live permission record inside a joined workgroup. */
    fun observeMyMembership(workspaceId: String, memberUid: String): Flow<WorkgroupMember?> = flow {
        val docRef = workspaceDoc(workspaceId).collection("members").document(memberUid)
        val path = docRef.path
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(WorkgroupMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .logAndRethrow(memberUid, OperationType.GET, path)
        )
    }

    /** User observes all kitchen workgroups they have joined across devices. */
    fun observeJoinedWorkgroups(userId: String): Flow<List<JoinedWorkgroup>> = flow {
        val path = "users/$userId/joined_workgroups"
        emitAll(
            db.collection("users").document(userId).collection("joined_workgroups")
                .snapshots()
                .map { snap ->
                    snap.toObjects(JoinedWorkgroup::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.joinedAt ?: Timestamp(0, 0) }
                }
                .logAndRethrow(userId, OperationType.LIST, path)
        )
    }

    // --- Multi-Group Creation, Unique Name Reservation & Logo Branding ---

    /**
     * Ensures the manager's primary kitchen has a corresponding /workgroups/{ownerUid} metadata
     * record with a Group Verification Code and a unique /group_names/{username} reservation.
     */
    suspend fun ensurePrimaryWorkgroup(
        ownerUid: String,
        defaultKitchenName: String,
        currencySymbol: String = "$",
        logoBase64: String? = null
    ): Result<Workgroup> {
        var docPath = "workgroups/$ownerUid"
        return try {
            val wgRef = db.collection("workgroups").document(ownerUid)
            docPath = wgRef.path
            val existingSnap = wgRef.get().await()
            if (existingSnap.exists()) {
                val existing = existingSnap.toObject(Workgroup::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                if (existing != null) return Result.success(existing)
            }

            val baseName = defaultKitchenName.trim().ifBlank { "Main Kitchen" }.take(70)
            var candidateHandle = WorkgroupInviteHelper.suggestUsernameFromName(baseName, ownerUid.take(4))

            // Check if candidate @username is already reserved by another manager
            var nameSnap = db.collection("group_names").document(candidateHandle).get().await()
            if (nameSnap.exists() && nameSnap.getString("ownerUserId") != ownerUid) {
                val suffix = ownerUid.lowercase().replace(Regex("[^a-z0-9]"), "").take(4).ifBlank { "1" }
                candidateHandle = "${candidateHandle.take(26)}_$suffix"
                nameSnap = db.collection("group_names").document(candidateHandle).get().await()
            }
            if (nameSnap.exists() && nameSnap.getString("ownerUserId") != ownerUid) {
                val uidSlug = ownerUid.lowercase().replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "main" }
                candidateHandle = "chef_$uidSlug"
                nameSnap = db.collection("group_names").document(candidateHandle).get().await()
            }

            val groupCode = WorkgroupInviteHelper.generateGroupCode()
            val primaryGroup = Workgroup(
                id = ownerUid,
                ownerUserId = ownerUid,
                name = baseName,
                normalizedName = candidateHandle,
                username = candidateHandle,
                groupCode = groupCode,
                primaryKitchen = true,
                currencySymbol = currencySymbol.ifBlank { "$" },
                targetFoodCostPct = 30.0,
                lowStockAlertsEnabled = true,
                posProvider = PosProvider.OMEGA_POS.id,
                logoBase64 = logoBase64
            )

            val batch = db.batch()
            val reservationRef = db.collection("group_names").document(candidateHandle)
            if (!nameSnap.exists()) {
                batch.set(
                    reservationRef,
                    mapOf(
                        "ownerUserId" to ownerUid,
                        "groupId" to ownerUid,
                        "displayName" to baseName,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(wgRef, primaryGroup.toCreateMap(ownerUid))
            batch.commit().await()

            // Also persist groupCode onto primary /users/{ownerUid} profile if it exists
            runCatching {
                db.collection("users").document(ownerUid).update(
                    mapOf(
                        "groupCode" to groupCode,
                        "kitchenName" to baseName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            Result.success(primaryGroup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Atomically checks global uniqueness in /group_names/{username}, reserves the unique @username handle,
     * and creates a new isolated restaurant/kitchen group under /workgroups/grp_*.
     */
    suspend fun createNewWorkgroup(
        name: String,
        username: String,
        currencySymbol: String = "$",
        logoBase64: String? = null
    ): Result<Workgroup> {
        var docPath = "workgroups"
        return try {
            val ownerUid = requireUserId()
            val cleanName = name.trim().take(80)
            require(cleanName.length >= 2) { "Group name must be at least 2 characters." }

            val cleanUsername = WorkgroupInviteHelper.finalizeUsername(
                username.ifBlank { WorkgroupInviteHelper.suggestUsernameFromName(cleanName) }
            )
            require(WorkgroupInviteHelper.isValidUsername(cleanUsername)) {
                "Group @username must be 3–32 characters using lowercase letters, numbers, or underscores."
            }

            val newGroupId = WorkgroupInviteHelper.generateGroupId()
            val groupCode = WorkgroupInviteHelper.generateGroupCode()

            val nameRef = db.collection("group_names").document(cleanUsername)
            val groupRef = db.collection("workgroups").document(newGroupId)
            docPath = groupRef.path

            val newGroup = Workgroup(
                id = newGroupId,
                ownerUserId = ownerUid,
                name = cleanName,
                normalizedName = cleanUsername,
                username = cleanUsername,
                groupCode = groupCode,
                primaryKitchen = false,
                currencySymbol = currencySymbol.trim().ifBlank { "$" }.take(5),
                targetFoodCostPct = 30.0,
                lowStockAlertsEnabled = true,
                posProvider = PosProvider.OMEGA_POS.id,
                logoBase64 = logoBase64?.takeIf { it.isNotBlank() && it.length <= 220_000 }
            )

            db.runTransaction { tx ->
                val existingReservation = tx.get(nameRef)
                if (existingReservation.exists()) {
                    throw IllegalArgumentException("@$cleanUsername is already taken. Please choose another unique username.")
                }
                tx.set(
                    nameRef,
                    mapOf(
                        "ownerUserId" to ownerUid,
                        "groupId" to newGroupId,
                        "displayName" to cleanName,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(groupRef, newGroup.toCreateMap(ownerUid))
            }.await()

            Result.success(newGroup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Renames a workgroup and/or updates its globally unique @username handle
     * (atomically swapping the /group_names/{username} reservation) and uploaded logo.
     */
    suspend fun updateWorkgroupBranding(
        workgroup: Workgroup,
        newName: String,
        newUsername: String,
        newLogoBase64: String?
    ): Result<Workgroup> {
        var docPath = "workgroups/${workgroup.id}"
        return try {
            val ownerUid = requireUserId()
            require(workgroup.ownerUserId == ownerUid || workgroup.id == ownerUid) {
                "Only the group manager can rename or edit this group."
            }
            val cleanName = newName.trim().take(80)
            require(cleanName.length >= 2) { "Group name must be at least 2 characters." }

            val fallbackHandle = workgroup.resolvedUsername.ifBlank {
                WorkgroupInviteHelper.suggestUsernameFromName(cleanName, ownerUid.take(4))
            }
            val cleanUsername = WorkgroupInviteHelper.finalizeUsername(newUsername.ifBlank { fallbackHandle })
            require(WorkgroupInviteHelper.isValidUsername(cleanUsername)) {
                "Group @username must be 3–32 characters using lowercase letters, numbers, or underscores."
            }

            val oldHandle = workgroup.resolvedUsername.trim().lowercase()
            val handleChanged = oldHandle != cleanUsername
            val safeLogo = newLogoBase64?.takeIf { it.isNotBlank() && it.length <= 220_000 }

            val groupRef = db.collection("workgroups").document(workgroup.id)
            val newNameRef = db.collection("group_names").document(cleanUsername)
            val oldNameRef = if (handleChanged && oldHandle.length in 2..64 && oldHandle.matches(Regex("^[a-z0-9_]+$"))) {
                db.collection("group_names").document(oldHandle)
            } else null
            docPath = groupRef.path

            val updates = mutableMapOf<String, Any>(
                "name" to cleanName,
                "normalizedName" to cleanUsername,
                "username" to cleanUsername,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (safeLogo != null) {
                updates["logoBase64"] = safeLogo
            }

            val fallbackCreatedGroup = workgroup.copy(
                ownerUserId = ownerUid,
                name = cleanName,
                normalizedName = cleanUsername,
                username = cleanUsername,
                groupCode = workgroup.groupCode.ifBlank { WorkgroupInviteHelper.generateGroupCode() },
                primaryKitchen = workgroup.primaryKitchen || workgroup.id == ownerUid,
                logoBase64 = safeLogo ?: workgroup.logoBase64
            )

            db.runTransaction { tx ->
                // All reads MUST execute before any writes in a Firestore transaction
                val groupSnap = tx.get(groupRef)
                val targetSnap = tx.get(newNameRef)
                val oldSnap = oldNameRef?.let { tx.get(it) }

                if (targetSnap.exists() && targetSnap.getString("groupId") != workgroup.id) {
                    throw IllegalArgumentException("@$cleanUsername is already taken. Please choose another unique username.")
                }

                if (!targetSnap.exists()) {
                    tx.set(
                        newNameRef,
                        mapOf(
                            "ownerUserId" to ownerUid,
                            "groupId" to workgroup.id,
                            "displayName" to cleanName,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                if (oldNameRef != null && oldSnap != null && oldSnap.exists() &&
                    oldSnap.getString("ownerUserId") == ownerUid &&
                    oldSnap.getString("groupId") == workgroup.id
                ) {
                    tx.delete(oldNameRef)
                }

                if (groupSnap.exists()) {
                    tx.update(groupRef, updates)
                } else {
                    tx.set(groupRef, fallbackCreatedGroup.toCreateMap(ownerUid))
                }
            }.await()

            // Mirror primary group display name & logo onto /users/{ownerUid}
            if (workgroup.primaryKitchen || workgroup.id == ownerUid) {
                runCatching {
                    val userUpdates = mutableMapOf<String, Any>(
                        "kitchenName" to cleanName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (safeLogo != null) userUpdates["logoBase64"] = safeLogo
                    db.collection("users").document(ownerUid).update(userUpdates).await()
                }
            }

            Result.success(fallbackCreatedGroup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- Strictly Email-Only Manager Invite Issuance, Role Management & Revocation ---

    /**
     * Atomically creates a StaffMember roster entry AND a strictly email-targeted WorkgroupInvite
     * carrying the group's verification code and manager-chosen role.
     */
    suspend fun createStaffWithInvite(
        workspaceId: String,
        member: StaffMember,
        kitchenName: String,
        groupCode: String,
        logoBase64: String?,
        invitedByName: String
    ): Result<WorkgroupInvite> {
        var docPath = "workgroup_invites"
        return try {
            val ownerUid = requireUserId()
            require(member.fullName.trim().isNotEmpty()) { "Staff name is required." }

            val normalizedEmail = member.inviteEmail?.trim()?.lowercase().orEmpty()
            require(WorkgroupInviteHelper.isValidEmail(normalizedEmail)) {
                "A valid staff email address is required. Invitations are strictly email-only."
            }

            val inviteCode = WorkgroupInviteHelper.generateInviteCode()
            val safeGroupCode = groupCode.trim().uppercase().ifBlank { WorkgroupInviteHelper.generateGroupCode() }
            val staffRef = workspaceDoc(workspaceId).collection("staff_roles").document()
            val inviteRef = db.collection("workgroup_invites").document(inviteCode)
            docPath = inviteRef.path

            val enrichedStaff = member.copy(
                userId = workspaceId,
                inviteEmail = normalizedEmail,
                inviteCode = inviteCode
            )
            val invite = WorkgroupInvite(
                id = inviteCode,
                ownerUserId = ownerUid,
                groupId = workspaceId,
                groupCode = safeGroupCode,
                kitchenName = kitchenName.ifBlank { "Main Kitchen" },
                invitedByName = invitedByName.ifBlank { "Manager" },
                staffRecordId = staffRef.id,
                staffName = enrichedStaff.fullName.trim(),
                inviteEmail = normalizedEmail,
                role = enrichedStaff.role,
                canViewReports = enrichedStaff.canViewReports,
                canExportCsv = enrichedStaff.canExportCsv,
                canApproveScans = enrichedStaff.canApproveScans,
                canEditRecipes = enrichedStaff.canEditRecipes,
                canManagePos = enrichedStaff.canManagePos,
                status = InviteStatus.PENDING.id,
                logoBase64 = logoBase64
            )

            val batch = db.batch()
            batch.set(staffRef, enrichedStaff.toCreateMap(workspaceId))
            batch.set(inviteRef, invite.toCreateMap(ownerUid))
            batch.commit().await()
            Result.success(invite)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    /** Generates a fresh strictly email-targeted invite for an existing staff record. */
    suspend fun regenerateStaffInvite(
        workspaceId: String,
        staff: StaffMember,
        kitchenName: String,
        groupCode: String,
        logoBase64: String?,
        invitedByName: String,
        existingPendingInviteCode: String? = null
    ): Result<WorkgroupInvite> {
        var docPath = "workgroup_invites"
        return try {
            val ownerUid = requireUserId()
            require(staff.id.isNotBlank()) { "Staff record ID required." }
            val normalizedEmail = staff.inviteEmail?.trim()?.lowercase().orEmpty()
            require(WorkgroupInviteHelper.isValidEmail(normalizedEmail)) {
                "This staff member does not have a valid email address. Invitations are strictly email-only."
            }

            val newCode = WorkgroupInviteHelper.generateInviteCode()
            val safeGroupCode = groupCode.trim().uppercase().ifBlank { WorkgroupInviteHelper.generateGroupCode() }
            val staffRef = workspaceDoc(workspaceId).collection("staff_roles").document(staff.id)
            val newInviteRef = db.collection("workgroup_invites").document(newCode)
            docPath = newInviteRef.path

            val invite = WorkgroupInvite(
                id = newCode,
                ownerUserId = ownerUid,
                groupId = workspaceId,
                groupCode = safeGroupCode,
                kitchenName = kitchenName.ifBlank { "Main Kitchen" },
                invitedByName = invitedByName.ifBlank { "Manager" },
                staffRecordId = staff.id,
                staffName = staff.fullName.trim(),
                inviteEmail = normalizedEmail,
                role = staff.role,
                canViewReports = staff.canViewReports,
                canExportCsv = staff.canExportCsv,
                canApproveScans = staff.canApproveScans,
                canEditRecipes = staff.canEditRecipes,
                canManagePos = staff.canManagePos,
                status = InviteStatus.PENDING.id,
                logoBase64 = logoBase64
            )

            val batch = db.batch()
            if (!existingPendingInviteCode.isNullOrBlank()) {
                val oldRef = db.collection("workgroup_invites").document(existingPendingInviteCode)
                batch.update(
                    oldRef,
                    mapOf(
                        "status" to InviteStatus.REVOKED.id,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.update(
                staffRef,
                mapOf(
                    "inviteCode" to newCode,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(newInviteRef, invite.toCreateMap(ownerUid))
            batch.commit().await()
            Result.success(invite)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Manager-Only Role & Permission Update: atomically updates both staff_roles/{staffId}
     * and (if the staff member already joined) members/{linkedMemberUid} so permissions sync live.
     */
    suspend fun updateStaffRoleAndPermissions(
        workspaceId: String,
        staffId: String,
        linkedMemberUid: String?,
        role: StaffRole,
        canViewReports: Boolean,
        canExportCsv: Boolean,
        canApproveScans: Boolean,
        canEditRecipes: Boolean,
        canManagePos: Boolean
    ): Result<Unit> {
        var docPath = "${workspaceDoc(workspaceId).path}/staff_roles/$staffId"
        return try {
            requireUserId()
            val root = workspaceDoc(workspaceId)
            val staffRef = root.collection("staff_roles").document(staffId)
            docPath = staffRef.path

            val updates = mapOf<String, Any>(
                "role" to role.id,
                "canViewReports" to canViewReports,
                "canExportCsv" to canExportCsv,
                "canApproveScans" to canApproveScans,
                "canEditRecipes" to canEditRecipes,
                "canManagePos" to canManagePos,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            val batch = db.batch()
            batch.update(staffRef, updates)
            if (!linkedMemberUid.isNullOrBlank()) {
                val memberRef = root.collection("members").document(linkedMemberUid)
                batch.update(memberRef, updates)
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

    suspend fun revokeInvite(inviteCode: String): Result<Unit> {
        var docPath = "workgroup_invites/$inviteCode"
        return try {
            requireUserId()
            val ref = db.collection("workgroup_invites").document(inviteCode)
            docPath = ref.path
            ref.update(
                mapOf(
                    "status" to InviteStatus.REVOKED.id,
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

    /** Invited staff member declines a pending email invite cleanly. */
    suspend fun declineWorkgroupInvite(inviteCode: String): Result<Unit> {
        var docPath = "workgroup_invites/$inviteCode"
        return try {
            requireUserId()
            val ref = db.collection("workgroup_invites").document(inviteCode)
            docPath = ref.path
            ref.update(
                mapOf(
                    "status" to InviteStatus.DECLINED.id,
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

    /**
     * Atomically removes a staff member from the group roster, revokes pending invites,
     * and deletes their multi-device WorkgroupMember access immediately.
     */
    suspend fun removeStaffAndRevokeAccess(
        workspaceId: String,
        staffId: String,
        members: List<WorkgroupMember>,
        invites: List<WorkgroupInvite>
    ): Result<Unit> {
        var docPath = "${workspaceDoc(workspaceId).path}/staff_roles/$staffId"
        return try {
            requireUserId()
            val root = workspaceDoc(workspaceId)
            val staffRef = root.collection("staff_roles").document(staffId)
            docPath = staffRef.path

            val batch = db.batch()
            batch.delete(staffRef)

            invites.filter { it.staffRecordId == staffId && it.statusEnum == InviteStatus.PENDING }.forEach { inv ->
                val invRef = db.collection("workgroup_invites").document(inv.id)
                batch.update(
                    invRef,
                    mapOf(
                        "status" to InviteStatus.REVOKED.id,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            members.filter { it.staffRecordId == staffId }.forEach { member ->
                val memRef = root.collection("members").document(member.memberUid.ifBlank { member.id })
                batch.delete(memRef)
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- Staff Joining & Leaving a Workgroup (Strictly Email + Group Verification Code) ---

    /**
     * Atomically accepts a PENDING strictly-email WorkgroupInvite ONLY AFTER verifying:
     * 1. Caller's signed-in email matches invite.inviteEmail
     * 2. User explicitly confirmed AND entered the matching Group Verification Code
     */
    suspend fun acceptWorkgroupInvite(
        invite: WorkgroupInvite,
        enteredGroupCode: String,
        memberDisplayName: String,
        memberEmail: String?
    ): Result<JoinedWorkgroup> {
        val targetWorkspaceId = invite.resolvedWorkspaceId
        var docPath = "${workspaceDoc(targetWorkspaceId).path}/members"
        return try {
            val uid = requireUserId()
            require(invite.id.isNotBlank()) { "Invite record missing." }
            require(invite.ownerUserId != uid) { "You already own this group." }

            val normalizedEmail = memberEmail?.trim()?.lowercase().orEmpty()
            val requiredEmail = invite.inviteEmail?.trim()?.lowercase().orEmpty()
            require(requiredEmail.isNotBlank() && normalizedEmail == requiredEmail) {
                "This invitation is strictly for $requiredEmail. Please sign in with that Google account."
            }

            val expectedCode = invite.requiredVerificationCode.trim().uppercase()
            val cleanedEntered = enteredGroupCode.trim().uppercase()
            require(cleanedEntered.isNotBlank() && cleanedEntered == expectedCode) {
                "Incorrect Group Verification Code. Please ask your manager for the code shown in their email invite."
            }

            val memberRef = workspaceDoc(targetWorkspaceId).collection("members").document(uid)
            val inviteRef = db.collection("workgroup_invites").document(invite.id)
            val joinedRef = db.collection("users").document(uid)
                .collection("joined_workgroups").document(targetWorkspaceId)
            docPath = memberRef.path

            val memberDoc = WorkgroupMember(
                ownerUserId = invite.ownerUserId,
                memberUid = uid,
                staffRecordId = invite.staffRecordId,
                inviteCode = invite.id,
                fullName = invite.staffName.ifBlank { memberDisplayName.ifBlank { "Staff Member" } },
                email = normalizedEmail,
                role = invite.role,
                canViewReports = invite.canViewReports,
                canExportCsv = invite.canExportCsv,
                canApproveScans = invite.canApproveScans,
                canEditRecipes = invite.canEditRecipes,
                canManagePos = invite.canManagePos,
                active = true
            )

            val joined = JoinedWorkgroup(
                id = targetWorkspaceId,
                ownerUserId = invite.ownerUserId,
                groupId = targetWorkspaceId,
                kitchenName = invite.kitchenName,
                role = invite.role,
                inviteCode = invite.id,
                logoBase64 = invite.logoBase64
            )

            val inviteUpdate = mapOf<String, Any>(
                "status" to InviteStatus.ACCEPTED.id,
                "claimedByUid" to uid,
                "claimedByEmail" to normalizedEmail.take(160),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            val batch = db.batch()
            batch.set(memberRef, memberDoc.toCreateMap())
            batch.update(inviteRef, inviteUpdate)
            batch.set(joinedRef, joined.toCreateMap())
            batch.commit().await()
            Result.success(joined)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /** Staff member cleanly disconnects their device from a joined workgroup. */
    suspend fun leaveWorkgroup(workspaceId: String): Result<Unit> {
        var docPath = "${workspaceDoc(workspaceId).path}/members"
        return try {
            val uid = requireUserId()
            val memberRef = workspaceDoc(workspaceId).collection("members").document(uid)
            val joinedRef = db.collection("users").document(uid)
                .collection("joined_workgroups").document(workspaceId)
            docPath = memberRef.path

            val batch = db.batch()
            batch.delete(memberRef)
            batch.delete(joinedRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
