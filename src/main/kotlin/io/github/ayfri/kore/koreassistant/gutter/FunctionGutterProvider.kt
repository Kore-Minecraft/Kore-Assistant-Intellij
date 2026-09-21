package io.github.ayfri.kore.koreassistant.gutter

import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames

class FunctionGutterProvider : KoreCallGutterProvider(
	fqName = KoreNames.FUNCTION,
	icon = KoreIcons.FUNCTION,
	tooltipTitle = "Function Definition",
	tooltipText = "Kore function definition",
)
