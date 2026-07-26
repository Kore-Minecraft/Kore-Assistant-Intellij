package io.github.ayfri.kore.koreassistant.index

/**
 * One row per Kore DSL builder that declares a datapack resource, keyed by the builder's short name
 * (the callee identifier the indexer sees, e.g. `function` in `function("x") { }`). Purely syntactic -
 * matching a name here does not mean the call actually resolves to Kore, that check happens at query time.
 *
 * [resourceFolder] mirrors the `Generator(resourceFolder)` constructor argument in Kore, i.e. the folder
 * under `data/<namespace>/` the resource is written to. Sourced from
 * `kore/src/commonMain/kotlin/io/github/ayfri/kore/DataPack.kt` (`registerGenerator<T>()` properties) and
 * the builder extension functions in each feature package; regenerate on every Kore MC-version bump.
 *
 * Two known upstream quirks are mirrored here rather than "fixed", so the plugin matches actual output:
 * - The enchantment-provider builders register with `resourceFolder = "trades"` (should be
 *   `enchantment_provider`), a copy-paste bug in Kore's `EnchantmentProvider.kt`.
 * - `Tag` overrides `getPathFromDataDir` to nest under `tags/<type>/<file>.json` - not representable as a
 *   flat resourceFolder, so tags are intentionally **not** in this table yet (needs its own path formula).
 *
 * Also not yet covered: the `dialogs { }` / `testEnvironments { }` / `testInstances { }` container
 * sub-builders (`notice`, `allOf`, `gameRules`, ...) - their names are too generic/collision-prone to index
 * confidently without also checking the enclosing container, which needs richer context than a flat
 * builder-name lookup gives.
 */
enum class KoreDeclarationKind(val builderName: String, val resourceFolder: String) {
	ADVANCEMENT("advancement", "advancement"),
	BANNER_PATTERN("bannerPattern", "banner_pattern"),
	BIOME("biome", "worldgen/biome"),
	BLASTING("blasting", "recipe"),
	BURIED_TREASURE("buriedTreasure", "worldgen/structure"),
	BY_COST_ENCHANTMENT_PROVIDER("byCostEnchantmentProvider", "trades"),
	BY_COST_WITH_DIFFICULTY_ENCHANTMENT_PROVIDER("byCostWithDifficultyEnchantmentProvider", "trades"),
	CAMPFIRE_COOKING("campfireCooking", "recipe"),
	CAT_SOUND_VARIANT("catSoundVariant", "cat_sound_variant"),
	CAT_VARIANT("catVariant", "cat_variant"),
	CHAT_TYPE("chatType", "chat_type"),
	CHICKEN_SOUND_VARIANT("chickenSoundVariant", "chicken_sound_variant"),
	CHICKEN_VARIANT("chickenVariant", "chicken_variant"),
	CONFIGURED_CARVER("configuredCarver", "worldgen/configured_carver"),
	CONFIGURED_FEATURE("configuredFeature", "worldgen/configured_feature"),
	COW_SOUND_VARIANT("cowSoundVariant", "cow_sound_variant"),
	COW_VARIANT("cowVariant", "cow_variant"),
	CRAFTING_DECORATED_POT("craftingDecoratedPot", "recipe"),
	CRAFTING_DYE("craftingDye", "recipe"),
	CRAFTING_IMBUE("craftingImbue", "recipe"),
	CRAFTING_SHAPED("craftingShaped", "recipe"),
	CRAFTING_SHAPELESS("craftingShapeless", "recipe"),
	CRAFTING_SPECIAL("craftingSpecial", "recipe"),
	CRAFTING_SPECIAL_BANNER_DUPLICATE("craftingSpecialBannerDuplicate", "recipe"),
	CRAFTING_SPECIAL_BOOK_CLONING("craftingSpecialBookCloning", "recipe"),
	CRAFTING_SPECIAL_FIREWORK_ROCKET("craftingSpecialFireworkRocket", "recipe"),
	CRAFTING_SPECIAL_FIREWORK_STAR("craftingSpecialFireworkStar", "recipe"),
	CRAFTING_SPECIAL_FIREWORK_STAR_FADE("craftingSpecialFireworkStarFade", "recipe"),
	CRAFTING_SPECIAL_MAP_EXTENDING("craftingSpecialMapExtending", "recipe"),
	CRAFTING_SPECIAL_SHIELD_DECORATION("craftingSpecialShieldDecoration", "recipe"),
	CRAFTING_TRANSMUTE("craftingTransmute", "recipe"),
	DAMAGE_TYPE("damageType", "damage_type"),
	DENSITY_FUNCTION("densityFunction", "worldgen/density_function"),
	DESERT_PYRAMID("desertPyramid", "worldgen/structure"),
	DIMENSION("dimension", "dimension"),
	DIMENSION_TYPE("dimensionType", "dimension_type"),
	ENCHANTMENT("enchantment", "enchantment"),
	END_CITY("endCity", "worldgen/structure"),
	FLAT_LEVEL_GENERATOR_PRESET("flatLevelGeneratorPreset", "worldgen/flat_level_generator_preset"),
	FORTRESS("fortress", "worldgen/structure"),
	FROG_VARIANT("frogVariant", "frog_variant"),
	FUNCTION("function", "function"),
	GENERATED_FUNCTION("generatedFunction", "function"),
	IGLOO("igloo", "worldgen/structure"),
	INSTRUMENT("instrument", "instrument"),
	ITEM_MODIFIER("itemModifier", "item_modifier"),
	JIGSAW("jigsaw", "worldgen/structure"),
	JUKEBOX_SONG("jukeboxSong", "jukebox_song"),
	JUNGLE_TEMPLE("jungleTemple", "worldgen/structure"),
	LOAD("load", "function"),
	LOOT_TABLE("lootTable", "loot_table"),
	MINESHAFT("mineshaft", "worldgen/structure"),
	NETHER_FOSSIL("netherFossil", "worldgen/structure"),
	NOISE("noise", "worldgen/noise"),
	NOISE_SETTINGS("noiseSettings", "worldgen/noise_settings"),
	OCEAN_MONUMENT("oceanMonument", "worldgen/structure"),
	OCEAN_RUIN("oceanRuin", "worldgen/structure"),
	PAINTING_VARIANT("paintingVariant", "painting_variant"),
	PIG_SOUND_VARIANT("pigSoundVariant", "pig_sound_variant"),
	PIG_VARIANT("pigVariant", "pig_variant"),
	PLACED_FEATURE("placedFeature", "worldgen/placed_feature"),
	PREDICATE("predicate", "predicate"),
	PROCESSOR_LIST("processorList", "worldgen/processor_list"),
	RUINED_PORTAL("ruinedPortal", "worldgen/structure"),
	SHIP_WRECK("shipWreck", "worldgen/structure"),
	SINGLE_ENCHANTMENT_PROVIDER("singleEnchantmentProvider", "trades"),
	SMELTING("smelting", "recipe"),
	SMITHING_TRANSFORM("smithingTransform", "recipe"),
	SMITHING_TRIM("smithingTrim", "recipe"),
	SMOKING("smoking", "recipe"),
	STONE_CUTTING("stoneCutting", "recipe"),
	STRONGHOLD("stronghold", "worldgen/structure"),
	STRUCTURE_SET("structureSet", "worldgen/structure_set"),
	SWAMP_HUT("swampHut", "worldgen/structure"),
	TEMPLATE_POOL("templatePool", "worldgen/template_pool"),
	TEST_ENVIRONMENT("testEnvironment", "test_environment"),
	TEST_INSTANCE("testInstance", "test_instance"),
	TICK("tick", "function"),
	TIMELINE("timeline", "timeline"),
	TRADE_SET("tradeSet", "trade_set"),
	TRIM_MATERIAL("trimMaterial", "trim_material"),
	TRIM_PATTERN("trimPattern", "trim_pattern"),
	VILLAGER_TRADE("villagerTrade", "villager_trade"),
	WOLF_SOUND_VARIANT("wolfSoundVariant", "wolf_sound_variant"),
	WOLF_VARIANT("wolfVariant", "wolf_variant"),
	WOODLAND_MANSION("woodlandMansion", "worldgen/structure"),
	WORLD_CLOCK("worldClock", "world_clock"),
	WORLD_PRESET("worldPreset", "worldgen/world_preset"),
	ZOMBIE_NAUTILUS_VARIANT("zombieNautilusVariant", "zombie_nautilus_variant"),
	;

	companion object {
		private val byBuilderName = entries.associateBy(KoreDeclarationKind::builderName)

		fun byBuilderName(name: String): KoreDeclarationKind? = byBuilderName[name]
	}
}
