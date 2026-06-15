package iondrive.nop.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexerTest {
    @Test fun `indexes ansible role directories`(@TempDir tmp: Path) {
        val rolesMain = tmp.resolve("roles/adn_api_server/tasks/main.yml")
        rolesMain.parent.createDirectories()
        rolesMain.writeText("- name: hello\n")

        val index = Indexer.build(tmp)
        val match = index.lookup("adn_api_server").singleOrNull()
        assertNotNull(match)
        assertEquals(SymbolKind.ANSIBLE_ROLE, match.kind)
        assertEquals("roles/adn_api_server/tasks/main.yml", match.file)
    }

    @Test fun `role with no tasks_main falls back to role directory`(@TempDir tmp: Path) {
        tmp.resolve("roles/bare/handlers").createDirectories()

        val index = Indexer.build(tmp)
        val match = index.lookup("bare").singleOrNull()
        assertNotNull(match)
        assertEquals("roles/bare", match.file)
    }

    @Test fun `indexes ansible task files by basename`(@TempDir tmp: Path) {
        val path = tmp.resolve("roles/r/tasks/schema-check.yml")
        path.parent.createDirectories()
        path.writeText("# tasks\n")

        val index = Indexer.build(tmp)
        // schema-check is reachable via `import_tasks: schema-check.yml`
        val match = index.lookup("schema-check").firstOrNull { it.kind == SymbolKind.ANSIBLE_TASKS }
        assertNotNull(match)
        assertEquals("roles/r/tasks/schema-check.yml", match.file)
    }

    @Test fun `indexes templates by filename and basename`(@TempDir tmp: Path) {
        val path = tmp.resolve("roles/r/templates/api.conf.j2")
        path.parent.createDirectories()
        path.writeText("{{ foo }}\n")

        val index = Indexer.build(tmp)
        assertEquals(1, index.lookup("api.conf.j2").size)
        // basename without extension
        val byBase = index.lookup("api.conf").firstOrNull { it.kind == SymbolKind.ANSIBLE_TEMPLATE }
        assertNotNull(byBase)
    }

    @Test fun `indexes group_vars top-level keys with line numbers`(@TempDir tmp: Path) {
        val path = tmp.resolve("group_vars/all.yml")
        path.parent.createDirectories()
        path.writeText(
            """
            ---
            deploy_AZ: active
            # a comment
            batch_size: 1
            nested:
              ignored: yes
            """.trimIndent() + "\n"
        )

        val index = Indexer.build(tmp)
        val az = index.lookup("deploy_AZ").singleOrNull()
        assertNotNull(az)
        assertEquals(SymbolKind.ANSIBLE_VAR, az.kind)
        assertEquals(2, az.line) // line numbers are 1-based; "---" is line 1
        assertEquals(1, index.lookup("batch_size").size)
        // nested keys aren't top-level — should not be picked up
        assertTrue(index.lookup("ignored").isEmpty())
    }

    @Test fun `indexes set_fact block keys`(@TempDir tmp: Path) {
        val path = tmp.resolve("roles/r/tasks/main.yml")
        path.parent.createDirectories()
        path.writeText(
            """
            - name: set things
              set_fact:
                build_root_dir: "/data/foo"
                new_build_dir: "/data/bar"
            - name: next task
              debug: msg=hi
            """.trimIndent() + "\n"
        )

        val index = Indexer.build(tmp)
        assertEquals(1, index.lookup("build_root_dir").count { it.kind == SymbolKind.ANSIBLE_VAR })
        assertEquals(1, index.lookup("new_build_dir").count { it.kind == SymbolKind.ANSIBLE_VAR })
    }

    @Test fun `indexes typescript exports`(@TempDir tmp: Path) {
        val path = tmp.resolve("src/app/foo.ts")
        path.parent.createDirectories()
        path.writeText(
            """
            const NotExported = 1;
            export class MyService {}
            export function helper() {}
            export const KEY = 'x';
            """.trimIndent() + "\n"
        )

        val index = Indexer.build(tmp)
        assertEquals(SymbolKind.TS_SYMBOL, index.lookup("MyService").single().kind)
        assertEquals(1, index.lookup("helper").size)
        assertEquals(1, index.lookup("KEY").size)
        assertTrue(index.lookup("NotExported").isEmpty())
    }

    @Test fun `ignores node_modules and build dirs`(@TempDir tmp: Path) {
        val ignored = tmp.resolve("node_modules/pkg/index.ts")
        ignored.parent.createDirectories()
        ignored.writeText("export class Garbage {}\n")
        val wanted = tmp.resolve("src/a.ts")
        wanted.parent.createDirectories()
        wanted.writeText("export class Wanted {}\n")

        val index = Indexer.build(tmp)
        assertTrue(index.lookup("Garbage").isEmpty())
        assertEquals(1, index.lookup("Wanted").size)
    }

    @Test fun `round-trips through tsv on disk`(@TempDir tmp: Path) {
        val src = tmp.resolve("group_vars/all.yml")
        src.parent.createDirectories()
        src.writeText("foo: bar\n")

        val built = Indexer.build(tmp)
        val tsv = tmp.resolve("index.tsv")
        SymbolIndex.save(tsv, built)
        val loaded = SymbolIndex.load(tsv)
        assertEquals(built.all().toSet(), loaded.all().toSet())
    }

    @Test fun `wordAt picks the word straddling the cursor`() {
        val text = "import_tasks: foo-bar.yml"
        // cursor in the middle of "foo-bar"
        val offset = text.indexOf("foo-bar") + 2
        assertEquals("foo-bar", JumpResolver.wordAt(text, offset))
    }

    @Test fun `wordAt returns null in whitespace`() {
        assertNull(JumpResolver.wordAt("  ", 1))
    }

    // Fixed epoch-millis timestamps keep these independent of the real clock: PAST < cache < EDIT.
    private val past = 1_000_000_000L
    private val cacheStamp = 2_000_000_000L
    private val future = 3_000_000_000L

    @Test fun `isStale is false when nothing changed since the cache`(@TempDir tmp: Path) {
        tmp.resolve("a.kt").writeText("val a = 1\n")
        val b = tmp.resolve("sub/b.kt"); b.parent.createDirectories(); b.writeText("val b = 2\n")
        tmp.toFile().walkTopDown().forEach { it.setLastModified(past) }
        assertFalse(Indexer.isStale(tmp, since = cacheStamp, cachedFileCount = 2))
    }

    @Test fun `isStale is true after a file is edited`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.kt"); a.writeText("x\n")
        tmp.toFile().walkTopDown().forEach { it.setLastModified(past) }
        a.toFile().setLastModified(future) // edited after the cache was written
        assertTrue(Indexer.isStale(tmp, since = cacheStamp, cachedFileCount = 1))
    }

    @Test fun `isStale is true when the file count grew`(@TempDir tmp: Path) {
        tmp.resolve("a.kt").writeText("x\n")
        tmp.resolve("b.kt").writeText("y\n")
        tmp.toFile().walkTopDown().forEach { it.setLastModified(past) }
        // The cache only knew about one file; a second appeared without bumping mtimes.
        assertTrue(Indexer.isStale(tmp, since = cacheStamp, cachedFileCount = 1))
    }

    @Test fun `isStale is true when a new file appears after the cache`(@TempDir tmp: Path) {
        // The double-shift bug: a file added while nop stays open (fresh mtime, grown count) must
        // read as stale so the next refresh rebuilds the file index and the file becomes findable.
        val existing = tmp.resolve("src/a.ts"); existing.parent.createDirectories(); existing.writeText("export const a = 1\n")
        tmp.toFile().walkTopDown().forEach { it.setLastModified(past) }
        val added = tmp.resolve("src/text.ts"); added.writeText("export const t = 2\n")
        added.toFile().setLastModified(future)
        assertTrue(Indexer.isStale(tmp, since = cacheStamp, cachedFileCount = 1))
    }

    @Test fun `isStale ignores churn inside ignored dirs`(@TempDir tmp: Path) {
        tmp.resolve("a.kt").writeText("x\n")
        val junk = tmp.resolve("node_modules/pkg/index.js")
        junk.parent.createDirectories(); junk.writeText("garbage\n")
        tmp.toFile().walkTopDown().forEach { it.setLastModified(past) }
        // A freshly-touched file under node_modules must not count as a project change.
        junk.toFile().setLastModified(future)
        junk.parent.toFile().setLastModified(future)
        assertFalse(Indexer.isStale(tmp, since = cacheStamp, cachedFileCount = 1))
    }
}
