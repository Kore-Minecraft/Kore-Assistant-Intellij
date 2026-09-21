package io.github.ayfri.kore.koreassistant.index

import com.intellij.icons.AllIcons
import io.github.ayfri.kore.koreassistant.KoreIcons
import javax.swing.Icon

const val FUNCTION_RESOURCE_FOLDER = "function"
const val TAGS_RESOURCE_FOLDER_PREFIX = "tags/"

/**
 * A `dataPack.<builderName> { }` block (or its `dataPack.<receiverName>.` property) whose lambda receiver exposes
 * the scoped builders, e.g. `recipes { craftingShaped("x") { } }` or `recipesBuilder.craftingShaped("x") { }`.
 */
enum class KoreScope(val builderName: String, val receiverName: String) {
	CONFIGURED_CARVERS("configuredCarvers", "configuredCarversBuilder"),
	CONFIGURED_FEATURES("configuredFeatures", "configuredFeaturesBuilder"),
	DENSITY_FUNCTIONS("densityFunctions", "densityFunctionsBuilder"),
	DIALOGS("dialogs", "dialogBuilder"),
	ENCHANTMENT_PROVIDERS("enchantmentProviders", "enchantmentProvidersBuilder"),
	RECIPES("recipes", "recipesBuilder"),
	STRUCTURES("structures", "structuresBuilder"),
	TEST_ENVIRONMENTS("testEnvironments", "testEnvironmentsBuilder"),
	;

	companion object {
		private val byBuilderName = entries.associateBy(KoreScope::builderName)
		private val byReceiverName = entries.associateBy(KoreScope::receiverName)

		fun byBuilderName(name: String): KoreScope? = byBuilderName[name]

		fun byReceiverName(name: String): KoreScope? = byReceiverName[name]
	}
}

/**
 * One row per Kore DSL builder that declares a datapack resource, keyed by the builder's short name
 * (the callee identifier the indexer sees, e.g. `function` in `function("x") { }`). Purely syntactic -
 * matching a name here does not mean the call actually resolves to Kore, that check happens at query time.
 *
 * [resourceFolder] mirrors the `Generator(resourceFolder)` constructor argument in Kore, i.e. the folder
 * under `data/<namespace>/` the resource is written to. It is `null` for [DATA_PACK], which is not a
 * resource but the container every other declaration is grouped under. Sourced from
 * `kore/src/commonMain/kotlin/io/github/ayfri/kore/DataPack.kt` (`registerGenerator<T>()` properties) and
 * the `fun DataPack.xxx(` / `fun <Scope>.xxx(` builders in each feature package; regenerate on every Kore
 * MC-version bump. Last synced with Kore 2.14.0-26.2.
 *
 * [scope] is set for builders only reachable inside a `recipes { }`-style block, which is how `noise` or
 * `function` can name two different builders: the enclosing scopes decide which one a call is.
 *
 * Tags override `getPathFromDataDir` to nest under `tags/<type>/<file>.json`, modeled here as a `tags/<type>`
 * folder, one kind per typed `xxxTag` builder. The untyped `tag(fileName, type)` / `addToTag` are skipped.
 */
enum class KoreDeclarationKind(val builderName: String, val resourceFolder: String?, val scope: KoreScope? = null) {
	ABS_DENSITY_FUNCTION("abs", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	ADD_DENSITY_FUNCTION("add", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	ADVANCEMENT("advancement", "advancement"),
	ALL_OF_TEST_ENVIRONMENT("allOf", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	BAMBOO_FEATURE("bamboo", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BANNER_PATTERN("bannerPattern", "banner_pattern"),
	BANNER_PATTERN_TAG("bannerPatternTag", "tags/banner_pattern"),
	BASALT_COLUMNS_FEATURE("basaltColumns", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BASALT_PILLAR_FEATURE("basaltPillar", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BEARDIFIER_DENSITY_FUNCTION("beardifier", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	BIOME("biome", "worldgen/biome"),
	BIOME_TAG("biomeTag", "tags/worldgen/biome"),
	BLASTING("blasting", "recipe", KoreScope.RECIPES),
	BLEND_ALPHA_DENSITY_FUNCTION("blendAlpha", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	BLEND_DENSITY_DENSITY_FUNCTION("blendDensity", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	BLEND_OFFSET_DENSITY_FUNCTION("blendOffset", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	BLOCK_BLOB_FEATURE("blockBlob", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BLOCK_COLUMN_FEATURE("blockColumn", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BLOCK_PILE_FEATURE("blockPile", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BLOCK_TAG("blockTag", "tags/block"),
	BLUE_ICE_FEATURE("blueIce", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BONUS_CHEST_FEATURE("bonusChest", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	BURIED_TREASURE("buriedTreasure", "worldgen/structure", KoreScope.STRUCTURES),
	BY_COST_ENCHANTMENT_PROVIDER("byCost", "enchantment_provider", KoreScope.ENCHANTMENT_PROVIDERS),
	BY_COST_WITH_DIFFICULTY_ENCHANTMENT_PROVIDER("byCostWithDifficulty", "enchantment_provider", KoreScope.ENCHANTMENT_PROVIDERS),
	CACHE_ALL_IN_CELL_DENSITY_FUNCTION("cacheAllInCell", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	CACHE_ONCE_DENSITY_FUNCTION("cacheOnce", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	CAMPFIRE_COOKING("campfireCooking", "recipe", KoreScope.RECIPES),
	CANYON_CARVER("canyon", "worldgen/configured_carver", KoreScope.CONFIGURED_CARVERS),
	CAT_SOUND_VARIANT("catSoundVariant", "cat_sound_variant"),
	CAT_VARIANT("catVariant", "cat_variant"),
	CAT_VARIANT_TAG("catVariantTag", "tags/cat_variant"),
	CAVE_CARVER("cave", "worldgen/configured_carver", KoreScope.CONFIGURED_CARVERS),
	CHAT_TYPE("chatType", "chat_type"),
	CHICKEN_SOUND_VARIANT("chickenSoundVariant", "chicken_sound_variant"),
	CHICKEN_VARIANT("chickenVariant", "chicken_variant"),
	CHORUS_PLANT_FEATURE("chorusPlant", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	CLAMP_DENSITY_FUNCTION("clamp", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	CLOCK_TIME_TEST_ENVIRONMENT("clockTime", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	CONFIGURED_CARVER("configuredCarver", "worldgen/configured_carver"),
	CONFIGURED_CARVER_TAG("configuredCarverTag", "tags/worldgen/configured_carver"),
	CONFIGURED_FEATURE("configuredFeature", "worldgen/configured_feature"),
	CONFIGURED_FEATURE_TAG("configuredFeatureTag", "tags/worldgen/configured_feature"),
	CONFIGURED_STRUCTURE_TAG("configuredStructureTag", "tags/worldgen/structure"),
	CONFIRMATION("confirmation", "dialog", KoreScope.DIALOGS),
	CONSTANT_DENSITY_FUNCTION("constant", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	CORAL_CLAW_FEATURE("coralClaw", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	CORAL_MUSHROOM_FEATURE("coralMushroom", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	CORAL_TREE_FEATURE("coralTree", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	COW_SOUND_VARIANT("cowSoundVariant", "cow_sound_variant"),
	COW_VARIANT("cowVariant", "cow_variant"),
	CRAFTING_DECORATED_POT("craftingDecoratedPot", "recipe", KoreScope.RECIPES),
	CRAFTING_DYE("craftingDye", "recipe", KoreScope.RECIPES),
	CRAFTING_IMBUE("craftingImbue", "recipe", KoreScope.RECIPES),
	CRAFTING_SHAPED("craftingShaped", "recipe", KoreScope.RECIPES),
	CRAFTING_SHAPELESS("craftingShapeless", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL("craftingSpecial", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_BANNER_DUPLICATE("craftingSpecialBannerDuplicate", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_BOOK_CLONING("craftingSpecialBookCloning", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_FIREWORK_ROCKET("craftingSpecialFireworkRocket", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_FIREWORK_STAR("craftingSpecialFireworkStar", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_FIREWORK_STAR_FADE("craftingSpecialFireworkStarFade", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_MAP_EXTENDING("craftingSpecialMapExtending", "recipe", KoreScope.RECIPES),
	CRAFTING_SPECIAL_SHIELD_DECORATION("craftingSpecialShieldDecoration", "recipe", KoreScope.RECIPES),
	CRAFTING_TRANSMUTE("craftingTransmute", "recipe", KoreScope.RECIPES),
	CUBE_DENSITY_FUNCTION("cube", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	DAMAGE_TYPE("damageType", "damage_type"),
	DAMAGE_TYPE_TAG("damageTypeTag", "tags/damage_type"),
	DATA_PACK("dataPack", null),
	DELTA_FEATURE("deltaFeature", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	DESERT_PYRAMID("desertPyramid", "worldgen/structure", KoreScope.STRUCTURES),
	DESERT_WELL_FEATURE("desertWell", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	DIALOG_LIST("dialogList", "dialog", KoreScope.DIALOGS),
	DIFFICULTY_TEST_ENVIRONMENT("difficulty", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	DIMENSION("dimension", "dimension"),
	DIMENSION_TYPE("dimensionType", "dimension_type"),
	DISK_FEATURE("disk", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	ENCHANTMENT("enchantment", "enchantment"),
	ENCHANTMENT_TAG("enchantmentTag", "tags/enchantment"),
	END_CITY("endCity", "worldgen/structure", KoreScope.STRUCTURES),
	END_GATEWAY_FEATURE("endGateway", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	END_ISLAND_FEATURE("endIsland", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	END_ISLANDS_DENSITY_FUNCTION("endIslands", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	END_PLATFORM_FEATURE("endPlatform", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	END_SPIKE_FEATURE("endSpike", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	ENTITY_TYPE_TAG("entityTypeTag", "tags/entity_type"),
	FILL_LAYER_FEATURE("fillLayer", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	FIND_TOP_SURFACE_DENSITY_FUNCTION("findTopSurface", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	FLAT_CACHE_DENSITY_FUNCTION("flatCache", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	FLAT_LEVEL_GENERATOR_PRESET("flatLevelGeneratorPreset", "worldgen/flat_level_generator_preset"),
	FLAT_LEVEL_GENERATOR_PRESET_TAG("flatLevelGeneratorPresetTag", "tags/worldgen/flat_level_generator_preset"),
	FLUID_TAG("fluidTag", "tags/fluid"),
	FORTRESS("fortress", "worldgen/structure", KoreScope.STRUCTURES),
	FOSSIL_FEATURE("fossil", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	FREEZE_TOP_LAYER_FEATURE("freezeTopLayer", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	FROG_VARIANT("frogVariant", "frog_variant"),
	FROG_VARIANT_TAG("frogVariantTag", "tags/frog_variant"),
	FUNCTION("function", "function"),
	FUNCTION_TEST_ENVIRONMENT("function", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	FUNCTION_TAG("functionTag", "tags/function"),
	GAME_EVENT_TAG("gameEventTag", "tags/game_event"),
	GAME_RULES_TEST_ENVIRONMENT("gameRules", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	GENERATED_FUNCTION("generatedFunction", "function"),
	GEODE_FEATURE("geode", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	GLOWSTONE_BLOB_FEATURE("glowstoneBlob", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	HALF_NEGATIVE_DENSITY_FUNCTION("halfNegative", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	HUGE_BROWN_MUSHROOM_FEATURE("hugeBrownMushroom", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	HUGE_FUNGUS_FEATURE("hugeFungus", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	HUGE_RED_MUSHROOM_FEATURE("hugeRedMushroom", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	ICEBERG_FEATURE("iceberg", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	IGLOO("igloo", "worldgen/structure", KoreScope.STRUCTURES),
	INSTRUMENT("instrument", "instrument"),
	INSTRUMENT_TAG("instrumentTag", "tags/instrument"),
	INTERPOLATED_DENSITY_FUNCTION("interpolated", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	INTERVAL_SELECT_DENSITY_FUNCTION("intervalSelect", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	INVERT_DENSITY_FUNCTION("invert", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	ITEM_MODIFIER("itemModifier", "item_modifier"),
	ITEM_TAG("itemTag", "tags/item"),
	JIGSAW("jigsaw", "worldgen/structure", KoreScope.STRUCTURES),
	JUKEBOX_SONG("jukeboxSong", "jukebox_song"),
	JUNGLE_TEMPLE("jungleTemple", "worldgen/structure", KoreScope.STRUCTURES),
	KELP_FEATURE("kelp", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	LAKE_FEATURE("lake", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	LARGE_DRIPSTONE_FEATURE("largeDripstone", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	LOAD("load", "function"),
	LOOT_TABLE("lootTable", "loot_table"),
	MAX_DENSITY_FUNCTION("max", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	MIN_DENSITY_FUNCTION("min", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	MINESHAFT("mineshaft", "worldgen/structure", KoreScope.STRUCTURES),
	MONSTER_ROOM_FEATURE("monsterRoom", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	MUL_DENSITY_FUNCTION("mul", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	MULTI_ACTION("multiAction", "dialog", KoreScope.DIALOGS),
	MULTIFACE_GROWTH_FEATURE("multifaceGrowth", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	NETHER_CAVE_CARVER("netherCave", "worldgen/configured_carver", KoreScope.CONFIGURED_CARVERS),
	NETHER_FOREST_VEGETATION_FEATURE("netherForestVegetation", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	NETHER_FOSSIL("netherFossil", "worldgen/structure", KoreScope.STRUCTURES),
	NETHERRACK_REPLACE_BLOBS_FEATURE("netherrackReplaceBlobs", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	NO_OP_FEATURE("noOp", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	NOISE("noise", "worldgen/noise"),
	NOISE_DENSITY_FUNCTION("noise", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	NOISE_SETTINGS("noiseSettings", "worldgen/noise_settings"),
	NOISE_SETTINGS_TAG("noiseSettingsTag", "tags/worldgen/noise_settings"),
	NOISE_TAG("noiseTag", "tags/worldgen/noise"),
	NOTICE("notice", "dialog", KoreScope.DIALOGS),
	OCEAN_MONUMENT("oceanMonument", "worldgen/structure", KoreScope.STRUCTURES),
	OCEAN_RUIN("oceanRuin", "worldgen/structure", KoreScope.STRUCTURES),
	OLD_BLENDED_NOISE_DENSITY_FUNCTION("oldBlendedNoise", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	ORE_FEATURE("ore", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	PAINTING_VARIANT("paintingVariant", "painting_variant"),
	PAINTING_VARIANT_TAG("paintingVariantTag", "tags/painting_variant"),
	PIG_SOUND_VARIANT("pigSoundVariant", "pig_sound_variant"),
	PIG_VARIANT("pigVariant", "pig_variant"),
	PIG_VARIANT_TAG("pigVariantTag", "tags/pig_variant"),
	PLACED_FEATURE("placedFeature", "worldgen/placed_feature"),
	PLACED_FEATURE_TAG("placedFeatureTag", "tags/worldgen/placed_feature"),
	POINT_OF_INTEREST_TYPE_TAG("pointOfInterestTypeTag", "tags/point_of_interest_type"),
	PREDICATE("predicate", "predicate"),
	PROCESSOR_LIST("processorList", "worldgen/processor_list"),
	PROCESSOR_LIST_TAG("processorListTag", "tags/worldgen/processor_list"),
	QUARTER_NEGATIVE_DENSITY_FUNCTION("quarterNegative", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	RANDOM_BOOLEAN_SELECTOR_FEATURE("randomBooleanSelector", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	RANDOM_PATCH_FEATURE("randomPatch", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	RANDOM_SELECTOR_FEATURE("randomSelector", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	RANGE_CHOICE_DENSITY_FUNCTION("rangeChoice", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	REPLACE_SINGLE_BLOCK_FEATURE("replaceSingleBlock", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	ROOT_SYSTEM_FEATURE("rootSystem", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	RUINED_PORTAL("ruinedPortal", "worldgen/structure", KoreScope.STRUCTURES),
	SCATTERED_ORE_FEATURE("scatteredOre", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SCULK_PATCH_FEATURE("sculkPatch", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SEA_PICKLE_FEATURE("seaPickle", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SEAGRASS_FEATURE("seagrass", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SEQUENCE_FEATURE("sequence", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SERVER_LINKS("serverLinks", "dialog", KoreScope.DIALOGS),
	SHIFT_DENSITY_FUNCTION("shift", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	SHIFT_A_DENSITY_FUNCTION("shiftA", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	SHIFT_B_DENSITY_FUNCTION("shiftB", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	SHIFTED_NOISE_DENSITY_FUNCTION("shiftedNoise", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	SHIPWRECK("shipwreck", "worldgen/structure", KoreScope.STRUCTURES),
	SIMPLE_BLOCK_FEATURE("simpleBlock", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SIMPLE_RANDOM_SELECTOR_FEATURE("simpleRandomSelector", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SINGLE_ENCHANTMENT_PROVIDER("single", "enchantment_provider", KoreScope.ENCHANTMENT_PROVIDERS),
	SMELTING("smelting", "recipe", KoreScope.RECIPES),
	SMITHING_TRANSFORM("smithingTransform", "recipe", KoreScope.RECIPES),
	SMITHING_TRIM("smithingTrim", "recipe", KoreScope.RECIPES),
	SMOKING("smoking", "recipe", KoreScope.RECIPES),
	SPELEOTHEM_FEATURE("speleothem", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SPELEOTHEM_CLUSTER_FEATURE("speleothemCluster", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SPIKE_FEATURE("spike", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SPLINE_DENSITY_FUNCTION("spline", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	SPRING_FEATURE("springFeature", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	SQUARE_DENSITY_FUNCTION("square", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	SQUEEZE_DENSITY_FUNCTION("squeeze", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	STONE_CUTTING("stoneCutting", "recipe", KoreScope.RECIPES),
	STRONGHOLD("stronghold", "worldgen/structure", KoreScope.STRUCTURES),
	STRUCTURE("structure", "worldgen/structure"),
	STRUCTURE_SET("structureSet", "worldgen/structure_set"),
	STRUCTURE_SET_TAG("structureSetTag", "tags/worldgen/structure_set"),
	STRUCTURE_TAG("structureTag", "tags/worldgen/structure"),
	SULFUR_CUBE_ARCHETYPE("sulfurCubeArchetype", "sulfur_cube_archetype"),
	SWAMP_HUT("swampHut", "worldgen/structure", KoreScope.STRUCTURES),
	TEMPLATE_FEATURE("template", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	TEMPLATE_POOL("templatePool", "worldgen/template_pool"),
	TEMPLATE_POOL_TAG("templatePoolTag", "tags/worldgen/template_pool"),
	TEST_ENVIRONMENT("testEnvironment", "test_environment"),
	TEST_INSTANCE("testInstance", "test_instance"),
	TICK("tick", "function"),
	TIMELINE("timeline", "timeline"),
	TIMELINE_ATTRIBUTES_TEST_ENVIRONMENT("timelineAttributes", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	TIMELINE_TAG("timelineTag", "tags/timeline"),
	TRADE_SET("tradeSet", "trade_set"),
	TREE_FEATURE("tree", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	TRIM_MATERIAL("trimMaterial", "trim_material"),
	TRIM_MATERIAL_TAG("trimMaterialTag", "tags/trim_material"),
	TRIM_PATTERN("trimPattern", "trim_pattern"),
	TRIM_PATTERN_TAG("trimPatternTag", "tags/trim_pattern"),
	TWISTING_VINES_FEATURE("twistingVines", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	UNDERWATER_MAGMA_FEATURE("underwaterMagma", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	VEGETATION_PATCH_FEATURE("vegetationPatch", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	VILLAGER_TRADE("villagerTrade", "villager_trade"),
	VINES_FEATURE("vines", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	VOID_START_PLATFORM_FEATURE("voidStartPlatform", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	WATERLOGGED_VEGETATION_PATCH_FEATURE("waterloggedVegetationPatch", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	WEATHER_TEST_ENVIRONMENT("weather", "test_environment", KoreScope.TEST_ENVIRONMENTS),
	WEEPING_VINES_FEATURE("weepingVines", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	WEIGHTED_RANDOM_SELECTOR_FEATURE("weightedRandomSelector", "worldgen/configured_feature", KoreScope.CONFIGURED_FEATURES),
	WOLF_SOUND_VARIANT("wolfSoundVariant", "wolf_sound_variant"),
	WOLF_VARIANT("wolfVariant", "wolf_variant"),
	WOLF_VARIANT_TAG("wolfVariantTag", "tags/wolf_variant"),
	WOODLAND_MANSION("woodlandMansion", "worldgen/structure", KoreScope.STRUCTURES),
	WORLD_CLOCK("worldClock", "world_clock"),
	WORLD_PRESET("worldPreset", "worldgen/world_preset"),
	WORLD_PRESET_TAG("worldPresetTag", "tags/worldgen/world_preset"),
	Y_CLAMPED_GRADIENT_DENSITY_FUNCTION("yClampedGradient", "worldgen/density_function", KoreScope.DENSITY_FUNCTIONS),
	ZOMBIE_NAUTILUS_VARIANT("zombieNautilusVariant", "zombie_nautilus_variant"),
	;

	/** The function family writes `.mcfunction` under an extra `<directory>` instead of `<folder>/<name>.json`. */
	val isFunction get() = resourceFolder == FUNCTION_RESOURCE_FOLDER

	/** Tags are referenced as `#namespace:name` and take their namespace as a second positional parameter, like functions. */
	val isTag get() = resourceFolder?.startsWith(TAGS_RESOURCE_FOLDER_PREFIX) == true

	/** `CRAFTING_SHAPED` -> `Crafting Shaped`, for the tooltip and the sort-by-kind grouping. */
	val displayName = name.split('_').joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::titlecase) }

	// Resolved lazily so the indexer, which only ever reads names and folders, never forces icon loading.
	val icon: Icon
		get() = when {
			this == DATA_PACK -> KoreIcons.KORE
			isFunction -> KoreIcons.FUNCTION
			else -> AllIcons.FileTypes.Json
		}

	companion object {
		private val byBuilderName = entries.groupBy(KoreDeclarationKind::builderName)

		/**
		 * The kind a callee named [name] declares. `noise` or `function` exist both as a top-level builder and inside a
		 * scope, so [activeScopes] decides: the scoped kind wins when its scope is active, else the unscoped one (if any).
		 * Only asked for when the name is ambiguous or scoped, so the common `function(...)` never pays for a scope walk.
		 */
		fun byBuilderName(name: String, activeScopes: () -> Set<KoreScope> = ::emptySet): KoreDeclarationKind? {
			val candidates = byBuilderName[name] ?: return null
			val unscoped = candidates.firstOrNull { it.scope == null }
			if (candidates.size == 1 && unscoped != null) return unscoped

			val scopes = activeScopes()
			return candidates.firstOrNull { it.scope != null && it.scope in scopes } ?: unscoped
		}
	}
}
