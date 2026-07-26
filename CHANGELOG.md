# Changelog

## [Unreleased]

### Added

- The "Kore Elements" tool window now lists every kind of Kore declaration (advancements, loot tables, recipes,
  predicates, worldgen, ...) instead of only `dataPack` and `function`, as a tree mirroring the generated datapack
  layout.
- The tool window can group by output structure, source file or a flat list, and gained a filter field, speed search,
  Expand All / Collapse All, and Enter to open the selected element.
- Hovering an element shows the path Kore will generate it to, e.g. `data/mypack/advancement/root.json`.
- Resources declared in `fun DataPack.xxx()` extensions, the layout Kore recommends, appear under the datapack that
  calls them.

### Changed

- The "Kore Elements" tool window now hides itself entirely on non-Kore projects and has a proper tab name instead of an
  unnamed one.
- The tool window refreshes itself as you edit Kotlin files instead of only when the Refresh button is pressed.

### Fixed

- The "Kore Elements" tool window no longer stays missing on Kore projects: it now appears as soon as the build system
  finishes importing Kore, instead of only after an IDE restart.
- Removed a log line written for every resolved `dataPack`/`function` call, which was slowing down the editor on files
  with many such calls.

### Maintenance

- Added a syntactic file-based index of Kore declaration calls (`function`, `advancement`, `lootTable`, ...), recording
  the namespace, directory and enclosing datapack of each one, backing the tool window in place of the old
  `ReferencesSearch` scan.
- Added the first tests, covering the declaration index, the Kore-resolution filter and the output tree.
- Merged `DataPackGutterProvider` and `FunctionGutterProvider` onto a shared `KoreCallGutterProvider` base, dropping the
  duplicated resolution logic.
- Renamed `DatapackGutterProvider.kt` to `DataPackGutterProvider.kt` to match its class name.
- Removed a dead `groovyScript` variable from the `fn` live template that did nothing.
- Added `KoreLibraryService` to detect Kore projects and resolve the Kore/Minecraft version pair, shared by future
  features.
- Added `KoreRegistryService` to resolve vanilla id registries (items, blocks, gamerules, ...) from the user's own
  resolved Kore jar, not yet consumed by any feature.

## [0.0.3] - 2026-07-22

### Changed

- Kore elements are now PSI-free DTOs referencing a file URL and offset, so the tool window cache survives reindexing
  and can cross the RPC boundary in split mode.
- Deduplicate found elements through a set instead of a linear scan.
- Reuse the tool window cell renderer components instead of allocating a panel per repaint.

### Maintenance

- Make it compatible from 2026.2 up to 2029.2
- Target Java 25, as required by IntelliJ Platform 2026.2.
- Updated dependencies.
- Moved the release workflow to `.github/workflows` and added a build workflow.

## [0.0.2] - 2026-01-27

### Maintenance

- Make it compatible from 2025.2 up to 2028.2
- Updated dependencies.

## [0.0.1] - 2025-05-19

### Added

- Initial setup of the plugin.
- Gutter icon provider for Kore `DataPack` objects.
- Gutter icon provider for Kore functions.
- "Kore Elements" tool window to browse Kore components in the project.

[Unreleased]: https://github.com/Kore-Minecraft/Kore-Assistant-Intellij/compare/v0.0.3...HEAD
[0.0.3]: https://github.com/Kore-Minecraft/Kore-Assistant-Intellij/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/Kore-Minecraft/Kore-Assistant-Intellij/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/Kore-Minecraft/Kore-Assistant-Intellij/commits/v0.0.1
