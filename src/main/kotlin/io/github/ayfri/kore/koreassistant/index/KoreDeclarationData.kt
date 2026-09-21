package io.github.ayfri.kore.koreassistant.index

import com.intellij.util.io.DataExternalizer
import io.github.ayfri.kore.koreassistant.psi.KorePropertyResolver
import io.github.ayfri.kore.koreassistant.psi.KoreStringValue
import io.github.ayfri.kore.koreassistant.psi.declarationNameArgument
import io.github.ayfri.kore.koreassistant.psi.enclosingDataPackName
import io.github.ayfri.kore.koreassistant.psi.koreStringPlaceholder
import io.github.ayfri.kore.koreassistant.psi.koreStringValue
import io.github.ayfri.kore.koreassistant.psi.namedArgument
import io.github.ayfri.kore.koreassistant.psi.namespaceAssignmentInBlock
import io.github.ayfri.kore.koreassistant.psi.positionalArgument
import org.jetbrains.kotlin.psi.KtCallExpression
import java.io.DataInput
import java.io.DataOutput

private const val NAMESPACE_PARAMETER_NAME = "namespace"
private const val DIRECTORY_PARAMETER_NAME = "directory"

// `function(name, namespace, directory) { }` and `blockTag(fileName, namespace) { }` pass them as parameters rather than in the block.
private const val NAMESPACE_PARAMETER_INDEX = 1
private const val DIRECTORY_PARAMETER_INDEX = 2

/**
 * Indexed value for one Kore declaration call: what kind it is, its declared name, where it is, and the
 * namespace/directory/datapack it will be written under - everything needed to rebuild its output path
 * without touching PSI again.
 *
 * [namespace] and [directory] are `null` when the declaration does not spell them out (the common case);
 * the effective namespace is then [dataPackName], mirroring `Generator.getFinalPath`'s `namespace ?: dataPack.name`.
 *
 * [isDynamic] means at least one of those parts is built at runtime (a loop variable, an unreachable constant),
 * so the strings here are templates showing the source spelling rather than the final output.
 */
data class KoreDeclarationData(
	val kind: KoreDeclarationKind,
	val name: String,
	val namespace: String?,
	val directory: String?,
	val dataPackName: String?,
	val isDynamic: Boolean,
	val offset: Int,
)

/**
 * Reads everything the plugin knows about a declaration call. [resolver] decides how far constants are
 * followed: the indexer may only look inside the file being indexed, the tool window resolves across files.
 * `null` when the call has no name argument at all, i.e. it is not a declaration after all.
 */
fun KtCallExpression.koreDeclarationData(kind: KoreDeclarationKind, resolver: KorePropertyResolver): KoreDeclarationData? {
	val nameArgument = declarationNameArgument() ?: return null
	// Even a name the plugin cannot compute (`lootTable(idOf(leaf))`) is worth listing as its own source text.
	val name = nameArgument.koreStringValue(resolver) ?: nameArgument.koreStringPlaceholder()

	val namespace = namedArgument(NAMESPACE_PARAMETER_NAME)?.koreStringValue(resolver)
		?: (if (kind.isFunction || kind.isTag) positionalArgument(NAMESPACE_PARAMETER_INDEX)?.koreStringValue(resolver) else null)
		?: namespaceAssignmentInBlock(resolver)

	val directory = if (!kind.isFunction) null else namedArgument(DIRECTORY_PARAMETER_NAME)?.koreStringValue(resolver)
		?: positionalArgument(DIRECTORY_PARAMETER_INDEX)?.koreStringValue(resolver)

	// A `dataPack("x") { }` is its own datapack; everything else inherits the enclosing one, if visible.
	val dataPack = if (kind == KoreDeclarationKind.DATA_PACK) name else enclosingDataPackName(resolver)

	return KoreDeclarationData(
		kind = kind,
		name = name.text,
		namespace = namespace?.text,
		directory = directory?.text,
		dataPackName = dataPack?.text,
		isDynamic = listOfNotNull(name, namespace, directory, dataPack).any(KoreStringValue::isDynamic),
		offset = textOffset,
	)
}

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
			out.writeBoolean(data.isDynamic)
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
			isDynamic = input.readBoolean(),
			offset = input.readInt(),
		)
	}
}

private fun DataOutput.writeNullableUTF(value: String?) {
	writeBoolean(value != null)
	value?.let(::writeUTF)
}

private fun DataInput.readNullableUTF() = if (readBoolean()) readUTF() else null
