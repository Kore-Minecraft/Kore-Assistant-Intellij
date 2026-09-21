package io.github.ayfri.kore.koreassistant

import com.intellij.openapi.util.IconLoader
import org.jetbrains.kotlin.name.FqName

object KoreNames {
	val DATA_PACK = FqName("io.github.ayfri.kore.dataPack")
	val FUNCTION = FqName("io.github.ayfri.kore.functions.function")
}

object KoreIcons {
	val KORE = IconLoader.getIcon("/images/kore-white.svg", KoreIcons::class.java.classLoader)
	val FUNCTION = IconLoader.getIcon("/images/function.svg", KoreIcons::class.java.classLoader)
}
