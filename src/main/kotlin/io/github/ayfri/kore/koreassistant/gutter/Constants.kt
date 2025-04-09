package io.github.ayfri.kore.koreassistant.gutter

import com.intellij.openapi.util.IconLoader
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

data object KoreNames {
	val KORE_DATAPACK_CLASS_ID = FqName("io.github.ayfri.kore.dataPack")
	val KORE_DATAPACK_NAME = Name.identifier("dataPack")
	val KORE_FUNCTION_CLASS_ID = FqName("io.github.ayfri.kore.functions.function")
	val KORE_FUNCTION_NAME = Name.identifier("function")
}

data object Icons {
	// Use java.classLoader to ensure compatibility across different environments
	val KORE = IconLoader.getIcon("/images/kore-white.svg", DataPackGutterProvider::class.java.classLoader)
	val FUNCTION = IconLoader.getIcon("/images/function.svg", DataPackGutterProvider::class.java.classLoader)
}
