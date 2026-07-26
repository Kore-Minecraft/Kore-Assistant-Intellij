package io.github.ayfri.kore.koreassistant.gutter

import io.github.ayfri.kore.koreassistant.KoreIcons
import io.github.ayfri.kore.koreassistant.KoreNames

class DataPackGutterProvider : KoreCallGutterProvider(
	shortName = KoreNames.KORE_DATAPACK_NAME,
	fqName = KoreNames.KORE_DATAPACK_CLASS_ID,
	icon = KoreIcons.KORE,
	tooltipTitle = "DataPack Definition",
	tooltipText = "Kore dataPack definition",
)
