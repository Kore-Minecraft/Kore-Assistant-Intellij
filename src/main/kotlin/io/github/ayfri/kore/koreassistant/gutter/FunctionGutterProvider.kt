package io.github.ayfri.kore.koreassistant.gutter

import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames

class FunctionGutterProvider : KoreCallGutterProvider(
	shortName = KoreNames.KORE_FUNCTION_NAME,
	fqName = KoreNames.KORE_FUNCTION_CLASS_ID,
	icon = KoreIcons.FUNCTION,
	tooltipTitle = "Function Definition",
	tooltipText = "Kore function definition",
)
