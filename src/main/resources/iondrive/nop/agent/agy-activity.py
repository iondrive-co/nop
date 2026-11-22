"""nop-activity: reports Antigravity turn start and stop events to nop over its agent socket, so
Antigravity tabs show the same working, waiting and unread-response indicators as Claude Code and
Codex tabs. Written by nop at each start (agent/AntigravityActivityHook.kt); edits here are
overwritten.

Run as PreInvocation ("working") and Stop ("idle") hooks by agy, with the hook's JSON on stdin:

    nop-activity working|idle

Always answers agy with a no-op and exits 0, whatever happens: outside nop, with no socket, or on
any fault of its own, it never blocks or alters a turn.
"""
import json
import os
import socket
import sys

TIMEOUT_S = 1.0


def notify(state):
    sock_path = os.environ.get("NOP_SOCKET")
    ticket = os.environ.get("NOP_AGENT_TICKET")
    if not sock_path or not ticket:
        return
    raw = sys.stdin.read()
    conv = ""
    if raw:
        try:
            payload = json.loads(raw)
            if isinstance(payload, dict):
                c = payload.get("conversationId")
                if isinstance(c, str):
                    conv = c.replace("\n", "").replace("\r", "").strip()
        except ValueError:
            pass
    req = ("%s\nactivity\n%s\n%s\n" % (ticket, state, conv)).encode("utf-8")
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.settimeout(TIMEOUT_S)
        s.connect(sock_path)
        s.sendall(req)
        s.shutdown(socket.SHUT_WR)
        s.recv(256)
    finally:
        s.close()


def main():
    state = sys.argv[1] if len(sys.argv) > 1 else ""
    if state in ("working", "idle"):
        try:
            notify(state)
        except Exception:
            pass
    # Stop expects a decision other than "continue" to let the turn end; PreInvocation takes {}.
    print('{"decision":"allow"}' if state == "idle" else "{}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
