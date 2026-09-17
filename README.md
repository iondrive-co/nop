# nop

Cross platform editor and change reviewer with built in multi-agent account management.

Download the latest installer for your platform from the
[releases page](https://github.com/iondrive-co/nop/releases/latest):

<!-- screenshot -->

### Run coding agents beside your code

Claude Code, Codex and Antigravity sessions run in tabs next to the editor, each on whichever of
your accounts you choose. The strip along the bottom shows every account's session and weekly
usage.

![A Claude Code session, with every account's usage along the bottom](docs/screenshots/latest-agents.png)

### Review what changed

Every change opens as a side-by-side diff, with a revert on each hunk and a stripe marking where
the rest of the file's changes are.

![A side-by-side diff](docs/screenshots/latest-diff.png)

### Manage all your accounts in one place

Set each account's model and thinking level, sign it in or out, and choose which account takes
over its work when it runs out.

![The agent accounts settings](docs/screenshots/latest-accounts.png)

### Keep projects in tabs

Open several projects in one window, each with its own file tree and editor tabs.

![Several projects open as tabs](docs/screenshots/latest-preview.png)

<!-- screenshot -->

## Shortcuts

- Ctrl click on an element to jump to source
- Ctrl F search within current file
- Ctrl R to find and replace within current file
- Ctrl Shift F to search across all files
- Shift-Shift to search for file
- F4 from a diff to open the file
- F5 reload git status / re-read active tab from disk
- `Alt F7` list usages
- `Shift F6` rename

When a file or directory is selected:
	- `Delete` — remove it from disk
	- `Ctrl C` — copy to clipboard
	- `Ctrl V` — paste the clipboard into the selected directory, or beside the selected file.
	- `F2` — rename
	- `H` — open a git history tab
	- `B` — toggle the git blame column in the editor

In a terminal, a launcher run or an agent session:
	- `Shift Enter` — insert a newline
	- `Ctrl C` — copy selection or interupt run if nothing selected
	- `Ctrl Shift V` or `Shift Insert` — paste

## Troubleshooting

Check `$XDG_CONFIG_HOME/nop/nop.log` (default `~/.config/nop/nop.log`), and file an issue if necessary.
