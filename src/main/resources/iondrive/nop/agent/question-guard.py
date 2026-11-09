"""nop-question-guard: sends a Claude Code question back to the model, once per prompt, when the
reply that carries it holds no text the user can see. Written by nop at each start
(agent/QuestionGuard.kt); edits here are overwritten.

Run by Claude Code as a PreToolUse hook on AskUserQuestion, with the hook's JSON on stdin.

Why: the model sometimes writes what the user must read before answering (a commit message, a
summary, what it found) into its hidden reasoning, believing it wrote it as text, and then asks
"Ready to commit?" over nothing. The CLI keeps no text of that reasoning, and the model cannot
tell the two apart in its own context. The transcript can: if the reply that carries the
question has no text block, nothing in it reached the user's screen. The first such question in
a prompt is denied with a reason that says so; the model then writes the text out, or asks again
as it was, and that second question goes through, so the guard never holds a question twice in
one prompt and costs one extra model call at most.

It only ever says no. Anything it cannot read, or any fault of its own, lets the question
through, and so does a headless run (`claude -p`), which has no screen to miss anything on.
"""
import json
import os
import sys
import time

LOG = "@@LOG@@"
MARKER = "nop's question guard"
# How long to wait for the CLI to write the reply that carries the question. It writes it while
# the hook runs, about 0.1 s in (Claude Code 2.1.319); a reply never seen lets the question through.
WAIT_S = 3.0
POLL_S = 0.05

REASON = (
    MARKER + " held this question back: this reply has no text, only reasoning the user never "
    "sees, so anything you meant them to read first (a commit message, a summary) is not on their "
    "screen. Write it out as text, then ask again; if the question stands on its own, ask it "
    "again as it is."
)


class Transcript:
    """The transcript's lines, read again from where the last read stopped."""

    def __init__(self, path):
        self.path = path
        self.offset = 0
        self.partial = b""
        self.lines = []

    def read(self):
        try:
            with open(self.path, "rb") as f:
                f.seek(self.offset)
                data = f.read()
        except OSError:
            return
        self.offset += len(data)
        *complete, self.partial = (self.partial + data).split(b"\n")
        for raw in complete:
            try:
                self.lines.append(json.loads(raw))
            except ValueError:
                pass


def blocks(line):
    content = (line.get("message") or {}).get("content")
    return content if isinstance(content, list) else []


def find_question(lines, tool_use_id):
    """The index of the line holding the question's tool_use, or None."""
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].get("type") == "assistant" and any(
            b.get("type") == "tool_use" and b.get("id") == tool_use_id for b in blocks(lines[i])
        ):
            return i
    return None


def reply_has_text(lines, at):
    """True when the API message the question came in holds a non-blank text block. The CLI
    writes each block of a message as a line of its own, all with the message's id."""
    message_id = (lines[at].get("message") or {}).get("id")
    reply = [ln for ln in lines if ln.get("type") == "assistant" and (ln.get("message") or {}).get("id") == message_id] \
        if message_id else [lines[at]]
    return any(b.get("type") == "text" and str(b.get("text") or "").strip() for ln in reply for b in blocks(ln))


def is_prompt(line):
    """A line where the user typed something, as opposed to a tool's result or the CLI's own."""
    if line.get("type") != "user" or line.get("isMeta"):
        return False
    content = (line.get("message") or {}).get("content")
    if isinstance(content, str):
        return True
    return isinstance(content, list) and any(b.get("type") == "text" for b in content) \
        and not any(b.get("type") == "tool_result" for b in content)


def turn_start(lines, at, prompt_id):
    """Where this prompt's turn begins: its first line, or failing an id, the last prompt before."""
    if prompt_id:
        for i, line in enumerate(lines[:at]):
            if line.get("type") == "user" and line.get("promptId") == prompt_id:
                return i
    for i in range(at - 1, -1, -1):
        if is_prompt(lines[i]):
            return i
    return 0


def held_before(lines, start, at):
    """True when the guard already held a question back in lines[start:at]."""
    for line in lines[start:at]:
        if line.get("type") != "user":
            continue
        for b in blocks(line):
            if b.get("type") == "tool_result" and MARKER in json.dumps(b.get("content"), ensure_ascii=False):
                return True
    return False


def verdict(payload):
    """(the reason to deny, or None, and a word for the log)."""
    path, tool_use_id = payload.get("transcript_path"), payload.get("tool_use_id")
    if not path or not tool_use_id:
        return None, "no transcript"
    transcript = Transcript(path)
    deadline = time.monotonic() + WAIT_S
    while True:
        transcript.read()
        at = find_question(transcript.lines, tool_use_id)
        if at is not None or time.monotonic() >= deadline:
            break
        time.sleep(POLL_S)
    lines = transcript.lines
    if at is None:
        return None, "reply not seen"
    if lines[at].get("entrypoint") not in (None, "cli"):
        return None, "headless"
    if reply_has_text(lines, at):
        return None, None
    if held_before(lines, turn_start(lines, at, payload.get("prompt_id")), at):
        return None, "asked again"
    return REASON, "held"


def log(verdict_word, payload):
    try:
        os.makedirs(os.path.dirname(LOG), 0o700, exist_ok=True)
        if os.path.exists(LOG) and os.path.getsize(LOG) > 1024 * 1024:
            os.replace(LOG, LOG + ".1")
        fd = os.open(LOG, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as f:
            stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            f.write("%s %s session %s in %s\n" % (stamp, verdict_word, payload.get("session_id") or "?", payload.get("cwd") or "?"))
    except OSError:
        pass


def main():
    payload = {}
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        reason, word = verdict(payload)
    except Exception as e:  # a guard that breaks must not take the agent's questions with it
        reason, word = None, "error " + repr(e)[:300]
    if word:
        log(word, payload)
    if reason:
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            }
        }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
