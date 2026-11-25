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

test("Recipes, Staff Roles, and POS Syncs: valid Kotlin payloads succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recipes").doc("rec_1").set({
      userId: ALICE_UID,
      nameEn: "Grilled Chicken Plate",
      nameAr: "طبق دجاج مشوي",
      servings: 1,
      menuPrice: 18.0,
      targetFoodCostPct: 28.0,
      ingredients: [
        { itemId: "inv_1", itemName: "Chicken", quantity: 250.0, unit: "g" },
      ],
      posSku: "CHK-GRL-01",
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
