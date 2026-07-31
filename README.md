# nop

Minimalist cross platform editor and change reviewer built on Jetbrains Compose

Download the latest installer for your platform from the
[releases page](https://github.com/iondrive-co/nop/releases/latest):

<!-- screenshot -->
![Diff view](docs/screenshots/latest-diff.png)
![Workspace preview](docs/screenshots/latest-preview.png)
<!-- screenshot -->

## Shortcuts

- Ctrl click on an element in a file to jump to source.
- Ctrl F to search within the current file
- Ctrl R to find and replace within the current file — the search field splits in half, the query on
  the left and its replacement on the right
- Ctrl Shift F to search across all files
- Shift-Shift to search for file
- F4 from a diff to open the working file behind it, at the line you were reading
- F5 to reload git status and re-read the active tab from disk
- Select a file or directory in the project tree, then:
	- `Delete` — remove it from disk (asks for confirmation first)
	- `H` — open a tab showing its git history
	- `B` — toggle the git blame column in the editor

## Troubleshooting

Check `$XDG_CONFIG_HOME/nop/nop.log` (default `~/.config/nop/nop.log`), and file an issue if necessary.
