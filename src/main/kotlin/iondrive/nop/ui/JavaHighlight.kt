package iondrive.nop.ui

import com.sun.source.tree.BlockTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.InstanceOfTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewArrayTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TryTree
import com.sun.source.tree.TypeCastTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreeScanner
import iondrive.nop.lang.JavaDecl
import iondrive.nop.lang.JavaDeclKind
import iondrive.nop.lang.ParsedJava
import javax.lang.model.element.Modifier

/** Member colours from the editor's background parse; types and ordinary calls keep the text colour. */
internal fun javaMemberTokens(parsed: ParsedJava, declarations: List<JavaDecl>): List<Token> {
    val out = ArrayList<Token>()
    val fieldsByStart = HashMap<Int, TokenKind>()
    val declarationsByName = declarations.filter { it.kind == JavaDeclKind.FIELD }.groupBy { it.name }

    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start >= 0 && end > start && end <= parsed.text.length) out += Token(start, end, kind)
    }

    data class Scope(val fields: Map<String, TokenKind>, val locals: MutableSet<String> = HashSet()) {
        fun nested() = copy(locals = HashSet(locals))
    }

    object : TreeScanner<Unit, Scope>() {
        override fun visitClass(node: ClassTree, scope: Scope): Unit {
            val ownFields = node.members.filterIsInstance<VariableTree>().associate { field ->
                val kind = if (Modifier.STATIC in field.modifiers.flags || node.kind == Tree.Kind.INTERFACE ||
                    node.kind == Tree.Kind.ANNOTATION_TYPE || field.type == null
                ) TokenKind.STATIC_FIELD else TokenKind.FIELD
                declarationsByName[field.name.toString()]?.firstOrNull {
                    it.nameStart >= parsed.startOf(field) && it.nameEnd <= parsed.endOf(field)
                }?.let { fieldsByStart[it.nameStart] = kind }
                field.name.toString() to kind
            }
            val inner = Scope(scope.fields + ownFields, HashSet(scope.locals - ownFields.keys))
            for (member in node.members) {
                if (member is VariableTree) scan(member.initializer, inner) else scan(member, inner)
            }
        }

        override fun visitMethod(node: MethodTree, scope: Scope): Unit {
            val inner = scope.nested()
            inner.locals.addAll(node.parameters.map { it.name.toString() })
            scan(node.body, inner)
            scan(node.defaultValue, inner)
        }

        override fun visitBlock(node: BlockTree, scope: Scope): Unit {
            super.visitBlock(node, scope.nested())
        }

        override fun visitVariable(node: VariableTree, scope: Scope): Unit {
            scope.locals += node.name.toString()
            scan(node.initializer, scope)
        }

        override fun visitLambdaExpression(node: LambdaExpressionTree, scope: Scope): Unit {
            val inner = scope.nested()
            inner.locals.addAll(node.parameters.map { it.name.toString() })
            scan(node.body, inner)
        }

        override fun visitForLoop(node: ForLoopTree, scope: Scope): Unit {
            super.visitForLoop(node, scope.nested())
        }

        override fun visitEnhancedForLoop(node: EnhancedForLoopTree, scope: Scope): Unit {
            scan(node.expression, scope)
            val inner = scope.nested()
            inner.locals += node.variable.name.toString()
            scan(node.statement, inner)
        }

        override fun visitCatch(node: CatchTree, scope: Scope): Unit {
            val inner = scope.nested()
            inner.locals += node.parameter.name.toString()
            scan(node.block, inner)
        }

        override fun visitTry(node: TryTree, scope: Scope): Unit {
            val inner = scope.nested()
            scan(node.resources, inner)
            scan(node.block, inner)
            scan(node.catches, scope)
            scan(node.finallyBlock, scope)
        }

        override fun visitIdentifier(node: IdentifierTree, scope: Scope): Unit {
            val name = node.name.toString()
            if (name !in scope.locals) scope.fields[name]?.let {
                add(parsed.startOf(node), parsed.endOf(node), it)
            }
        }

        override fun visitMemberSelect(node: MemberSelectTree, scope: Scope): Unit {
            val name = node.identifier.toString()
            // A parse proves that a selection is a field, but cannot resolve an external field's
            // modifiers. Constant spelling supplies the conventional static-field style there.
            if (name !in setOf("class", "this", "super") &&
                (name.firstOrNull()?.isUpperCase() != true || name.all { !it.isLetter() || it.isUpperCase() })
            ) {
                val kind = if (name.all { !it.isLetter() || it.isUpperCase() }) TokenKind.STATIC_FIELD
                    else if (node.expression.toString() == "this") scope.fields[name] ?: TokenKind.FIELD
                    else TokenKind.FIELD
                val end = parsed.endOf(node)
                add(end - name.length, end, kind)
            }
            scan(node.expression, scope)
        }

        override fun visitMethodInvocation(node: MethodInvocationTree, scope: Scope): Unit {
            // The selector here names a method; only its receiver can contain field references.
            (node.methodSelect as? MemberSelectTree)?.let { scan(it.expression, scope) }
            scan(node.arguments, scope)
        }

        override fun visitNewClass(node: NewClassTree, scope: Scope): Unit {
            scan(node.enclosingExpression, scope)
            scan(node.arguments, scope)
            scan(node.classBody, scope)
        }

        override fun visitNewArray(node: NewArrayTree, scope: Scope): Unit {
            scan(node.dimensions, scope)
            scan(node.initializers, scope)
        }

        override fun visitTypeCast(node: TypeCastTree, scope: Scope): Unit {
            scan(node.expression, scope)
        }

        override fun visitInstanceOf(node: InstanceOfTree, scope: Scope): Unit {
            scan(node.expression, scope)
            scan(node.pattern, scope)
        }
    }.let { scanner ->
        val imports = parsed.unit.imports.filter { it.isStatic }.mapNotNull {
            (it.qualifiedIdentifier as? MemberSelectTree)?.identifier?.toString()?.takeUnless { name -> name == "*" }
        }.associateWith { TokenKind.STATIC_FIELD }
        parsed.unit.typeDecls.forEach { scanner.scan(it, Scope(imports)) }
    }

    for (declaration in declarations) {
        val kind = when (declaration.kind) {
            JavaDeclKind.FIELD -> fieldsByStart[declaration.nameStart] ?: TokenKind.FIELD
            JavaDeclKind.METHOD -> TokenKind.FUNCTION_DECLARATION
            JavaDeclKind.TYPE -> continue
        }
        add(declaration.nameStart, declaration.nameEnd, kind)
    }
    return out.sortedBy { it.start }
}
