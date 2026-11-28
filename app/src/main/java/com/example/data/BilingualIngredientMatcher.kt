package com.example.data

import com.example.model.IngredientCategory
import com.example.model.InventoryItem
import com.example.model.UnitType
import java.util.Locale
import kotlin.math.min

data class CanonicalIngredientMatch(
    val canonicalKey: String,
    val nameEn: String,
    val nameAr: String,
    val category: IngredientCategory,
    val matchedExistingId: String? = null,
    val autoCorrectedFrom: String? = null
)

data class BasicIngredientSpec(
    val key: String,
    val nameEn: String,
    val nameAr: String,
    val category: IngredientCategory,
    val defaultUnit: UnitType,
    val defaultMinStock: Double,
    val synonyms: Set<String>
)

data class SpellSuggestion(
    val canonicalKey: String,
    val nameEn: String,
    val nameAr: String,
    val category: IngredientCategory,
    val defaultUnit: UnitType,
    val matchedInventoryId: String?,
    val editDistance: Int,
    val isExactMatch: Boolean,
    val isPrefixMatch: Boolean,
    val isHighConfidenceAutoCorrect: Boolean
)

/**
 * Unifies Arabic, English, Spanish, and French culinary ingredient names, provides a comprehensive
 * catalog of 115+ basic kitchen ingredients, and detects/corrects misspellings (e.g. "galric" -> "Garlic")
 * using Damerau-Levenshtein edit distance.
 */
object BilingualIngredientMatcher {

    val basicIngredients: List<BasicIngredientSpec> = listOf(
        // --- PROTEINS ---
        BasicIngredientSpec("chicken", "Chicken", "دجاج", IngredientCategory.PROTEIN, UnitType.KG, 2.0, setOf("chicken", "whole chicken", "poultry", "دجاج", "الدجاج", "فراخ", "فروج", "pollo", "poulet")),
        BasicIngredientSpec("chicken_breast", "Chicken Breast", "صدور دجاج", IngredientCategory.PROTEIN, UnitType.KG, 2.0, setOf("chicken breast", "chicken fillet", "صدور دجاج", "صدر دجاج", "فيليه دجاج", "pechuga de pollo", "blanc de poulet")),
        BasicIngredientSpec("chicken_thigh", "Chicken Thighs", "أفخاذ دجاج", IngredientCategory.PROTEIN, UnitType.KG, 2.0, setOf("chicken thigh", "chicken thighs", "drumstick", "أفخاذ دجاج", "فخذ دجاج", "وراك دجاج")),
        BasicIngredientSpec("beef", "Beef", "لحم بقر", IngredientCategory.PROTEIN, UnitType.KG, 2.0, setOf("beef", "steak", "ribeye", "tenderloin", "sirloin", "لحم", "لحم بقر", "لحم بقري", "ستيك", "carne", "res", "boeuf")),
        BasicIngredientSpec("ground_beef", "Ground Beef", "لحم مفروم", IngredientCategory.PROTEIN, UnitType.KG, 2.0, setOf("ground beef", "minced beef", "mince", "beef mince", "لحم مفروم", "مفروم", "carne molida", "boeuf hache")),
        BasicIngredientSpec("lamb", "Lamb", "لحم غنم", IngredientCategory.PROTEIN, UnitType.KG, 1.5, setOf("lamb", "mutton", "lamb chop", "لحم غنم", "خروف", "ضاني", "لحم ضاني", "cordero", "agneau")),
        BasicIngredientSpec("veal", "Veal", "لحم عجل", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("veal", "بتلو", "لحم عجل", "لحم بتلو", "ternera", "veau")),
        BasicIngredientSpec("turkey", "Turkey", "ديك رومي", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("turkey", "turkey breast", "ديك رومي", "رومي", "pavo", "dinde")),
        BasicIngredientSpec("duck", "Duck", "بط", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("duck", "duck breast", "بط", "صدر بط", "pato", "canard")),
        BasicIngredientSpec("salmon", "Salmon", "سلمون", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("salmon", "salmon fillet", "سلمون", "فيليه سلمون", "سمك سلمون", "salmón", "saumon")),
        BasicIngredientSpec("shrimp", "Shrimp", "روبيان", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("shrimp", "prawns", "prawn", "روبيان", "جمبري", "قريدس", "camarones", "crevettes")),
        BasicIngredientSpec("white_fish", "White Fish", "سمك فيليه", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("fish", "white fish", "sea bass", "cod", "tilapia", "hamour", "سمك", "فيليه سمك", "بلطي", "قاروص", "هامور", "pescado", "poisson")),
        BasicIngredientSpec("tuna", "Tuna", "تونة", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("tuna", "تونة", "تونا", "atun", "thon")),
        BasicIngredientSpec("calamari", "Calamari / Squid", "كاليماري", IngredientCategory.PROTEIN, UnitType.KG, 0.5, setOf("calamari", "squid", "كاليماري", "حبار", "سبيط", "calamar")),
        BasicIngredientSpec("sausage", "Sausage", "سجق", IngredientCategory.PROTEIN, UnitType.KG, 1.0, setOf("sausage", "sujuk", "hot dog", "سجق", "نقانق", "سوسيس", "salchicha", "saucisse")),
        BasicIngredientSpec("beef_bacon", "Beef Bacon", "بيكون بقري", IngredientCategory.PROTEIN, UnitType.KG, 0.5, setOf("bacon", "beef bacon", "smoked beef", "بيكون", "بيف بيكون", "tocino")),
        BasicIngredientSpec("tofu", "Tofu", "توفو", IngredientCategory.PROTEIN, UnitType.KG, 0.5, setOf("tofu", "توفو")),

        // --- PRODUCE, AROMATICS & FRESH HERBS ---
        BasicIngredientSpec("garlic", "Garlic", "ثوم", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("garlic", "garlic cloves", "ثوم", "الثوم", "توم", "ajo", "ail")),
        BasicIngredientSpec("onion", "Onions", "بصل", IngredientCategory.PRODUCE, UnitType.KG, 2.0, setOf("onion", "onions", "yellow onion", "white onion", "بصل", "البصل", "cebolla", "oignon")),
        BasicIngredientSpec("red_onion", "Red Onions", "بصل أحمر", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("red onion", "red onions", "بصل أحمر", "بصل احمر", "cebolla morada", "oignon rouge")),
        BasicIngredientSpec("green_onion", "Green Onions / Scallions", "بصل أخضر", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("green onion", "green onions", "scallion", "scallions", "spring onion", "بصل أخضر", "بصل اخضر")),
        BasicIngredientSpec("shallots", "Shallots", "كراث / شالوت", IngredientCategory.PRODUCE, UnitType.KG, 0.3, setOf("shallot", "shallots", "شالوت", "كراث", "echalote")),
        BasicIngredientSpec("tomato", "Tomatoes", "طماطم", IngredientCategory.PRODUCE, UnitType.KG, 2.0, setOf("tomato", "tomatoes", "طماطم", "بندورة", "الطماطم", "tomate", "tomates")),
        BasicIngredientSpec("cherry_tomato", "Cherry Tomatoes", "طماطم كرزية", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("cherry tomato", "cherry tomatoes", "طماطم كرزية", "بندورة كرزية")),
        BasicIngredientSpec("potato", "Potatoes", "بطاطس", IngredientCategory.PRODUCE, UnitType.KG, 3.0, setOf("potato", "potatoes", "بطاطس", "بطاطا", "patata", "papa", "pomme de terre")),
        BasicIngredientSpec("sweet_potato", "Sweet Potatoes", "بطاطا حلوة", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("sweet potato", "sweet potatoes", "بطاطا حلوة", "بطاطس حلوة")),
        BasicIngredientSpec("carrot", "Carrots", "جزر", IngredientCategory.PRODUCE, UnitType.KG, 1.5, setOf("carrot", "carrots", "جزر", "الجزر", "zanahoria", "carotte")),
        BasicIngredientSpec("celery", "Celery", "كرفس", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("celery", "كرفس", "apio", "celeri")),
        BasicIngredientSpec("cucumber", "Cucumber", "خيار", IngredientCategory.PRODUCE, UnitType.KG, 1.5, setOf("cucumber", "cucumbers", "خيار", "الخيار", "pepino", "concombre")),
        BasicIngredientSpec("lettuce", "Lettuce", "خس", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("lettuce", "romaine", "iceberg", "خس", "الخس", "كابوتشا", "lechuga", "laitue")),
        BasicIngredientSpec("spinach", "Spinach", "سبانخ", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("spinach", "baby spinach", "سبانخ", "espinaca", "epinard")),
        BasicIngredientSpec("arugula", "Arugula / Rocket", "جرجير", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("arugula", "rocket", "rocca", "جرجير", "rucula", "roquette")),
        BasicIngredientSpec("cabbage", "Cabbage", "ملفوف / كرنب", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("cabbage", "red cabbage", "white cabbage", "ملفوف", "كرنب", "repollo", "chou")),
        BasicIngredientSpec("broccoli", "Broccoli", "بروكلي", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("broccoli", "بروكلي", "brocoli")),
        BasicIngredientSpec("cauliflower", "Cauliflower", "قرنبيط", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("cauliflower", "قرنبيط", "زهرة", "coliflor", "chou-fleur")),
        BasicIngredientSpec("zucchini", "Zucchini", "كوسة", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("zucchini", "courgette", "كوسة", "كوسا", "calabacin")),
        BasicIngredientSpec("eggplant", "Eggplant", "باذنجان", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("eggplant", "aubergine", "باذنجان", "بتنجان", "berenjena")),
        BasicIngredientSpec("bell_pepper", "Bell Pepper", "فلفل رومي", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("bell pepper", "capsicum", "sweet pepper", "red pepper", "green pepper", "فلفل رومي", "فلفل ألوان", "فلفل حلو", "فليفلة", "pimiento", "poivron")),
        BasicIngredientSpec("chili_pepper", "Chili Pepper / Jalapeño", "فلفل حار", IngredientCategory.PRODUCE, UnitType.KG, 0.3, setOf("chili", "chilli", "chili pepper", "jalapeno", "hot pepper", "فلفل حار", "شطة خضراء", "هالبينو", "chile", "piment")),
        BasicIngredientSpec("mushroom", "Mushrooms", "فطر / مشروم", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("mushroom", "mushrooms", "button mushroom", "portobello", "فطر", "مشروم", "عيش الغراب", "champiñón", "champignon")),
        BasicIngredientSpec("corn", "Sweet Corn", "ذرة حلوة", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("corn", "sweet corn", "ذرة", "ذرة حلوة", "maiz", "mais")),
        BasicIngredientSpec("green_beans", "Green Beans", "فاصوليا خضراء", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("green beans", "french beans", "فاصوليا خضراء", "لوبيا")),
        BasicIngredientSpec("peas", "Green Peas", "بازلاء", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("peas", "green peas", "بازلاء", "بسلة", "guisantes", "petits pois")),
        BasicIngredientSpec("avocado", "Avocado", "أفوكادو", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("avocado", "avocados", "أفوكادو", "افوكادو", "aguacate", "avocat")),
        BasicIngredientSpec("lemon", "Lemons", "ليمون", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("lemon", "lemons", "ليمون", "الليمون", "limón", "citron")),
        BasicIngredientSpec("lime", "Limes", "ليمون أخضر", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("lime", "limes", "ليمون أخضر", "لايم", "lima", "citron vert")),
        BasicIngredientSpec("orange", "Oranges", "برتقال", IngredientCategory.PRODUCE, UnitType.KG, 1.0, setOf("orange", "oranges", "برتقال", "naranja")),
        BasicIngredientSpec("ginger", "Fresh Ginger", "زنجبيل", IngredientCategory.PRODUCE, UnitType.KG, 0.3, setOf("ginger", "fresh ginger", "زنجبيل", "جنزبيل", "jengibre", "gingembre")),
        BasicIngredientSpec("parsley", "Parsley", "بقدونس", IngredientCategory.PRODUCE, UnitType.KG, 0.3, setOf("parsley", "بقدونس", "perejil", "persil")),
        BasicIngredientSpec("cilantro", "Cilantro / Coriander", "كزبرة خضراء", IngredientCategory.PRODUCE, UnitType.KG, 0.3, setOf("cilantro", "coriander", "fresh coriander", "كزبرة", "كزبرة خضراء", "coriandre")),
        BasicIngredientSpec("mint", "Fresh Mint", "نعناع", IngredientCategory.PRODUCE, UnitType.KG, 0.2, setOf("mint", "fresh mint", "نعناع", "نعنع", "menta", "menthe")),
        BasicIngredientSpec("basil", "Fresh Basil", "ريحان", IngredientCategory.PRODUCE, UnitType.KG, 0.2, setOf("basil", "fresh basil", "ريحان", "حبق", "albahaca", "basilic")),
        BasicIngredientSpec("rosemary", "Rosemary", "إكليل الجبل", IngredientCategory.PRODUCE, UnitType.G, 100.0, setOf("rosemary", "إكليل الجبل", "روزماري", "حصى البان", "romero", "romarin")),
        BasicIngredientSpec("thyme", "Fresh Thyme", "زعتر طازج", IngredientCategory.PRODUCE, UnitType.G, 100.0, setOf("thyme", "fresh thyme", "زعتر طازج", "tomillo", "thym")),
        BasicIngredientSpec("dill", "Fresh Dill", "شبت", IngredientCategory.PRODUCE, UnitType.G, 100.0, setOf("dill", "شبت", "eneldo", "aneth")),
        BasicIngredientSpec("oregano", "Oregano", "أوريجانو", IngredientCategory.SPICES, UnitType.G, 100.0, setOf("oregano", "أوريجانو", "اوريجانو", "بردقوش", "origan")),
        BasicIngredientSpec("chives", "Chives", "ثوم معمر", IngredientCategory.PRODUCE, UnitType.G, 100.0, setOf("chives", "ثوم معمر", "ciboulette")),
        BasicIngredientSpec("olives", "Olives", "زيتون", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("olives", "black olives", "green olives", "زيتون", "زيتون أسود", "زيتون أخضر", "aceitunas")),
        BasicIngredientSpec("pickles", "Pickles", "مخلل خيار", IngredientCategory.PRODUCE, UnitType.KG, 0.5, setOf("pickles", "gherkins", "pickled cucumber", "مخلل", "مخلل خيار", "كبيس", "pepinillos", "cornichons")),

        // --- DAIRY & EGGS ---
        BasicIngredientSpec("eggs", "Eggs", "بيض", IngredientCategory.DAIRY, UnitType.PCS, 30.0, setOf("egg", "eggs", "بيض", "البيض", "huevos", "oeufs")),
        BasicIngredientSpec("milk", "Milk", "حليب", IngredientCategory.DAIRY, UnitType.L, 4.0, setOf("milk", "whole milk", "حليب", "لبن", "حليب طازج", "leche", "lait")),
        BasicIngredientSpec("butter", "Butter", "زبدة", IngredientCategory.DAIRY, UnitType.KG, 1.0, setOf("butter", "unsalted butter", "salted butter", "زبدة", "الزبدة", "mantequilla", "beurre")),
        BasicIngredientSpec("ghee", "Ghee / Clarified Butter", "سمن", IngredientCategory.DAIRY, UnitType.KG, 0.5, setOf("ghee", "clarified butter", "سمن", "سمنة", "سمن بلدي")),
        BasicIngredientSpec("heavy_cream", "Heavy Cream", "كريمة طبخ", IngredientCategory.DAIRY, UnitType.L, 2.0, setOf("cream", "heavy cream", "cooking cream", "whipping cream", "كريمة", "كريمة طبخ", "قشطة", "crema", "crème")),
        BasicIngredientSpec("sour_cream", "Sour Cream", "كريمة حامضة", IngredientCategory.DAIRY, UnitType.KG, 0.5, setOf("sour cream", "كريمة حامضة", "ساور كريم", "crema agria")),
        BasicIngredientSpec("cream_cheese", "Cream Cheese", "جبنة كريمي", IngredientCategory.DAIRY, UnitType.KG, 0.5, setOf("cream cheese", "philadelphia", "جبنة كريمي", "جبن كريمي", "queso crema")),
        BasicIngredientSpec("mozzarella", "Mozzarella Cheese", "جبن موزاريلا", IngredientCategory.DAIRY, UnitType.KG, 1.0, setOf("mozzarella", "cheese", "جبن", "جبنة", "موزاريلا", "جبن موزاريلا", "queso", "fromage")),
        BasicIngredientSpec("cheddar", "Cheddar Cheese", "جبن شيدر", IngredientCategory.DAIRY, UnitType.KG, 1.0, setOf("cheddar", "cheddar cheese", "شيدر", "جبن شيدر")),
        BasicIngredientSpec("parmesan", "Parmesan Cheese", "جبن بارميزان", IngredientCategory.DAIRY, UnitType.KG, 0.5, setOf("parmesan", "parmigiano", "بارميزان", "جبن بارميزان", "parmesano")),
        BasicIngredientSpec("feta", "Feta Cheese", "جبن فيتا", IngredientCategory.DAIRY, UnitType.KG, 0.5, setOf("feta", "feta cheese", "فيتا", "جبن فيتا", "جبن أبيض")),
        BasicIngredientSpec("halloumi", "Halloumi Cheese", "جبن حلوم", IngredientCategory.DAIRY, UnitType.KG, 0.5, setOf("halloumi", "حلوم", "جبن حلوم")),
        BasicIngredientSpec("yogurt", "Yogurt", "زبادي", IngredientCategory.DAIRY, UnitType.KG, 1.0, setOf("yogurt", "greek yogurt", "laban", "زبادي", "لبن رائب", "روب", "لبنة", "yogur", "yaourt")),

        // --- DRY GOODS, GRAINS, OILS, ACIDS & CONDIMENTS ---
        BasicIngredientSpec("rice", "Rice", "أرز", IngredientCategory.DRY_GOODS, UnitType.KG, 5.0, setOf("rice", "basmati", "basmati rice", "jasmine rice", "أرز", "رز", "أرز بسمتي", "arroz", "riz")),
        BasicIngredientSpec("flour", "All-Purpose Flour", "دقيق", IngredientCategory.DRY_GOODS, UnitType.KG, 5.0, setOf("flour", "all purpose flour", "wheat flour", "دقيق", "طحين", "harina", "farine")),
        BasicIngredientSpec("cornstarch", "Cornstarch", "نشا ذرة", IngredientCategory.DRY_GOODS, UnitType.KG, 0.5, setOf("cornstarch", "corn starch", "cornflour", "نشا", "نشا ذرة", "maicena", "fecule de mais")),
        BasicIngredientSpec("breadcrumbs", "Breadcrumbs / Panko", "بقسماط", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("breadcrumbs", "panko", "bread crumbs", "بقسماط", "كعك مطحون", "pan rallado", "chapelure")),
        BasicIngredientSpec("sugar", "White Sugar", "سكر", IngredientCategory.DRY_GOODS, UnitType.KG, 2.0, setOf("sugar", "white sugar", "granulated sugar", "سكر", "السكر", "azúcar", "sucre")),
        BasicIngredientSpec("brown_sugar", "Brown Sugar", "سكر بني", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("brown sugar", "سكر بني", "azucar moreno", "cassonade")),
        BasicIngredientSpec("honey", "Honey", "عسل", IngredientCategory.DRY_GOODS, UnitType.KG, 0.5, setOf("honey", "عسل", "عسل نحل", "miel")),
        BasicIngredientSpec("pasta", "Pasta", "مكرونة", IngredientCategory.DRY_GOODS, UnitType.KG, 3.0, setOf("pasta", "spaghetti", "penne", "fettuccine", "macaroni", "مكرونة", "باستا", "معكرونة", "سباغيتي", "pâtes")),
        BasicIngredientSpec("noodles", "Noodles", "نودلز", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("noodles", "egg noodles", "rice noodles", "نودلز", "شعيرية")),
        BasicIngredientSpec("bread", "Bread / Buns", "خبز", IngredientCategory.DRY_GOODS, UnitType.PCS, 20.0, setOf("bread", "pita", "bun", "buns", "burger bun", "brioche", "خبز", "عيش", "صمون", "pan", "pain")),
        BasicIngredientSpec("tortilla", "Tortilla / Flatbread", "خبز تورتيلا", IngredientCategory.DRY_GOODS, UnitType.PCS, 20.0, setOf("tortilla", "tortillas", "wrap", "flatbread", "تورتيلا", "خبز صاج", "خبز شامي")),
        BasicIngredientSpec("lentils", "Lentils", "عدس", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("lentils", "red lentils", "عدس", "lentejas", "lentilles")),
        BasicIngredientSpec("chickpeas", "Chickpeas", "حمص", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("chickpeas", "garbanzo", "حمص", "garbanzos", "pois chiches")),
        BasicIngredientSpec("tomato_paste", "Tomato Paste", "معجون طماطم", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("tomato paste", "tomato puree", "معجون طماطم", "صلصة طماطم", "رب البندورة", "pasta de tomate", "concentre de tomate")),
        BasicIngredientSpec("crushed_tomatoes", "Crushed Tomatoes", "طماطم مهروسة", IngredientCategory.DRY_GOODS, UnitType.KG, 2.0, setOf("crushed tomatoes", "canned tomatoes", "peeled tomatoes", "طماطم مقشرة", "طماطم معلبة")),
        BasicIngredientSpec("olive_oil", "Olive Oil", "زيت زيتون", IngredientCategory.DRY_GOODS, UnitType.L, 2.0, setOf("olive oil", "extra virgin olive oil", "evoo", "زيت زيتون", "aceite de oliva", "huile d'olive")),
        BasicIngredientSpec("cooking_oil", "Vegetable Oil", "زيت طبخ", IngredientCategory.DRY_GOODS, UnitType.L, 4.0, setOf("oil", "vegetable oil", "sunflower oil", "canola oil", "frying oil", "corn oil", "زيت", "زيت طبخ", "زيت نباتي", "زيت ذرة", "زيت قلى", "aceite", "huile")),
        BasicIngredientSpec("sesame_oil", "Sesame Oil", "زيت سمسم", IngredientCategory.DRY_GOODS, UnitType.L, 0.5, setOf("sesame oil", "زيت سمسم")),
        BasicIngredientSpec("vinegar", "White Vinegar", "خل أبيض", IngredientCategory.DRY_GOODS, UnitType.L, 1.0, setOf("vinegar", "white vinegar", "خل", "خل أبيض", "vinagre", "vinaigre")),
        BasicIngredientSpec("balsamic_vinegar", "Balsamic Vinegar", "خل بلسميك", IngredientCategory.DRY_GOODS, UnitType.L, 0.5, setOf("balsamic", "balsamic vinegar", "خل بلسميك", "بلسميك")),
        BasicIngredientSpec("soy_sauce", "Soy Sauce", "صوص صويا", IngredientCategory.DRY_GOODS, UnitType.L, 1.0, setOf("soy sauce", "soya sauce", "صوص صويا", "صويا صوص", "salsa de soja", "sauce soja")),
        BasicIngredientSpec("worcestershire", "Worcestershire Sauce", "صوص وسترشير", IngredientCategory.DRY_GOODS, UnitType.L, 0.5, setOf("worcestershire", "worcestershire sauce", "وسترشير")),
        BasicIngredientSpec("oyster_sauce", "Oyster Sauce", "صوص المحار", IngredientCategory.DRY_GOODS, UnitType.L, 0.5, setOf("oyster sauce", "صوص المحار", "صلصة المحار")),
        BasicIngredientSpec("hot_sauce", "Hot Sauce / Sriracha", "صوص حار", IngredientCategory.DRY_GOODS, UnitType.L, 0.5, setOf("hot sauce", "sriracha", "tabasco", "صوص حار", "شطة", "سيراتشا")),
        BasicIngredientSpec("ketchup", "Ketchup", "كاتشب", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("ketchup", "tomato ketchup", "كاتشب", "كتشب")),
        BasicIngredientSpec("mayonnaise", "Mayonnaise", "مايونيز", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("mayonnaise", "mayo", "مايونيز", "mayonesa")),
        BasicIngredientSpec("mustard", "Mustard", "خردل / مستردة", IngredientCategory.DRY_GOODS, UnitType.KG, 0.5, setOf("mustard", "dijon mustard", "خردل", "مستردة", "mostaza", "moutarde")),
        BasicIngredientSpec("tahini", "Tahini", "طحينة", IngredientCategory.DRY_GOODS, UnitType.KG, 1.0, setOf("tahini", "tahina", "sesame paste", "طحينة", "طحينية", "راشي")),
        BasicIngredientSpec("pomegranate_molasses", "Pomegranate Molasses", "دبس رمان", IngredientCategory.DRY_GOODS, UnitType.L, 0.5, setOf("pomegranate molasses", "دبس رمان")),
        BasicIngredientSpec("coconut_milk", "Coconut Milk", "حليب جوز الهند", IngredientCategory.DRY_GOODS, UnitType.L, 1.0, setOf("coconut milk", "حليب جوز الهند")),
        BasicIngredientSpec("baking_powder", "Baking Powder", "بيكنج باودر", IngredientCategory.DRY_GOODS, UnitType.G, 200.0, setOf("baking powder", "بيكنج باودر", "خميرة حلويات")),
        BasicIngredientSpec("baking_soda", "Baking Soda", "بيكربونات الصوديوم", IngredientCategory.DRY_GOODS, UnitType.G, 200.0, setOf("baking soda", "bicarbonate", "بيكربونات", "كربوناتو")),
        BasicIngredientSpec("yeast", "Dry Yeast", "خميرة فورية", IngredientCategory.DRY_GOODS, UnitType.G, 200.0, setOf("yeast", "dry yeast", "instant yeast", "خميرة", "خميرة فورية", "levadura", "levure")),
        BasicIngredientSpec("chicken_stock", "Chicken Stock / Bouillon", "مرق دجاج", IngredientCategory.DRY_GOODS, UnitType.L, 2.0, setOf("chicken stock", "chicken broth", "bouillon", "مرق دجاج", "مرقة دجاج", "ماجي")),
        BasicIngredientSpec("beef_stock", "Beef Stock / Broth", "مرق لحم", IngredientCategory.DRY_GOODS, UnitType.L, 2.0, setOf("beef stock", "beef broth", "مرق لحم", "مرقة لحم")),
        BasicIngredientSpec("almonds", "Almonds", "لوز", IngredientCategory.DRY_GOODS, UnitType.KG, 0.5, setOf("almonds", "almond", "لوز", "almendras", "amandes")),
        BasicIngredientSpec("pine_nuts", "Pine Nuts", "صنوبر", IngredientCategory.DRY_GOODS, UnitType.G, 200.0, setOf("pine nuts", "صنوبر", "pinones", "pignons")),
        BasicIngredientSpec("sesame_seeds", "Sesame Seeds", "سمسم", IngredientCategory.DRY_GOODS, UnitType.G, 250.0, setOf("sesame", "sesame seeds", "سمسم", "ajonjoli")),

        // --- SPICES & SEASONINGS ---
        BasicIngredientSpec("salt", "Salt", "ملح", IngredientCategory.SPICES, UnitType.KG, 1.0, setOf("salt", "sea salt", "kosher salt", "table salt", "ملح", "الملح", "sal", "sel")),
        BasicIngredientSpec("black_pepper", "Black Pepper", "فلفل أسود", IngredientCategory.SPICES, UnitType.G, 250.0, setOf("black pepper", "pepper", "peppercorn", "فلفل أسود", "فلفل اسود", "pimienta negra", "poivre noir")),
        BasicIngredientSpec("white_pepper", "White Pepper", "فلفل أبيض", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("white pepper", "فلفل أبيض", "فلفل ابيض")),
        BasicIngredientSpec("cumin", "Cumin", "كمون", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("cumin", "ground cumin", "كمون", "comino")),
        BasicIngredientSpec("coriander_powder", "Coriander Powder", "كزبرة ناشفة", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("coriander powder", "ground coriander", "dry coriander", "كزبرة ناشفة", "كزبرة مطحونة")),
        BasicIngredientSpec("paprika", "Paprika", "بابريكا", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("paprika", "smoked paprika", "sweet paprika", "بابريكا", "بابريكا مدخنة", "pimentón")),
        BasicIngredientSpec("cayenne", "Cayenne / Chili Powder", "شطة بودرة", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("cayenne", "cayenne pepper", "chili powder", "red chili", "شطة", "فلفل أحمر حار")),
        BasicIngredientSpec("chili_flakes", "Red Chili Flakes", "فلفل مجروش", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("chili flakes", "crushed red pepper", "فلفل مجروش", "شطة مجروشة")),
        BasicIngredientSpec("turmeric", "Turmeric", "كركم", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("turmeric", "كركم", "curcuma")),
        BasicIngredientSpec("cinnamon", "Cinnamon", "قرفة", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("cinnamon", "قرفة", "دارسين", "canela", "cannelle")),
        BasicIngredientSpec("cardamom", "Cardamom", "هيل / حبهان", IngredientCategory.SPICES, UnitType.G, 100.0, setOf("cardamom", "هيل", "حبهان", "cardamomo", "cardamome")),
        BasicIngredientSpec("cloves", "Cloves", "قرنفل", IngredientCategory.SPICES, UnitType.G, 100.0, setOf("cloves", "clove", "قرنفل", "مسمار", "clavo", "clou de girofle")),
        BasicIngredientSpec("nutmeg", "Nutmeg", "جوزة الطيب", IngredientCategory.SPICES, UnitType.G, 100.0, setOf("nutmeg", "جوزة الطيب", "nuez moscada", "muscade")),
        BasicIngredientSpec("bay_leaves", "Bay Leaves", "ورق غار / لورا", IngredientCategory.SPICES, UnitType.G, 50.0, setOf("bay leaf", "bay leaves", "ورق غار", "ورق لورا", "laurel", "laurier")),
        BasicIngredientSpec("sumac", "Sumac", "سماق", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("sumac", "سماق")),
        BasicIngredientSpec("zaatar", "Za'atar", "زعتر", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("zaatar", "za'atar", "zatar", "زعتر")),
        BasicIngredientSpec("curry_powder", "Curry Powder", "كاري", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("curry", "curry powder", "كاري", "بهارات كاري")),
        BasicIngredientSpec("garam_masala", "Garam Masala", "جارام ماسالا", IngredientCategory.SPICES, UnitType.G, 150.0, setOf("garam masala", "جارام ماسالا", "ماسالا")),
        BasicIngredientSpec("seven_spice", "Seven Spice / Baharat", "سبع بهارات", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("seven spice", "7 spice", "baharat", "allspice", "سبع بهارات", "بهارات مشكلة", "بهار حلو")),
        BasicIngredientSpec("garlic_powder", "Garlic Powder", "ثوم بودرة", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("garlic powder", "ثوم بودرة", "بودرة ثوم")),
        BasicIngredientSpec("onion_powder", "Onion Powder", "بصل بودرة", IngredientCategory.SPICES, UnitType.G, 200.0, setOf("onion powder", "بصل بودرة", "بودرة بصل")),
        BasicIngredientSpec("saffron", "Saffron", "زعفران", IngredientCategory.SPICES, UnitType.G, 10.0, setOf("saffron", "زعفران", "azafran", "safran")),

        // --- BEVERAGES ---
        BasicIngredientSpec("coffee", "Coffee Beans", "قهوة", IngredientCategory.BEVERAGES, UnitType.KG, 1.0, setOf("coffee", "espresso", "coffee beans", "قهوة", "بن", "café")),
        BasicIngredientSpec("tea", "Tea", "شاي", IngredientCategory.BEVERAGES, UnitType.KG, 0.5, setOf("tea", "black tea", "green tea", "شاي", "té", "thé")),
        BasicIngredientSpec("orange_juice", "Orange Juice", "عصير برتقال", IngredientCategory.BEVERAGES, UnitType.L, 2.0, setOf("orange juice", "عصير برتقال")),
        BasicIngredientSpec("sparkling_water", "Sparkling Water", "مياه غازية", IngredientCategory.BEVERAGES, UnitType.L, 2.0, setOf("sparkling water", "soda water", "club soda", "مياه غازية", "صودا"))
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
     * Optimal String Alignment / Damerau-Levenshtein edit distance.
     * Treats adjacent character transpositions (e.g. "galric" -> "garlic") as a single edit (distance = 1).
     */
    fun damerauLevenshtein(source: String, target: String): Int {
        if (source == target) return 0
        val n = source.length
        val m = target.length
        if (n == 0) return m
        if (m == 0) return n

        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (source[i - 1] == target[j - 1]) 0 else 1
                var best = min(
                    min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                    d[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && source[i - 1] == target[j - 2] && source[i - 2] == target[j - 1]) {
                    best = min(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        return d[n][m]
    }

    private fun maxAllowedEditDistance(len: Int): Int = when {
        len <= 2 -> 0
        len <= 4 -> 1
        len <= 8 -> 2
        else -> 3
    }

    /**
     * Searches both the kitchen's live inventory and the 115+ basic culinary ingredients catalog
     * to provide autocomplete matches and "Did you mean...?" typo suggestions (e.g. "galric" -> "Garlic").
     */
    fun suggestCorrections(
        rawInput: String,
        existingInventory: List<InventoryItem>,
        maxResults: Int = 5
    ): List<SpellSuggestion> {
        val normInput = normalize(rawInput)
        if (normInput.length < 2) return emptyList()

        val maxDist = maxAllowedEditDistance(normInput.length)
        val byKey = LinkedHashMap<String, SpellSuggestion>()

        fun considerCandidate(
            canonicalKey: String,
            nameEn: String,
            nameAr: String,
            category: IngredientCategory,
            defaultUnit: UnitType,
            matchedInvId: String?,
            candidateStrings: Collection<String>
        ) {
            var bestDist = Int.MAX_VALUE
            var isExact = false
            var isPrefix = false

            for (cand in candidateStrings) {
                val normCand = normalize(cand)
                if (normCand.isEmpty()) continue
                if (normCand == normInput) {
                    bestDist = 0
                    isExact = true
                    break
                }
                if (normCand.startsWith(normInput) || normCand.split(" ").any { it.startsWith(normInput) }) {
                    isPrefix = true
                    bestDist = min(bestDist, 0)
                }
                val dist = damerauLevenshtein(normInput, normCand)
                if (dist <= maxDist && dist < bestDist) {
                    bestDist = dist
                }
                // Also compare single-word tokens for multi-word ingredients like "chicken breast"
                if (normCand.contains(' ') && !normInput.contains(' ')) {
                    for (token in normCand.split(' ')) {
                        if (token.length >= 3) {
                            val tokenDist = damerauLevenshtein(normInput, token)
                            if (tokenDist <= maxDist && tokenDist + 1 < bestDist) {
                                bestDist = tokenDist + 1
                            }
                        }
                    }
                }
            }

            if (!isExact && !isPrefix && bestDist > maxDist) return

            val highConfidence = !isExact && !isPrefix &&
                ((bestDist == 1 && normInput.length >= 4) || (bestDist == 2 && normInput.length >= 7))

            val suggestion = SpellSuggestion(
                canonicalKey = canonicalKey,
                nameEn = nameEn,
                nameAr = nameAr,
                category = category,
                defaultUnit = defaultUnit,
                matchedInventoryId = matchedInvId,
                editDistance = if (isExact) 0 else bestDist,
                isExactMatch = isExact,
                isPrefixMatch = isPrefix && !isExact,
                isHighConfidenceAutoCorrect = highConfidence
            )

            val existing = byKey[canonicalKey]
            if (existing == null || suggestion.editDistance < existing.editDistance || (suggestion.isExactMatch && !existing.isExactMatch)) {
                byKey[canonicalKey] = suggestion
            }
        }

        // 1. Existing inventory items first
        for (item in existingInventory) {
            val candidates = buildList {
                add(item.nameEn)
                if (item.nameAr.isNotBlank()) add(item.nameAr)
                addAll(item.aliases)
            }
            considerCandidate(
                canonicalKey = item.canonicalKey,
                nameEn = item.nameEn,
                nameAr = item.nameAr,
                category = item.categoryEnum,
                defaultUnit = item.unitEnum,
                matchedInvId = item.id,
                candidateStrings = candidates
            )
        }

        // 2. Built-in basic ingredients catalog
        for (spec in basicIngredients) {
            val matchedInv = existingInventory.firstOrNull {
                normalize(it.canonicalKey) == normalize(spec.key) ||
                    normalize(it.nameEn) == normalize(spec.nameEn)
            }
            val candidates = buildList {
                add(spec.nameEn)
                add(spec.nameAr)
                addAll(spec.synonyms)
            }
            considerCandidate(
                canonicalKey = matchedInv?.canonicalKey ?: spec.key,
                nameEn = matchedInv?.nameEn ?: spec.nameEn,
                nameAr = matchedInv?.nameAr?.ifBlank { spec.nameAr } ?: spec.nameAr,
                category = matchedInv?.categoryEnum ?: spec.category,
                defaultUnit = matchedInv?.unitEnum ?: spec.defaultUnit,
                matchedInvId = matchedInv?.id,
                candidateStrings = candidates
            )
        }

        return byKey.values
            .sortedWith(
                compareByDescending<SpellSuggestion> { it.isExactMatch }
                    .thenByDescending { it.isPrefixMatch }
                    .thenBy { it.editDistance }
                    .thenByDescending { it.matchedInventoryId != null }
                    .thenBy { it.nameEn.length }
            )
            .take(maxResults)
    }

    /**
     * Returns a top spelling suggestion when the user's input is NOT already an exact match
     * (e.g. "galric" -> Garlic, "chiken" -> Chicken, "tomtao" -> Tomatoes).
     */
    fun bestTypoSuggestion(
        rawInput: String,
        existingInventory: List<InventoryItem>
    ): SpellSuggestion? {
        val normInput = normalize(rawInput)
        if (normInput.length < 3) return null
        val suggestions = suggestCorrections(rawInput, existingInventory, maxResults = 3)
        if (suggestions.any { it.isExactMatch }) return null
        return suggestions.firstOrNull { !it.isPrefixMatch && it.editDistance in 1..maxAllowedEditDistance(normInput.length) }
    }

    /**
     * Resolves an input label (English, Arabic, Spanish, French, or misspelled) against existing
     * inventory first, then against the built-in culinary dictionary (including high-confidence typo correction).
     */
    fun resolve(
        rawName: String,
        existingInventory: List<InventoryItem>,
        fallbackArabic: String = "",
        fallbackCategory: IngredientCategory = IngredientCategory.OTHER,
        allowAutoCorrectTypos: Boolean = true
    ): CanonicalIngredientMatch {
        val normInput = normalize(rawName)
        val normFallbackAr = normalize(fallbackArabic)

        // 1. Check existing inventory items by exact match on canonicalKey, nameEn, nameAr, or aliases
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

        // 2. Check built-in bilingual culinary dictionary (exact or clear containment)
        val dictEntry = basicIngredients.firstOrNull { entry ->
            val normalizedSynonyms = entry.synonyms.map { normalize(it) }
            normalizedSynonyms.any { syn ->
                syn == normInput ||
                    (normFallbackAr.isNotEmpty() && syn == normFallbackAr) ||
                    (normInput.length >= 4 && syn.length >= 4 && normInput.contains(syn))
            }
        }

        if (dictEntry != null) {
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

        // 3. High-confidence automatic typo correction (e.g. "galric" -> "Garlic", "chiken" -> "Chicken")
        if (allowAutoCorrectTypos && normInput.length >= 4) {
            val typoMatch = bestTypoSuggestion(rawName, existingInventory)
            if (typoMatch != null && typoMatch.isHighConfidenceAutoCorrect) {
                return CanonicalIngredientMatch(
                    canonicalKey = typoMatch.canonicalKey,
                    nameEn = typoMatch.nameEn,
                    nameAr = typoMatch.nameAr.ifBlank { fallbackArabic },
                    category = typoMatch.category,
                    matchedExistingId = typoMatch.matchedInventoryId,
                    autoCorrectedFrom = rawName.trim()
                )
            }
        }

        // 4. Fallback: generate clean canonical key from rawName
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
