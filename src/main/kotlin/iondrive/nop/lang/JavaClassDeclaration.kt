package iondrive.nop.lang

import com.sun.source.util.JavacTask
import java.io.File
import java.net.URI
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.element.VariableElement
import javax.tools.SimpleJavaFileObject
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/** Reads signatures through javac's class-file model without loading or initializing project classes. */
object JavaClassDeclaration {
    fun render(binaryName: String, classpath: List<File>): String {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Java compiler unavailable" }
        compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { manager ->
            manager.setLocation(StandardLocation.CLASS_PATH, classpath)
            manager.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, emptyList())
            val source = object : SimpleJavaFileObject(URI.create("string:///LibraryView.java"), JavaFileObject.Kind.SOURCE) {
                override fun getCharContent(ignoreEncodingErrors: Boolean) = "final class LibraryView {}"
            }
            val task = compiler.getTask(null, manager, { }, listOf("-proc:none", "-nowarn"), null, listOf(source)) as JavacTask
            task.parse().toList()
            task.analyze().toList()
            val type = checkNotNull(task.elements.getTypeElement(binaryName.replace('$', '.'))) {
                "Class not found on the analysis classpath: $binaryName"
            }
            val pkg = task.elements.getPackageOf(type).qualifiedName.toString()
            val owners = generateSequence(type.enclosingElement as? TypeElement) { it.enclosingElement as? TypeElement }
                .toList().asReversed()
            val indent = "    ".repeat(owners.size)
            return buildString {
                append("// Compiled declaration. Method bodies are unavailable.\n")
                if (pkg.isNotEmpty()) append("package $pkg;\n\n")
                owners.forEachIndexed { index, owner -> append("    ".repeat(index)).append(header(owner)).append(" {\n") }
                append(indent).append(header(type)).append(" {\n")
                for (member in type.enclosedElements) {
                    if (Modifier.PRIVATE in member.modifiers) continue
                    val prefix = modifiers(member.modifiers)
                    when (member) {
                        is ExecutableElement -> {
                            val name = if (member.kind == ElementKind.CONSTRUCTOR) type.simpleName.toString()
                                else member.simpleName.toString()
                            val parameters = member.parameters.mapIndexed { index, parameter ->
                                val original = parameter.asType().toString()
                                val parameterType = if (member.isVarArgs && index == member.parameters.lastIndex)
                                    original.removeSuffix("[]") + "..." else original
                                "$parameterType ${parameter.simpleName}"
                            }.joinToString(", ")
                            val result = if (member.kind == ElementKind.CONSTRUCTOR) "" else "${member.returnType} "
                            val generic = typeParameters(member.typeParameters).let { if (it.isEmpty()) "" else "$it " }
                            append("$indent    $prefix$generic$result$name($parameters)")
                            if (member.thrownTypes.isNotEmpty()) append(" throws ${member.thrownTypes.joinToString(", ")}")
                            member.defaultValue?.let { append(" default $it") }
                            append(";\n")
                        }
                        is VariableElement -> append("$indent    $prefix${member.asType()} ${member.simpleName};\n")
                        is TypeElement -> append("$indent    ${header(member)} { /* nested type */ }\n")
                    }
                }
                append(indent).append("}\n")
                owners.indices.reversed().forEach { append("    ".repeat(it)).append("}\n") }
            }
        }
    }

    private fun modifiers(values: Set<Modifier>): String =
        values.joinToString(" ").let { if (it.isEmpty()) "" else "$it " }

    private fun typeParameters(parameters: List<TypeParameterElement>): String =
        if (parameters.isEmpty()) "" else parameters.joinToString(", ", "<", ">") { parameter ->
            val bounds = parameter.bounds.filter { it.toString() != "java.lang.Object" }
            parameter.simpleName.toString() + if (bounds.isEmpty()) "" else " extends ${bounds.joinToString(" & ")}"
        }

    private fun header(type: TypeElement): String = buildString {
        append(modifiers(type.modifiers))
        append(when (type.kind) {
            ElementKind.INTERFACE -> "interface"
            ElementKind.ANNOTATION_TYPE -> "@interface"
            ElementKind.ENUM -> "enum"
            ElementKind.RECORD -> "record"
            else -> "class"
        })
        append(" ${type.simpleName}${typeParameters(type.typeParameters)}")
        if (type.kind == ElementKind.RECORD) {
            append(type.recordComponents.joinToString(", ", "(", ")") { "${it.asType()} ${it.simpleName}" })
        }
        val superclass = type.superclass.toString()
        if (type.kind == ElementKind.CLASS && superclass !in setOf("none", "java.lang.Object")) append(" extends $superclass")
        if (type.interfaces.isNotEmpty()) {
            append(if (type.kind == ElementKind.INTERFACE) " extends " else " implements ")
            append(type.interfaces.joinToString(", "))
        }
    }
}
