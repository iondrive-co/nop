# nop

Cross platform editor and change reviewer with built in multi-agent account management.

Download the latest installer for your platform from the
[releases page](https://github.com/iondrive-co/nop/releases/latest):

<!-- screenshot -->
![Diff view](docs/screenshots/latest-diff.png)
![Workspace preview](docs/screenshots/latest-preview.png)
![Agent accounts and usage](docs/screenshots/latest-agents.png)
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
- `Alt F7` list usages
- `Shift F6` rename
- When a file or directory is selected:
	- `Delete` — remove it from disk
	- `Ctrl C` — copy to clipboard
	- `Ctrl V` — paste the clipboard into the selected directory, or beside the selected file.
	- `F2` — rename
	- `H` — open a git history tab
	- `B` — toggle the git blame column in the editor
- In a terminal, a launcher run or an agent session:
	- `Shift Enter` — insert a newline
	- `Ctrl C` — copy selection or interupt run if nothing selected
	- `Ctrl Shift V` or `Shift Insert` — paste

## Troubleshooting

Check `$XDG_CONFIG_HOME/nop/nop.log` (default `~/.config/nop/nop.log`), and file an issue if necessary.
