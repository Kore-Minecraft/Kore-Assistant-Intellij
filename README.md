# Kore Assistant

![Plugin Icon](src/main/resources/images/kore.png)

An Intellij IDEA extension providing powerful tools for working with [Kore](https://kore.ayfri.com), a Kotlin library for creating Minecraft datapacks without writing JSON.

[![Build](https://github.com/Ayfri/kore-assistant/actions/workflows/build.yml/badge.svg)](https://github.com/Ayfri/kore-assistant/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/io.github.ayfri.kore.kore-assistant.svg)](https://plugins.jetbrains.com/plugin/io.github.ayfri.kore.kore-assistant)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/io.github.ayfri.kore.kore-assistant.svg)](https://plugins.jetbrains.com/plugin/io.github.ayfri.kore.kore-assistant)

<!-- Plugin description -->

An IntelliJ IDEA extension providing powerful tools for working with [Kore](https://kore.ayfri.com), a Kotlin library for creating Minecraft datapacks without writing JSON. Features:

- Gutter icons for quick identification of Kore `DataPack` objects and functions.
- A dedicated "Kore Elements" tool window to easily browse Kore components within your project.

<!-- Plugin description end -->

---

**Kore Assistant** enhances your development experience with Kore by providing useful features directly within IntelliJ IDEA.

## Features

*   **Gutter Icons:** Easily identify Kore `DataPack` objects and functions with dedicated icons in the editor gutter.
*   **Kore Elements Tool Window:** Browse and navigate through the Kore components (like datapacks, functions, tags, etc.) defined in your project using a dedicated tool window.
*   **Live Templates:** Quickly create Kore `dataPack` and `function` blocks using the `dp` and `fn` live templates respectively.

## Installation

-   **Using IDE built-in plugin system:**

    <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Kore Assistant"</kbd> > <kbd>Install</kbd>

-   **Manually:**

    Download the [latest release](https://github.com/Ayfri/kore-assistant/releases/latest) and install it manually using <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Usage

-   **Gutter Icons:** Look for the Kore icon (✨) next to your `DataPack` object declarations and function definitions.
-   **Kore Elements Tool Window:** Access the tool window via <kbd>View</kbd> > <kbd>Tool Windows</kbd> > <kbd>Kore Elements</kbd>. It displays a tree view of the Kore elements found in your current project.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgements

*   Based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
