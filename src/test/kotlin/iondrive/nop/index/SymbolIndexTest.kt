package iondrive.nop.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SymbolIndexTest {
    private fun type(name: String, owner: String, vararg supertypes: String) =
        IndexEntry(name, "${owner.replace('.', '/')}/$name.java", 3, SymbolKind.JAVA_TYPE, owner, supertypes.toList())

    @Test fun `subtypes match a bare or qualified supertype name`() {
        val index = SymbolIndex(
            listOf(
                type("Base", "a"),
                type("Bare", "a", "Base"),
                type("Qualified", "c", "a.Base"),
                type("Elsewhere", "c", "x.Base"),
                type("Unrelated", "a", "Other"),
            ),
        )
        assertEquals(listOf("Bare", "Qualified"), index.subtypesOf("a.Base").map { it.name })
    }

    @Test fun `nested supertypes match through their outer type`() {
        val index = SymbolIndex(
            listOf(
                type("Impl", "a", "Outer.Inner"),
                type("Shadow", "a", "Other.Inner"),
            ),
        )
        assertEquals(listOf("Impl"), index.subtypesOf("a.Outer.Inner").map { it.name })
    }

    @Test fun `supertypes survive the cache round trip`(@TempDir tmp: Path) {
        val file = tmp.resolve("index.tsv")
        SymbolIndex.save(file, SymbolIndex(listOf(type("Sub", "a", "Base", "b.Iface"), type("Base", "a"))))
        val loaded = assertNotNull(SymbolIndex.load(file))
        assertEquals(listOf("Base", "b.Iface"), loaded.lookup("Sub").single().supertypes)
        assertEquals(emptyList(), loaded.lookup("Base").single().supertypes)
    }

    @Test fun `the indexer links a subclass to its superclass across files`(@TempDir tmp: Path) {
        tmp.resolve("src/p").createDirectories()
        tmp.resolve("src/p/Animal.java").writeText("package p;\npublic abstract class Animal {}\n")
        tmp.resolve("src/p/Dog.java").writeText("package p;\n\npublic class Dog extends Animal {}\n")
        val subs = Indexer.build(tmp).subtypesOf("p.Animal")
        assertEquals(listOf("src/p/Dog.java" to 3), subs.map { it.file to it.line })
    }
}
