# Changelog

## [Unreleased]

### Changed

- The "Kore Elements" tool window now hides itself entirely on non-Kore projects and has a proper tab name instead of an unnamed one.

### Fixed

- Removed a log line written for every resolved `dataPack`/`function` call, which was slowing down the editor on files with many such calls.

### Maintenance

- Merged `DataPackGutterProvider` and `FunctionGutterProvider` onto a shared `KoreCallGutterProvider` base, dropping the duplicated resolution logic.
- Renamed `DatapackGutterProvider.kt` to `DataPackGutterProvider.kt` to match its class name.
- Removed a dead `groovyScript` variable from the `fn` live template that did nothing.
- Added `KoreLibraryService` to detect Kore projects and resolve the Kore/Minecraft version pair, shared by future features.

## [0.0.3] - 2026-07-22

### Changed

- Kore elements are now PSI-free DTOs referencing a file URL and offset, so the tool window cache survives reindexing and can cross the RPC boundary in split mode.
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
