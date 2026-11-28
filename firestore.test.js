const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "chef_alice_01";
const BOB_UID = "chef_bob_02";
const ADMIN_UID = "admin_mena_01";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

// --- 1. Unauthenticated & Cross-User Isolation ---

test("Unauthenticated user: denied across all collections", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection("users").doc(ALICE_UID).get());
  await assertFails(db.collection("users").doc(ALICE_UID).collection("inventory").get());
  await assertFails(db.collection("users").doc(ALICE_UID).collection("recipes").get());
  await assertFails(db.collection("users").doc(ALICE_UID).collection("flagged_scans").get());
  await assertFails(db.collection("users").doc(ALICE_UID).collection("staff_roles").get());
  await assertFails(db.collection("users").doc(ALICE_UID).collection("pos_syncs").get());
});

test("Cross-user access: Bob cannot read or write Alice's kitchen workspace", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("inventory").doc("item_chicken").set({
      userId: ALICE_UID,
      canonicalKey: "chicken",
      nameEn: "Chicken",
      nameAr: "دجاج",
      aliases: ["chicken", "دجاج"],
      category: "PROTEIN",
      unit: "kg",
      grossQuantity: 1.0,
      wasteQuantity: 0.1,
      consumedQuantity: 0.0,
      totalCost: 10.0,
      unitCost: 10.0,
      minStockLevel: 0.5,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("inventory").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("inventory").doc("item_chicken").update({
      wasteQuantity: 0.5,
      updatedAt: pastDate(500),
    })
  );
});

// --- 2. KitchenProfile & Role Immutability / Email Spoofing ---

test("KitchenProfile: owner creates profile, cannot mutate role or inject shadow fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    profileRef.set({
      userId: ALICE_UID,
      displayName: "Chef Alice",
      kitchenName: "Atelier Kitchen",
      role: "MANAGER",
      currencySymbol: "$",
      targetFoodCostPct: 30.0,
      lowStockAlertsEnabled: true,
      posProvider: "NONE",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid partial update
  await assertSucceeds(
    profileRef.update({
      kitchenName: "Bistro Noir",
      posProvider: "SQUARE",
      posEndpointUrl: "https://pos.example.com/sales",
      updatedAt: pastDate(500),
    })
  );

  // Reject role mutation by regular owner
  await assertFails(
    profileRef.update({
      role: "LINE_COOK",
      updatedAt: pastDate(400),
    })
  );

  // Reject shadow ghost field
  await assertFails(
    profileRef.update({
      isSuperAdmin: true,
      updatedAt: pastDate(400),
    })
  );

  // Reject unverified admin email spoofing
  const spoofDb = testEnv
    .authenticatedContext("spoof_uid", {
      email: "MenaZaki60@gmail.com",
      email_verified: false,
    })
    .firestore();
  await assertFails(spoofDb.collection("users").doc(ALICE_UID).get());

  // Verified bootstrapped admin succeeds
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: "MenaZaki60@gmail.com",
      email_verified: true,
    })
    .firestore();
  await assertSucceeds(adminDb.collection("users").doc(ALICE_UID).get());
});

// --- 3. Inventory, Ledger & Future Timestamp Rejection ---

test("Inventory & Ledger: Kotlin-mirrored create/update succeed; @DocumentId 'id' and future timestamps fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();
  const invRef = aliceDb.collection("users").doc(ALICE_UID).collection("inventory").doc("inv_1");

  await assertSucceeds(
    invRef.set({
      userId: ALICE_UID,
      canonicalKey: "chicken",
      nameEn: "Chicken",
      nameAr: "دجاج",
      aliases: ["Chicken", "دجاج"],
      category: "PROTEIN",
      unit: "kg",
      grossQuantity: 1.0,
      wasteQuantity: 0.1,
      consumedQuantity: 0.0,
      totalCost: 10.0,
      unitCost: 10.0,
      minStockLevel: 0.5,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Partial waste update
  await assertSucceeds(
    invRef.update({
      wasteQuantity: 0.15,
      updatedAt: pastDate(500),
    })
  );

  // Reject 'id' field in document body
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("inventory").doc("inv_bad").set({
      id: "inv_bad",
      userId: ALICE_UID,
      canonicalKey: "tomato",
      nameEn: "Tomatoes",
      nameAr: "طماطم",
      aliases: ["tomato"],
      category: "PRODUCE",
      unit: "kg",
      grossQuantity: 2.0,
      wasteQuantity: 0.0,
      consumedQuantity: 0.0,
      totalCost: 6.0,
      unitCost: 3.0,
      minStockLevel: 0.5,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("ledger_entries").doc("led_future").set({
      userId: ALICE_UID,
      itemId: "inv_1",
      itemNameEn: "Chicken",
      itemNameAr: "دجاج",
      rawScannedLabel: "دجاج",
      entryType: "WASTE",
      category: "PROTEIN",
      unit: "kg",
      grossDelta: 0.0,
      wasteDelta: 0.1,
      costAmount: 0.0,
      wasteCostAmount: 1.0,
      loggedByRole: "MANAGER",
      hourOfDay: 19,
      createdAt: new Date(Date.now() + 120000),
    })
  );

  // Valid ledger entry succeeds; client update on append-only ledger fails
  const ledRef = aliceDb.collection("users").doc(ALICE_UID).collection("ledger_entries").doc("led_1");
  await assertSucceeds(
    ledRef.set({
      userId: ALICE_UID,
      itemId: "inv_1",
      itemNameEn: "Chicken",
      itemNameAr: "دجاج",
      rawScannedLabel: "دجاج",
      entryType: "WASTE",
      category: "PROTEIN",
      unit: "kg",
      grossDelta: 0.0,
      wasteDelta: 0.1,
      costAmount: 0.0,
      wasteCostAmount: 1.0,
      loggedByRole: "MANAGER",
      hourOfDay: 19,
      reason: "Trim fat",
      createdAt: ts,
    })
  );
  await assertFails(ledRef.update({ wasteDelta: 0.0 }));
});

// --- 4. Flagged Scans Terminal State Locking ---

test("FlaggedScans: PENDING -> APPROVED succeeds, then terminal state locks further updates", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();
  const flagRef = aliceDb.collection("users").doc(ALICE_UID).collection("flagged_scans").doc("flag_1");

  await assertSucceeds(
    flagRef.set({
      userId: ALICE_UID,
      rawLabel: "SKU-X99 Mystery Blend",
      suggestedNameEn: "",
      suggestedNameAr: "",
      quantity: 1.0,
      unit: "kg",
      totalCost: 14.5,
      confidence: 0.35,
      reviewReason: "Unknown supplier code",
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    flagRef.update({
      status: "APPROVED",
      resolvedItemId: "inv_1",
      suggestedNameEn: "House Spice Blend",
      suggestedNameAr: "بهارات",
      updatedAt: pastDate(500),
    })
  );

  // Terminal state lock: cannot reopen APPROVED item
  await assertFails(
    flagRef.update({
      status: "DISMISSED",
      updatedAt: pastDate(200),
    })
  );
});

// --- 5. Recipes, Staff Roles, and POS Syncs ---

test("Recipes, Sub-Recipes, Staff Roles, and POS Syncs: valid Kotlin payloads succeed and invalid sub-recipes fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();

  // Production sub-recipe (sauce — not a selling item)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("sub_garlic_sauce").set({
      userId: ALICE_UID,
      nameEn: "House Garlic Sauce",
      nameAr: "صوص الثوم",
      recipeType: "SUB_RECIPE",
      servings: 1,
      yieldQuantity: 1000.0,
      yieldUnit: "mL",
      menuPrice: 0.0,
      targetFoodCostPct: 30.0,
      ingredients: [
        { itemId: "inv_garlic", itemName: "Garlic", quantity: 120.0, unit: "g", sourceType: "INVENTORY" },
        { itemId: "inv_oil", itemName: "Vegetable Oil", quantity: 600.0, unit: "mL", sourceType: "INVENTORY" },
      ],
      category: "Sauce",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Menu dish referencing both raw inventory and the production sub-recipe
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_1").set({
      userId: ALICE_UID,
      nameEn: "Grilled Chicken Plate",
      nameAr: "طبق دجاج مشوي",
      recipeType: "MENU_ITEM",
      servings: 1,
      yieldQuantity: 1.0,
      yieldUnit: "pcs",
      menuPrice: 18.0,
      targetFoodCostPct: 28.0,
      ingredients: [
        { itemId: "inv_1", itemName: "Chicken", quantity: 250.0, unit: "g", sourceType: "INVENTORY" },
        { itemId: "sub_garlic_sauce", itemName: "House Garlic Sauce", quantity: 60.0, unit: "mL", sourceType: "SUB_RECIPE" },
      ],
      posSku: "CHK-GRL-01",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject invalid recipeType
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_bad_type").set({
      userId: ALICE_UID,
      nameEn: "Bad Type",
      nameAr: "",
      recipeType: "INVALID",
      servings: 1,
      yieldQuantity: 1.0,
      yieldUnit: "kg",
      menuPrice: 0.0,
      targetFoodCostPct: 30.0,
      ingredients: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject zero yieldQuantity
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_bad_yield").set({
      userId: ALICE_UID,
      nameEn: "Zero Yield Sauce",
      nameAr: "",
      recipeType: "SUB_RECIPE",
      servings: 1,
      yieldQuantity: 0.0,
      yieldUnit: "mL",
      menuPrice: 0.0,
      targetFoodCostPct: 30.0,
      ingredients: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("staff_roles").doc("staff_1").set({
      userId: ALICE_UID,
      fullName: "Chef Omar",
      role: "SOUS_CHEF",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: true,
      canEditRecipes: true,
      canManagePos: false,
      active: true,
      station: "Grill",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  const posRef = aliceDb.collection("users").doc(ALICE_UID).collection("pos_syncs").doc("pos_1");
  await assertSucceeds(
    posRef.set({
      userId: ALICE_UID,
      provider: "Square POS",
      syncMode: "Z_REPORT_BATCH",
      ordersCount: 15,
      totalRevenue: 270.0,
      deductedItemsCount: 4,
      summary: "15 plates synced across 4 ingredients",
      createdAt: ts,
    })
  );
  await assertFails(posRef.update({ ordersCount: 99 }));
});

// --- 6. Multi-Device Workgroup Invites (Email & Shareable Link) & Server-Side RBAC ---

test("Workgroup email invite: Bob accepts on his device, accesses recipes read-only, cannot escalate or reuse", async () => {
  const CHARLIE_UID = "chef_charlie_03";
  const INVITE_CODE = "CM-AB12-CD34-EF56";
  const ts = pastDate();

  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@kitchen.test",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "Bob@Kitchen.Test",
    email_verified: true,
  }).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID, {
    email: "charlie@kitchen.test",
    email_verified: true,
  }).firestore();

  // Alice creates a recipe and an email-targeted Line Cook invite for Bob (canEditRecipes = false)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_garlic_chicken").set({
      userId: ALICE_UID,
      nameEn: "Garlic Chicken",
      nameAr: "دجاج بالثوم",
      recipeType: "MENU_ITEM",
      servings: 1,
      yieldQuantity: 1.0,
      yieldUnit: "pcs",
      menuPrice: 16.0,
      targetFoodCostPct: 30.0,
      ingredients: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("workgroup_invites").doc(INVITE_CODE).set({
      ownerUserId: ALICE_UID,
      kitchenName: "Atelier Kitchen",
      invitedByName: "Chef Alice",
      staffRecordId: "staff_bob_1",
      staffName: "Bob Line",
      inviteEmail: "bob@kitchen.test",
      role: "LINE_COOK",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: false,
      canEditRecipes: false,
      canManagePos: false,
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Charlie (wrong email) CANNOT read, list, or claim Bob's email-targeted invite
  await assertFails(charlieDb.collection("workgroup_invites").doc(INVITE_CODE).get());
  await assertFails(
    charlieDb.collection("users").doc(ALICE_UID).collection("members").doc(CHARLIE_UID).set({
      ownerUserId: ALICE_UID,
      memberUid: CHARLIE_UID,
      staffRecordId: "staff_bob_1",
      inviteCode: INVITE_CODE,
      fullName: "Charlie Spoof",
      email: "charlie@kitchen.test",
      role: "LINE_COOK",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: false,
      canEditRecipes: false,
      canManagePos: false,
      active: true,
      joinedAt: ts,
      updatedAt: ts,
    })
  );

  // Bob (verified email match, case-insensitive) CAN list his pending email invites
  await assertSucceeds(
    bobDb.collection("workgroup_invites").where("inviteEmail", "==", "bob@kitchen.test").get()
  );

  // Bob CANNOT escalate permissions (e.g. claiming canEditRecipes: true when invite has false)
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID).set({
      ownerUserId: ALICE_UID,
      memberUid: BOB_UID,
      staffRecordId: "staff_bob_1",
      inviteCode: INVITE_CODE,
      fullName: "Bob Line",
      email: "bob@kitchen.test",
      role: "LINE_COOK",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: false,
      canEditRecipes: true, // Escalation attempt!
      canManagePos: false,
      active: true,
      joinedAt: ts,
      updatedAt: ts,
    })
  );

  // Bob atomically accepts the invite with exact matching permissions
  const batch = bobDb.batch();
  batch.set(bobDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID), {
    ownerUserId: ALICE_UID,
    memberUid: BOB_UID,
    staffRecordId: "staff_bob_1",
    inviteCode: INVITE_CODE,
    fullName: "Bob Line",
    email: "bob@kitchen.test",
    role: "LINE_COOK",
    canViewReports: false,
    canExportCsv: false,
    canApproveScans: false,
    canEditRecipes: false,
    canManagePos: false,
    active: true,
    joinedAt: ts,
    updatedAt: ts,
  });
  batch.update(bobDb.collection("workgroup_invites").doc(INVITE_CODE), {
    status: "ACCEPTED",
    claimedByUid: BOB_UID,
    claimedByEmail: "bob@kitchen.test",
    updatedAt: pastDate(500),
  });
  batch.set(bobDb.collection("users").doc(BOB_UID).collection("joined_workgroups").doc(ALICE_UID), {
    ownerUserId: ALICE_UID,
    kitchenName: "Atelier Kitchen",
    role: "LINE_COOK",
    inviteCode: INVITE_CODE,
    joinedAt: ts,
  });
  await assertSucceeds(batch.commit());

  // Now Bob CAN read Alice's recipes on his own device!
  await assertSucceeds(bobDb.collection("users").doc(ALICE_UID).collection("recipes").get());

  // Bob CANNOT create or edit Alice's recipes because canEditRecipes == false
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_bob_hack").set({
      userId: ALICE_UID,
      nameEn: "Unauthorized Dish",
      nameAr: "",
      servings: 1,
      menuPrice: 9.0,
      targetFoodCostPct: 30.0,
      ingredients: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob CANNOT read POS syncs because canViewReports == false && canManagePos == false
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("pos_syncs").get());

  // Alice revokes Bob's access by deleting his member doc -> Bob immediately loses access
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID).delete()
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("recipes").get());
});

test("Workgroup invites are strictly email-only: missing inviteEmail fails, matching verified email can join or decline", async () => {
  const OPEN_INVITE_CODE = "CM-OPEN-9988-7766";
  const SOUS_INVITE_CODE = "CM-SOUS-9988-7766";
  const DECLINE_INVITE_CODE = "CM-DECL-1111-2222";
  const ts = pastDate();

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "sous@kitchen.test",
    email_verified: true,
  }).firestore();
  const charlieDb = testEnv.authenticatedContext("chef_charlie_03", {
    email: "charlie@kitchen.test",
    email_verified: true,
  }).firestore();

  // Strictly email-only: creating an invite WITHOUT inviteEmail MUST FAIL
  await assertFails(
    aliceDb.collection("workgroup_invites").doc(OPEN_INVITE_CODE).set({
      ownerUserId: ALICE_UID,
      kitchenName: "Atelier Kitchen",
      invitedByName: "Chef Alice",
      staffRecordId: "staff_sous_1",
      staffName: "Chef Omar",
      role: "SOUS_CHEF",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: true,
      canEditRecipes: true,
      canManagePos: false,
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice creates a strictly email-targeted invite with groupCode for Sous Chef
  await assertSucceeds(
    aliceDb.collection("workgroup_invites").doc(SOUS_INVITE_CODE).set({
      ownerUserId: ALICE_UID,
      groupId: ALICE_UID,
      groupCode: "GRP-7788",
      kitchenName: "Atelier Kitchen",
      invitedByName: "Chef Alice",
      staffRecordId: "staff_sous_1",
      staffName: "Chef Omar",
      inviteEmail: "sous@kitchen.test",
      role: "SOUS_CHEF",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: true,
      canEditRecipes: true,
      canManagePos: false,
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Charlie (wrong email) CANNOT read or accept Bob's email-only invite
  await assertFails(charlieDb.collection("workgroup_invites").doc(SOUS_INVITE_CODE).get());

  // Bob (matching verified email) can preview and accept atomically
  await assertSucceeds(bobDb.collection("workgroup_invites").doc(SOUS_INVITE_CODE).get());
  const batch = bobDb.batch();
  batch.set(bobDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID), {
    ownerUserId: ALICE_UID,
    memberUid: BOB_UID,
    staffRecordId: "staff_sous_1",
    inviteCode: SOUS_INVITE_CODE,
    fullName: "Chef Omar",
    email: "sous@kitchen.test",
    role: "SOUS_CHEF",
    canViewReports: false,
    canExportCsv: false,
    canApproveScans: true,
    canEditRecipes: true,
    canManagePos: false,
    active: true,
    joinedAt: ts,
    updatedAt: ts,
  });
  batch.update(bobDb.collection("workgroup_invites").doc(SOUS_INVITE_CODE), {
    status: "ACCEPTED",
    claimedByUid: BOB_UID,
    claimedByEmail: "sous@kitchen.test",
    updatedAt: pastDate(500),
  });
  await assertSucceeds(batch.commit());

  // Bob (Sous Chef with canEditRecipes == true) CAN create recipes in Alice's workgroup
  await assertSucceeds(
    bobDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_sous_sauce").set({
      userId: ALICE_UID,
      nameEn: "Truffle Mayo",
      nameAr: "",
      recipeType: "SUB_RECIPE",
      servings: 1,
      yieldQuantity: 500.0,
      yieldUnit: "mL",
      menuPrice: 0.0,
      targetFoodCostPct: 30.0,
      ingredients: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Manager-only roles: Bob CANNOT update his own member role to MANAGER
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID).update({
      role: "MANAGER",
      canManagePos: true,
      updatedAt: pastDate(300),
    })
  );

  // Manager (Alice) CAN update Bob's role & permissions
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID).update({
      role: "MANAGER",
      canViewReports: true,
      updatedAt: pastDate(250),
    })
  );

  // Invite decline flow: Charlie can decline an email invite sent to him
  await assertSucceeds(
    aliceDb.collection("workgroup_invites").doc(DECLINE_INVITE_CODE).set({
      ownerUserId: ALICE_UID,
      groupId: ALICE_UID,
      groupCode: "GRP-7788",
      kitchenName: "Atelier Kitchen",
      invitedByName: "Chef Alice",
      staffRecordId: "staff_charlie_1",
      staffName: "Charlie",
      inviteEmail: "charlie@kitchen.test",
      role: "LINE_COOK",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: false,
      canEditRecipes: false,
      canManagePos: false,
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    charlieDb.collection("workgroup_invites").doc(DECLINE_INVITE_CODE).update({
      status: "DECLINED",
      updatedAt: pastDate(200),
    })
  );
});

// --- 7. Production Stock Management & RBAC ---

test("Production stock: owner sets productionStock, Line Cook can update productionStock only, negative values fail", async () => {
  const INVITE_CODE = "CM-PROD-1122-3344";
  const ts = pastDate();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "line@kitchen.test",
    email_verified: true,
  }).firestore();

  const subRef = aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("sub_toum");

  // Owner creates sub-recipe with initial productionStock and minProductionStock
  await assertSucceeds(
    subRef.set({
      userId: ALICE_UID,
      nameEn: "Garlic Toum",
      nameAr: "ثومية",
      recipeType: "SUB_RECIPE",
      servings: 1,
      yieldQuantity: 1000.0,
      yieldUnit: "mL",
      productionStock: 1500.0,
      minProductionStock: 500.0,
      menuPrice: 0.0,
      targetFoodCostPct: 30.0,
      ingredients: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject negative productionStock
  await assertFails(
    subRef.update({
      productionStock: -50.0,
      updatedAt: pastDate(500),
    })
  );

  // Invite Bob as Line Cook (canEditRecipes = false) strictly by email
  await assertSucceeds(
    aliceDb.collection("workgroup_invites").doc(INVITE_CODE).set({
      ownerUserId: ALICE_UID,
      groupId: ALICE_UID,
      groupCode: "GRP-1122",
      kitchenName: "Atelier Kitchen",
      invitedByName: "Chef Alice",
      staffRecordId: "staff_line_1",
      staffName: "Bob Prep",
      inviteEmail: "line@kitchen.test",
      role: "LINE_COOK",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: false,
      canEditRecipes: false,
      canManagePos: false,
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  const acceptBatch = bobDb.batch();
  acceptBatch.set(bobDb.collection("users").doc(ALICE_UID).collection("members").doc(BOB_UID), {
    ownerUserId: ALICE_UID,
    memberUid: BOB_UID,
    staffRecordId: "staff_line_1",
    inviteCode: INVITE_CODE,
    fullName: "Bob Prep",
    email: "line@kitchen.test",
    role: "LINE_COOK",
    canViewReports: false,
    canExportCsv: false,
    canApproveScans: false,
    canEditRecipes: false,
    canManagePos: false,
    active: true,
    joinedAt: ts,
    updatedAt: ts,
  });
  acceptBatch.update(bobDb.collection("workgroup_invites").doc(INVITE_CODE), {
    status: "ACCEPTED",
    claimedByUid: BOB_UID,
    claimedByEmail: "line@kitchen.test",
    updatedAt: pastDate(500),
  });
  await assertSucceeds(acceptBatch.commit());

  const bobSubRef = bobDb.collection("users").doc(ALICE_UID).collection("recipes").doc("sub_toum");

  // Line Cook CAN update productionStock & minProductionStock during prep shifts
  await assertSucceeds(
    bobSubRef.update({
      productionStock: 2500.0,
      minProductionStock: 600.0,
      updatedAt: pastDate(300),
    })
  );

  // Line Cook STILL CANNOT alter recipe formula or menuPrice
  await assertFails(
    bobSubRef.update({
      productionStock: 2600.0,
      menuPrice: 99.0,
      updatedAt: pastDate(200),
    })
  );
});

// --- 8. Multi-Group Management, Unique Group Names & Logos ---

test("Multi-group: unique /group_names lock prevents duplicate names; /workgroups isolates data and supports logos & email invites", async () => {
  const GROUP_ID = "grp_beirut001";
  const GROUP_NAME_KEY = "beirut_bistro";
  const INVITE_CODE = "CM-BEIRUT-4455";
  const ts = pastDate();

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "branchchef@kitchen.test",
    email_verified: true,
  }).firestore();
  const charlieDb = testEnv.authenticatedContext("chef_charlie_03").firestore();

  // Alice atomically reserves unique group name and creates a new branch group with a logo
  const createBatch = aliceDb.batch();
  createBatch.set(aliceDb.collection("group_names").doc(GROUP_NAME_KEY), {
    ownerUserId: ALICE_UID,
    groupId: GROUP_ID,
    displayName: "Beirut Bistro",
    createdAt: ts,
  });
  createBatch.set(aliceDb.collection("workgroups").doc(GROUP_ID), {
    ownerUserId: ALICE_UID,
    name: "Beirut Bistro",
    normalizedName: GROUP_NAME_KEY,
    groupCode: "GRP-BEIR",
    primaryKitchen: false,
    currencySymbol: "$",
    targetFoodCostPct: 28.0,
    lowStockAlertsEnabled: true,
    posProvider: "OMEGA_POS",
    logoBase64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertSucceeds(createBatch.commit());

  // Bob CANNOT reserve the same unique group name "beirut_bistro"
  await assertFails(
    bobDb.collection("group_names").doc(GROUP_NAME_KEY).set({
      ownerUserId: BOB_UID,
      groupId: "grp_bob_copy",
      displayName: "Beirut Bistro",
      createdAt: ts,
    })
  );

  // Listing /group_names is denied to prevent enumeration
  await assertFails(bobDb.collection("group_names").get());

  // Alice can list her own workgroups and add inventory to the new branch
  await assertSucceeds(
    aliceDb.collection("workgroups").where("ownerUserId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    aliceDb.collection("workgroups").doc(GROUP_ID).collection("inventory").doc("inv_olive").set({
      userId: GROUP_ID,
      canonicalKey: "olive_oil",
      nameEn: "Olive Oil",
      nameAr: "زيت زيتون",
      aliases: ["Olive Oil"],
      category: "DRY_GOODS",
      unit: "L",
      grossQuantity: 12.0,
      wasteQuantity: 0.0,
      consumedQuantity: 0.0,
      totalCost: 114.0,
      unitCost: 9.5,
      minStockLevel: 3.0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Charlie (non-member) CANNOT read the branch or its inventory
  await assertFails(charlieDb.collection("workgroups").doc(GROUP_ID).get());
  await assertFails(charlieDb.collection("workgroups").doc(GROUP_ID).collection("inventory").get());

  // Alice invites Bob by email to the branch workgroup
  await assertSucceeds(
    aliceDb.collection("workgroup_invites").doc(INVITE_CODE).set({
      ownerUserId: ALICE_UID,
      groupId: GROUP_ID,
      groupCode: "GRP-BEIR",
      kitchenName: "Beirut Bistro",
      invitedByName: "Chef Alice",
      staffRecordId: "staff_b_1",
      staffName: "Chef Bob",
      inviteEmail: "branchchef@kitchen.test",
      role: "SOUS_CHEF",
      canViewReports: false,
      canExportCsv: false,
      canApproveScans: true,
      canEditRecipes: true,
      canManagePos: false,
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob accepts invite into /workgroups/{GROUP_ID}/members/{BOB_UID}
  const joinBatch = bobDb.batch();
  joinBatch.set(bobDb.collection("workgroups").doc(GROUP_ID).collection("members").doc(BOB_UID), {
    ownerUserId: ALICE_UID,
    memberUid: BOB_UID,
    staffRecordId: "staff_b_1",
    inviteCode: INVITE_CODE,
    fullName: "Chef Bob",
    email: "branchchef@kitchen.test",
    role: "SOUS_CHEF",
    canViewReports: false,
    canExportCsv: false,
    canApproveScans: true,
    canEditRecipes: true,
    canManagePos: false,
    active: true,
    joinedAt: ts,
    updatedAt: ts,
  });
  joinBatch.update(bobDb.collection("workgroup_invites").doc(INVITE_CODE), {
    status: "ACCEPTED",
    claimedByUid: BOB_UID,
    claimedByEmail: "branchchef@kitchen.test",
    updatedAt: pastDate(500),
  });
  joinBatch.set(bobDb.collection("users").doc(BOB_UID).collection("joined_workgroups").doc(GROUP_ID), {
    ownerUserId: ALICE_UID,
    groupId: GROUP_ID,
    kitchenName: "Beirut Bistro",
    role: "SOUS_CHEF",
    inviteCode: INVITE_CODE,
    joinedAt: ts,
  });
  await assertSucceeds(joinBatch.commit());

  // Now Bob CAN read the branch metadata and inventory, but CANNOT escalate his own role
  await assertSucceeds(bobDb.collection("workgroups").doc(GROUP_ID).get());
  await assertSucceeds(bobDb.collection("workgroups").doc(GROUP_ID).collection("inventory").get());
  await assertFails(
    bobDb.collection("workgroups").doc(GROUP_ID).collection("members").doc(BOB_UID).update({
      role: "MANAGER",
      updatedAt: pastDate(200),
    })
  );

  // Alice (owner) CAN update Bob's role in the branch workgroup
  await assertSucceeds(
    aliceDb.collection("workgroups").doc(GROUP_ID).collection("members").doc(BOB_UID).update({
      role: "MANAGER",
      canManagePos: true,
      updatedAt: pastDate(150),
    })
  );
});

// --- 9. Primary Workgroup Existence Check, Rename & Unique @username Handle ---

test("Workgroup rename & unique @username: non-existent primary workgroup get allowed for owner; rename & handle swap succeed; duplicate/invalid usernames rejected", async () => {
  const ts = pastDate();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Owner CAN check existence of /workgroups/{ownerUid} BEFORE it exists (fixes ensurePrimaryWorkgroup)
  await assertSucceeds(aliceDb.collection("workgroups").doc(ALICE_UID).get());
  // Another user CANNOT read Alice's non-existent primary workgroup doc
  await assertFails(bobDb.collection("workgroups").doc(ALICE_UID).get());

  // Alice creates her primary workgroup with unique @username handle "alice_kitchen"
  const initBatch = aliceDb.batch();
  initBatch.set(aliceDb.collection("group_names").doc("alice_kitchen"), {
    ownerUserId: ALICE_UID,
    groupId: ALICE_UID,
    displayName: "Alice Kitchen",
    createdAt: ts,
  });
  initBatch.set(aliceDb.collection("workgroups").doc(ALICE_UID), {
    ownerUserId: ALICE_UID,
    name: "Alice Kitchen",
    normalizedName: "alice_kitchen",
    username: "alice_kitchen",
    groupCode: "GRP-ALIC",
    primaryKitchen: true,
    currencySymbol: "$",
    targetFoodCostPct: 30.0,
    lowStockAlertsEnabled: true,
    posProvider: "OMEGA_POS",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertSucceeds(initBatch.commit());

  // Invalid username (uppercase or too short) is rejected
  await assertFails(
    aliceDb.collection("workgroups").doc(ALICE_UID).update({
      username: "Bad Handle!",
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    aliceDb.collection("workgroups").doc(ALICE_UID).update({
      username: "ab",
      updatedAt: pastDate(500),
    })
  );

  // Alice renames display name AND swaps unique @username to "beirut_grill" atomically
  const renameBatch = aliceDb.batch();
  renameBatch.set(aliceDb.collection("group_names").doc("beirut_grill"), {
    ownerUserId: ALICE_UID,
    groupId: ALICE_UID,
    displayName: "Beirut Grill & Bar",
    createdAt: pastDate(400),
  });
  renameBatch.delete(aliceDb.collection("group_names").doc("alice_kitchen"));
  renameBatch.update(aliceDb.collection("workgroups").doc(ALICE_UID), {
    name: "Beirut Grill & Bar",
    normalizedName: "beirut_grill",
    username: "beirut_grill",
    updatedAt: pastDate(400),
  });
  await assertSucceeds(renameBatch.commit());

  // Bob CANNOT claim Alice's new @beirut_grill username
  await assertFails(
    bobDb.collection("group_names").doc("beirut_grill").set({
      ownerUserId: BOB_UID,
      groupId: BOB_UID,
      displayName: "Copy Grill",
      createdAt: pastDate(300),
    })
  );

  // Bob CAN now claim the released "alice_kitchen" username
  await assertSucceeds(
    bobDb.collection("group_names").doc("alice_kitchen").set({
      ownerUserId: BOB_UID,
      groupId: BOB_UID,
      displayName: "Alice Kitchen 2",
      createdAt: pastDate(200),
    })
  );
});



