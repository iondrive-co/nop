package iondrive.nop.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Disk format for restoring a project's tab strip on the next launch. We only persist tab kinds
 * that survive across runs:
 *
 *   * [Tab.FileView] — refers to a stable absolute path
 *   * [Tab.History]  — same; the repo root is implicit (we're inside a single project)
 *
 * Diff tabs depend on the live [iondrive.nop.git.FileChange] blob which is recomputed each
 * launch, and Terminal tabs wrap a running PTY process — neither can be meaningfully restored,
 * so both are dropped at save time.
 *
 * Stored as TSV under the project's data dir: `kind<TAB>path<TAB>selected?`. One tab per line;
 * unparseable lines are skipped so a partial corruption doesn't wipe the strip.
 */
data class SavedTab(val kind: String, val path: String, val selected: Boolean)

object TabsPersistence {
    private const val KIND_FILE = "file"
    private const val KIND_HISTORY = "history"

    fun save(target: Path, tabs: List<Tab>, selectedId: String?) {
        val rows = tabs.mapNotNull { tab ->
            val (kind, file) = when (tab) {
                is Tab.FileView -> KIND_FILE to tab.file
                is Tab.History -> KIND_HISTORY to tab.file
                is Tab.Diff, is Tab.CommitDiff, is Tab.Terminal -> return@mapNotNull null
            }
            val selected = tab.id == selectedId
            "$kind\t${file.absolutePath}\t${if (selected) "1" else "0"}"
        }
        runCatching {
            Files.createDirectories(target.parent)
            // Empty file on no persistable tabs — easier than tracking "did we ever save" for
            // the load side. An empty file loads as an empty list.
            Files.writeString(target, rows.joinToString("\n"))
        }
    }

    fun load(source: Path): List<SavedTab> {
        if (!Files.isRegularFile(source)) return emptyList()
        val text = runCatching { Files.readString(source) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SavedTab>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val kind = parts[0]
            if (kind != KIND_FILE && kind != KIND_HISTORY) continue
            val path = parts[1]
            val selected = parts.getOrNull(2) == "1"
            out += SavedTab(kind, path, selected)
        }
        return out
    }

    /**
     * Rebuilds a [TabsState] from the on-disk snapshot, filtering out anything that doesn't
     * exist (or is no longer a file) so a renamed/deleted file doesn't reopen as a broken tab.
     * Falls back to whichever tab was selected when saving; if that one didn't survive the
     * filter, leaves the last remaining tab selected (matching [TabsState.open]'s contract).
     */
    fun restore(
        state: TabsState,
        saved: List<SavedTab>,
        repoRoot: File?,
    ) {
        var preferredSelectedId: String? = null
        for (s in saved) {
            val file = File(s.path)
            if (!file.isFile && s.kind == KIND_FILE) continue
            // History on a directory is valid (e.g. log for an entire role), so allow either
            // file or directory existence.
            if (s.kind == KIND_HISTORY && !file.exists()) continue
            val tab: Tab = when (s.kind) {
                KIND_FILE -> Tab.FileView(file)
                KIND_HISTORY -> if (repoRoot != null) Tab.History(file, repoRoot) else continue
                else -> continue
            }
            state.open(tab)
            if (s.selected) preferredSelectedId = tab.id
        }
        if (preferredSelectedId != null) state.select(preferredSelectedId)
    }
}
