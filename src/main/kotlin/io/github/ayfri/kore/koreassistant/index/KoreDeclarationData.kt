package io.github.ayfri.kore.koreassistant.index

import com.intellij.util.io.DataExternalizer
import java.io.DataInput
import java.io.DataOutput

/**
 * Indexed value for one Kore declaration call: what kind it is, its declared name, where it is, and the
 * namespace/directory/datapack it will be written under - everything needed to rebuild its output path
 * without touching PSI again.
 *
 * [namespace] and [directory] are `null` when the declaration does not spell them out (the common case);
 * the effective namespace is then [dataPackName], mirroring `Generator.getFinalPath`'s `namespace ?: dataPack.name`.
 */
data class KoreDeclarationData(
	val kind: KoreDeclarationKind,
	val name: String,
	val namespace: String?,
	val directory: String?,
	val dataPackName: String?,
	val offset: Int,
)

/** A file can hold several declarations sharing one name (different namespaces), so the value is a list. */
data object KoreDeclarationDataExternalizer : DataExternalizer<List<KoreDeclarationData>> {
	override fun save(out: DataOutput, value: List<KoreDeclarationData>) {
		out.writeInt(value.size)
		for (data in value) {
			out.writeUTF(data.kind.name)
			out.writeUTF(data.name)
			out.writeNullableUTF(data.namespace)
			out.writeNullableUTF(data.directory)
			out.writeNullableUTF(data.dataPackName)
			out.writeInt(data.offset)
		}
	}

	override fun read(input: DataInput) = List(input.readInt()) {
		KoreDeclarationData(
			kind = KoreDeclarationKind.valueOf(input.readUTF()),
			name = input.readUTF(),
			namespace = input.readNullableUTF(),
			directory = input.readNullableUTF(),
			dataPackName = input.readNullableUTF(),
			offset = input.readInt(),
		)
	}
}

private fun DataOutput.writeNullableUTF(value: String?) {
	writeBoolean(value != null)
	value?.let(::writeUTF)
}

private fun DataInput.readNullableUTF() = if (readBoolean()) readUTF() else null
