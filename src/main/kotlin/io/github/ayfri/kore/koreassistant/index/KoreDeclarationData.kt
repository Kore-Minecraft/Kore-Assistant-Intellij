package io.github.ayfri.kore.koreassistant.index

import com.intellij.util.io.DataExternalizer
import java.io.DataInput
import java.io.DataOutput

/** Indexed value for one Kore declaration call: what kind it is, its name, where it is, and (maybe) its namespace. */
data class KoreDeclarationData(
	val kind: KoreDeclarationKind,
	val name: String,
	val offset: Int,
	val namespaceHint: String?,
)

data object KoreDeclarationDataExternalizer : DataExternalizer<KoreDeclarationData> {
	override fun save(out: DataOutput, value: KoreDeclarationData) {
		out.writeUTF(value.kind.name)
		out.writeUTF(value.name)
		out.writeInt(value.offset)
		out.writeBoolean(value.namespaceHint != null)
		value.namespaceHint?.let(out::writeUTF)
	}

	override fun read(input: DataInput): KoreDeclarationData {
		val kind = KoreDeclarationKind.valueOf(input.readUTF())
		val name = input.readUTF()
		val offset = input.readInt()
		val namespaceHint = if (input.readBoolean()) input.readUTF() else null
		return KoreDeclarationData(kind, name, offset, namespaceHint)
	}
}
