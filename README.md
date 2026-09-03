# nop

Minimalist cross platform editor and change reviewer built on Jetbrains Compose.

Download the latest installer for your platform from the
[releases page](https://github.com/iondrive-co/nop/releases/latest):

<!-- screenshot -->
![Diff view](docs/screenshots/latest-diff.png)
![Workspace preview](docs/screenshots/latest-preview.png)
<!-- screenshot -->

## Shortcuts

- Ctrl click on an element in a file to jump to source. In a Java file this reaches methods and
  fields, not just types — the index is built from a real parse.
- Ctrl F to search within the current file
- Ctrl R to find and replace within the current file — the search field splits in half, the query on
  the left and its replacement on the right
- Ctrl Shift F to search across all files
- Shift-Shift to search for file
- F4 from a diff to open the working file behind it, at the line you were reading
- F5 to reload git status and re-read the active tab from disk
- In a Java file:
	- syntax errors are underlined as you type, named in a bar above the file, and marked in the
	  scrollbar lane so you can see one that is off screen
	- `Alt F7` — list every usage of the name under the caret in the Usages tab. Locals, parameters,
	  private members and types are found exactly: a file that imports a *different* `Widget` is not
	  in the list. A public method or field can't be pinned down without the project's classpath, so
	  those results say so above the rows
	- `Shift F6` — rename it everywhere it is used. The dialog says how many places in how many
	  files change before you commit to it, renames the file alongside a public class, and refuses
	  a name Java won't take or one already used where the new one would land. Every rewritten file
	  goes into local history first, so a rename you regret is recoverable from inside nop
- Select a file or directory in the project tree, then:
	- `Delete` — remove it from disk (asks for confirmation first)
	- `Ctrl C` — copy it (and the rest of the selection) to the clipboard
	- `Ctrl V` — paste the clipboard into the selected directory, or beside the selected file.
	  Pasting into the entry's own directory names the copy `Main (copy).kt`
	- `F2` — rename it in place; an open editor tab follows the file to its new name
	- `H` — open a tab showing its git history
	- `B` — toggle the git blame column in the editor
- In a git history tab, right click a commit to revert it — its changes are backed out of the
  files on disk and left there uncommitted, so you can look them over before committing. HEAD
  doesn't move, and work done after the reverted commit is kept.

## Troubleshooting

Check `$XDG_CONFIG_HOME/nop/nop.log` (default `~/.config/nop/nop.log`), and file an issue if necessary.
