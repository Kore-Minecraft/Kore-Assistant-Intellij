# Changelog

## [Unreleased]

### Added

- The "Kore Elements" tool window now lists every kind of Kore declaration (advancements, loot tables, recipes,
  predicates, dialogs, worldgen, ...) as a tree mirroring the generated datapack layout, groupable by output structure,
  source file or flat list, sortable by name, kind, namespace or declaration order, with a filter field, speed search,
  Expand All / Collapse All and Enter to open an element.
- Hovering any row shows a card laid out like the IDE's documentation popup: the declaration in monospace, then its
  resource location, generated path (e.g. `data/mypack/advancement/root.json`), the command that runs it, its datapack,
  namespace and source location. Datapack, namespace, folder and file rows summarise their whole subtree instead.
- Right-clicking any row offers Copy, which opens a searchable list previewing each value in grey. Elements copy their
  resource location (`mypack:foo`), command (`/function mypack:foo`), output path, name, source location or absolute
  path; datapack, namespace, folder and file rows copy their own name and path, plus every resource location or output
  path underneath them at once.
- Right-clicking an element also offers Jump to Source and Find Usages.
- Declarations are found whatever their name is built from: a constant declared in another file (`dataPack(NAMESPACE)`),
  a concatenation, or an interpolation like `lootTable("blocks/$leafId")` - listed as its template, in italics, since
  the final name is only known at runtime.
- Resources are attached to their datapack across every layout Kore allows: `fun DataPack.xxx()` extensions, helpers
  taking the datapack as a parameter or context parameter, and `dataPack("x").apply { }`.

### Changed

- The tool window hides itself on non-Kore projects, has a proper tab name, and refreshes as you edit Kotlin files.

### Fixed

- The tool window now appears as soon as the build system finishes importing Kore, instead of only after an IDE restart.
- Removed a log line written for every resolved `dataPack`/`function` call, which slowed the editor down on files with
  many of them.

### Maintenance

- Backed the tool window with a syntactic file-based index of declaration calls instead of the old `ReferencesSearch`
  scan; the index only locates calls, the tool window re-reads each one once resolution is available.
- Added `psi/KoreStringValue.kt`, the shared constant-folding string reader both of them read names with.
- Merged `DataPackGutterProvider` and `FunctionGutterProvider` onto a shared `KoreCallGutterProvider` base.
- Added `KoreLibraryService` (Kore project detection, Kore/Minecraft version pair) and `KoreRegistryService` (vanilla id
  registries read from the user's own Kore jar), neither consumed by a user-facing feature yet.
- Added tests covering the index, the Kore-resolution filter, the output tree, runtime-built names, the datapack
  layouts, and what each row kind copies and hovers.
- Declared the tool window through the `com.intellij.toolWindow` extension point and reduced `KoreToolWindowRegistrar`
  to toggling its availability, dropping the override-only `ToolWindowManager` call the Plugin Verifier rejected.

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
