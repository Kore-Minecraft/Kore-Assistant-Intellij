package io.github.ayfri.kore.koreassistant

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor

/**
 * A light project carrying an (empty) module library named like a Gradle-imported Kore, so
 * `KoreLibraryService.isKoreProject` gates let the extension under test run. The fake `io.github.ayfri.kore`
 * sources each test adds are what resolution actually sees.
 */
object KoreProjectDescriptor : LightProjectDescriptor() {
	override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
		super.configureModule(module, model, contentEntry)
		model.moduleLibraryTable.createLibrary("Gradle: io.github.ayfri.kore:kore:2.14.0-26.2")
	}
}
