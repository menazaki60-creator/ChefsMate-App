package com.example.data

import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import java.util.Locale

data class CanonicalIngredientMatch(
    val canonicalKey: String,
    val nameEn: String,
    val nameAr: String,
    val category: IngredientCategory,
    val matchedExistingId: String? = null
)

/**
 * Unifies Arabic, English, Spanish, and French culinary ingredient names so labels like
 * "chicken", "دجاج", "صدور دجاج", "pollo", and "poulet" seamlessly merge into one inventory record.
 */
object BilingualIngredientMatcher {

    private data class KnownEntry(
        val key: String,
        val nameEn: String,
        val nameAr: String,
        val category: IngredientCategory,
        val synonyms: Set<String>
    )

    private val dictionary: List<KnownEntry> = listOf(
        KnownEntry("chicken", "Chicken", "دجاج", IngredientCategory.PROTEIN, setOf("chicken", "chicken breast", "chicken thigh", "whole chicken", "poultry", "دجاج", "الدجاج", "صدور دجاج", "فراخ", "فروج", "pollo", "poulet")),
        KnownEntry("beef", "Beef", "لحم بقر", IngredientCategory.PROTEIN, setOf("beef", "ground beef", "minced beef", "steak", "ribeye", "tenderloin", "لحم", "لحم بقر", "لحم بقري", "لحم مفروم", "ستيك", "carne", "res", "boeuf")),
        KnownEntry("lamb", "Lamb", "لحم غنم", IngredientCategory.PROTEIN, setOf("lamb", "mutton", "lamb chop", "لحم غنم", "خروف", "ضاني", "لحم ضاني", "cordero", "agneau")),
        KnownEntry("salmon", "Salmon", "سلمون", IngredientCategory.PROTEIN, setOf("salmon", "salmon fillet", "سلمون", "فيليه سلمون", "سمك سلمون", "salmón", "saumon")),
        KnownEntry("shrimp", "Shrimp", "روبيان", IngredientCategory.PROTEIN, setOf("shrimp", "prawns", "روبيان", "جمبري", "قريدس", "camarones", "crevettes")),
        KnownEntry("fish", "White Fish", "سمك", IngredientCategory.PROTEIN, setOf("fish", "sea bass", "cod", "tilapia", "سمك", "فيليه سمك", "بلطي", "قاروص", "pescado", "poisson")),
        KnownEntry("eggs", "Eggs", "بيض", IngredientCategory.DAIRY, setOf("egg", "eggs", "بيض", "البيض", "huevos", "oeufs")),
        KnownEntry("milk", "Milk", "حليب", IngredientCategory.DAIRY, setOf("milk", "whole milk", "حليب", "لبن", "حليب طازج", "leche", "lait")),
        KnownEntry("butter", "Butter", "زبدة", IngredientCategory.DAIRY, setOf("butter", "unsalted butter", "زبدة", "الزبدة", "mantequilla", "beurre")),
        KnownEntry("cream", "Heavy Cream", "كريمة طبخ", IngredientCategory.DAIRY, setOf("cream", "heavy cream", "cooking cream", "كريمة", "كريمة طبخ", "قشطة", "crema", "crème")),
        KnownEntry("cheese", "Cheese", "جبن", IngredientCategory.DAIRY, setOf("cheese", "mozzarella", "cheddar", "parmesan", "جبن", "جبنة", "موزاريلا", "شيدر", "بارميزان", "queso", "fromage")),
        KnownEntry("yogurt", "Yogurt", "زبادي", IngredientCategory.DAIRY, setOf("yogurt", "greek yogurt", "زبادي", "لبن رائب", "روب", "yogur", "yaourt")),
        KnownEntry("tomato", "Tomatoes", "طماطم", IngredientCategory.PRODUCE, setOf("tomato", "tomatoes", "cherry tomato", "طماطم", "بندورة", "الطماطم", "tomate", "tomates")),
        KnownEntry("onion", "Onions", "بصل", IngredientCategory.PRODUCE, setOf("onion", "onions", "red onion", "white onion", "بصل", "البصل", "cebolla", "oignon")),
        KnownEntry("garlic", "Garlic", "ثوم", IngredientCategory.PRODUCE, setOf("garlic", "ثوم", "الثوم", "ajo", "ail")),
        KnownEntry("potato", "Potatoes", "بطاطس", IngredientCategory.PRODUCE, setOf("potato", "potatoes", "بطاطس", "بطاطا", "patata", "papa", "pomme de terre")),
        KnownEntry("lettuce", "Lettuce", "خس", IngredientCategory.PRODUCE, setOf("lettuce", "romaine", "iceberg", "خس", "الخس", "lechuga", "laitue")),
        KnownEntry("cucumber", "Cucumber", "خيار", IngredientCategory.PRODUCE, setOf("cucumber", "cucumbers", "خيار", "الخيار", "pepino", "concombre")),
        KnownEntry("lemon", "Lemons", "ليمون", IngredientCategory.PRODUCE, setOf("lemon", "lemons", "lime", "ليمون", "الليمون", "limón", "citron")),
        KnownEntry("bell_pepper", "Bell Pepper", "فلفل رومي", IngredientCategory.PRODUCE, setOf("bell pepper", "capsicum", "sweet pepper", "فلفل رومي", "فلفل حلو", "فليفلة", "pimiento", "poivron")),
        KnownEntry("carrot", "Carrots", "جزر", IngredientCategory.PRODUCE, setOf("carrot", "carrots", "جزر", "الجزر", "zanahoria", "carotte")),
        KnownEntry("mushroom", "Mushrooms", "فطر", IngredientCategory.PRODUCE, setOf("mushroom", "mushrooms", "فطر", "مشروم", "عيش الغراب", "champiñón", "champignon")),
        KnownEntry("parsley", "Parsley", "بقدونس", IngredientCategory.PRODUCE, setOf("parsley", "بقدونس", "perejil", "persil")),
        KnownEntry("cilantro", "Cilantro", "كزبرة", IngredientCategory.PRODUCE, setOf("cilantro", "coriander", "كزبرة", "cilantro", "coriandre")),
        KnownEntry("rice", "Rice", "أرز", IngredientCategory.DRY_GOODS, setOf("rice", "basmati", "basmati rice", "jasmine rice", "أرز", "رز", "أرز بسمتي", "arroz", "riz")),
        KnownEntry("flour", "Flour", "دقيق", IngredientCategory.DRY_GOODS, setOf("flour", "all purpose flour", "wheat flour", "دقيق", "طحين", "harina", "farine")),
        KnownEntry("sugar", "Sugar", "سكر", IngredientCategory.DRY_GOODS, setOf("sugar", "white sugar", "brown sugar", "سكر", "السكر", "azúcar", "sucre")),
        KnownEntry("pasta", "Pasta", "مكرونة", IngredientCategory.DRY_GOODS, setOf("pasta", "spaghetti", "penne", "مكرونة", "باستا", "معكرونة", "pâtes")),
        KnownEntry("bread", "Bread", "خبز", IngredientCategory.DRY_GOODS, setOf("bread", "pita", "bun", "buns", "خبز", "عيش", "pan", "pain")),
        KnownEntry("olive_oil", "Olive Oil", "زيت زيتون", IngredientCategory.DRY_GOODS, setOf("olive oil", "extra virgin olive oil", "evoo", "زيت زيتون", "aceite de oliva", "huile d'olive")),
        KnownEntry("cooking_oil", "Vegetable Oil", "زيت طبخ", IngredientCategory.DRY_GOODS, setOf("oil", "vegetable oil", "sunflower oil", "frying oil", "زيت", "زيت طبخ", "زيت نباتي", "زيت ذرة", "aceite", "huile")),
        KnownEntry("salt", "Salt", "ملح", IngredientCategory.SPICES, setOf("salt", "sea salt", "kosher salt", "ملح", "الملح", "sal", "sel")),
        KnownEntry("black_pepper", "Black Pepper", "فلفل أسود", IngredientCategory.SPICES, setOf("black pepper", "pepper", "peppercorn", "فلفل أسود", "فلفل اسود", "pimienta negra", "poivre noir")),
        KnownEntry("cumin", "Cumin", "كمون", IngredientCategory.SPICES, setOf("cumin", "كمون", "comino", "cumin")),
        KnownEntry("paprika", "Paprika", "بابريكا", IngredientCategory.SPICES, setOf("paprika", "بابريكا", "pimentón")),
        KnownEntry("cardamom", "Cardamom", "هيل", IngredientCategory.SPICES, setOf("cardamom", "هيل", "حبهان", "cardamomo", "cardamome")),
        KnownEntry("coffee", "Coffee Beans", "قهوة", IngredientCategory.BEVERAGES, setOf("coffee", "espresso", "coffee beans", "قهوة", "بن", "café")),
        KnownEntry("tea", "Tea", "شاي", IngredientCategory.BEVERAGES, setOf("tea", "black tea", "green tea", "شاي", "té", "thé"))
    )

    /** Normalizes Arabic letter variants/diacritics and Latin punctuation for cross-language matching. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "") // Strip Arabic tashkeel diacritics
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (trimmed.startsWith("ال") && trimmed.length > 3) {
            trimmed.removePrefix("ال")
        } else {
            trimmed
        }
    }

    /**
     * Resolves an input label (English, Arabic, Spanish, French, or mixed) against existing inventory first,
     * then against the built-in culinary multilingual dictionary.
     */
    fun resolve(
        rawName: String,
        existingInventory: List<InventoryItem>,
        fallbackArabic: String = "",
        fallbackCategory: IngredientCategory = IngredientCategory.OTHER
    ): CanonicalIngredientMatch {
        val normInput = normalize(rawName)
        val normFallbackAr = normalize(fallbackArabic)

        // 1. Check existing inventory items by canonicalKey, nameEn, nameAr, or aliases
        val matchedItem = existingInventory.firstOrNull { item ->
            val candidates = buildList {
                add(item.canonicalKey)
                add(item.nameEn)
                add(item.nameAr)
                addAll(item.aliases)
            }.map { normalize(it) }.filter { it.isNotEmpty() }

            normInput.isNotEmpty() && (
                candidates.contains(normInput) ||
                    (normFallbackAr.isNotEmpty() && candidates.contains(normFallbackAr))
                )
        }

        if (matchedItem != null) {
            return CanonicalIngredientMatch(
                canonicalKey = matchedItem.canonicalKey,
                nameEn = matchedItem.nameEn,
                nameAr = matchedItem.nameAr.ifBlank { fallbackArabic },
                category = matchedItem.categoryEnum,
                matchedExistingId = matchedItem.id
            )
        }

        // 2. Check built-in bilingual culinary dictionary
        val dictEntry = dictionary.firstOrNull { entry ->
            val normalizedSynonyms = entry.synonyms.map { normalize(it) }
            normalizedSynonyms.any { syn ->
                syn == normInput ||
                    (normFallbackAr.isNotEmpty() && syn == normFallbackAr) ||
                    (normInput.length >= 3 && (normInput.contains(syn) && syn.length >= 3))
            }
        }

        if (dictEntry != null) {
            // See if an existing inventory item already uses this canonical key!
            val existingByKey = existingInventory.firstOrNull {
                normalize(it.canonicalKey) == normalize(dictEntry.key) ||
                    normalize(it.nameEn) == normalize(dictEntry.nameEn) ||
                    (it.nameAr.isNotBlank() && normalize(it.nameAr) == normalize(dictEntry.nameAr))
            }
            return CanonicalIngredientMatch(
                canonicalKey = existingByKey?.canonicalKey ?: dictEntry.key,
                nameEn = existingByKey?.nameEn ?: dictEntry.nameEn,
                nameAr = existingByKey?.nameAr?.ifBlank { dictEntry.nameAr } ?: dictEntry.nameAr,
                category = existingByKey?.categoryEnum ?: dictEntry.category,
                matchedExistingId = existingByKey?.id
            )
        }

        // 3. Fallback: generate clean canonical key from rawName
        val isArabicInput = rawName.any { it in '\u0600'..'\u06FF' }
        val cleanEn = if (isArabicInput) rawName.trim() else rawName.trim().replaceFirstChar { it.uppercase() }
        val cleanAr = if (isArabicInput) rawName.trim() else fallbackArabic.trim()
        val generatedKey = normInput.replace(" ", "_").ifBlank { "item_${System.currentTimeMillis()}" }.take(80)

        return CanonicalIngredientMatch(
            canonicalKey = generatedKey,
            nameEn = cleanEn,
            nameAr = cleanAr,
            category = fallbackCategory,
            matchedExistingId = null
        )
    }
}
