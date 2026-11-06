"""nop-address-guard: stops an agent's tool call that would send one of the user's own email
addresses to another service: in a User-Agent or other header, a URL, a request body, or a
script that does so. Written by nop at each start (agent/AddressGuard.kt); edits here are
overwritten.

Run as a PreToolUse hook by Claude Code, Codex and agy, with the hook's JSON on stdin:

    nop-address-guard claude|codex|agy

It only ever says no (to agy, which has no way to abstain, it says yes otherwise). Anything it
cannot read, or any fault of its own, lets the call through: this is a seatbelt against an agent
leaking an address by habit, not a sandbox.
"""
import json
import os
import re
import sys
import time
import urllib.parse

ADDRESSES = "@@ADDRESSES@@"    # written by nop: the addresses its accounts are signed in as
USER_CONFIG = "@@USER_CONFIG@@"  # the user's own: more addresses, hosts an address may go to, or off
LOG = "@@LOG@@"

EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+")
URL = re.compile(r"(?i)\b(?:https?|wss?|ftp)://[^\s\"'`<>]+")
HOST = re.compile(r"(?i)\b(?:https?|wss?|ftp)://(?:[^@/\s\"'`<>]*@)?([A-Za-z0-9.-]+)")
# Something in the text that talks to the network: a URL, or a client that sends requests.
NETWORK = re.compile(
    r"(?i)://|\b(?:curl|wget|urllib\d?|urlopen|requests|httpx|aiohttp|http\.client|httplib"
    r"|axios|fetch|XMLHttpRequest|smtplib|sendmail|websockets?|ncat|socat|telnet|httpie"
    r"|gh|glab)\b"
)
# What a header or a contact field looks like just before the address.
HEADER = re.compile(
    r"(?i)user[-_ ]?agent|\bua\b|\bfrom\s*[:=]|[\"']from[\"']|\bheaders?\b|-A\s|-H\s"
    r"|--header|--user-agent|x-contact"
)
HEADER_REACH = 160
SCRIPT = re.compile(
    r"(?<![\w/.~-])((?:~|\.{1,2})?/?[\w@%+=,./-]*\.(?:py|sh|bash|zsh|js|mjs|cjs|ts|rb|pl|php))(?![\w.])"
)
MAX_SCRIPTS = 20
MAX_SCRIPT_BYTES = 2 * 1024 * 1024
# The text an edit takes out: removing an address sends nothing.
OLD_TEXT_KEYS = {"old_string", "old_str", "oldText", "TargetContent", "Target"}

SHELL_TOOLS = {"bash", "run_command", "shell", "exec_command", "local_shell", "unified_exec"}
WRITE_TOOLS = {
    "write", "edit", "multiedit", "notebookedit", "write_to_file", "replace_file_content",
    "multi_replace_file_content", "write_file", "edit_file", "create_file",
}
WEB_TOOLS = {"webfetch", "websearch", "webrun", "web_search", "read_url_content", "search_web"}
PATH_KEYS = ("file_path", "notebook_path", "TargetFile", "Path", "path")


def normalize(address):
    """The mailbox an address reaches: no +tag, and for Gmail no dots."""
    local, _, domain = address.strip().lower().rpartition("@")
    if not local or not domain:
        return None
    local = local.split("+", 1)[0]
    if domain in ("gmail.com", "googlemail.com"):
        local, domain = local.replace(".", ""), "gmail.com"
    return local + "@" + domain if local else None


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            value = json.load(f)
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


class Policy:
    def __init__(self):
        own = read_json(ADDRESSES)
        user = read_json(USER_CONFIG)
        self.enabled = user.get("enabled", True) is not False
        listed = [a for a in own.get("addresses", []) + user.get("addresses", []) if isinstance(a, str)]
        self.protected = {n for n in map(normalize, listed) if n}
        self.allow = []
        for rule in user.get("allow", []):
            if not isinstance(rule, dict) or not isinstance(rule.get("address"), str):
                continue
            hosts = [h.lower().strip(".") for h in rule.get("hosts", []) if isinstance(h, str) and h.strip(".")]
            if hosts:
                self.allow.append((rule["address"].strip().lower(), hosts))

    def found(self, text):
        """Each protected address in [text], as written there, with where it starts."""
        hits = []
        for variant in {text, urllib.parse.unquote(text)}:
            for m in EMAIL.finditer(variant):
                if normalize(m.group(0)) in self.protected:
                    hits.append((m.group(0), m.start(), variant))
        return hits

    def allowed(self, address, hosts, strict):
        """
        Whether the user lets [address] go where [hosts] are. A command or a web call names exactly
        where it goes, so every host has to be allowed ([strict]); code names every host it ever
        talks to, so one allowed host is taken as where the address is for. With no host in sight
        an allowed address passes, since the user has allowed it somewhere.
        """
        for allowed, suffixes in self.allow:
            if allowed != address.lower():
                continue
            ok = [any(h == s or h.endswith("." + s) for s in suffixes) for h in hosts]
            if not hosts or (all(ok) if strict else any(ok)):
                return True
        return False


def hosts_in(text):
    return sorted({h.lower().strip(".") for h in HOST.findall(text) if "." in h})


def strings(value, skip=()):
    """Every string inside a tool's arguments, but for the keys in [skip]."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for k, v in value.items():
            if k not in skip:
                yield from strings(v, skip)
    elif isinstance(value, list):
        for v in value:
            yield from strings(v, skip)


def in_url(policy, text):
    return [a for url in URL.findall(text) for a, _, _ in policy.found(url)]


def sent_by_command(policy, command):
    """A shell command sends any address it holds once it talks to the network at all."""
    if not NETWORK.search(command):
        return []
    return [a for a, _, _ in policy.found(command)]


def sent_by_code(policy, text, context=""):
    """
    Code or a file that names an address in a header, or in a URL, and that talks to the network
    (itself, or in [context]: the rest of the file an edit lands in). An address in prose, or in a
    file that never makes a request, is not being sent anywhere.
    """
    sent = in_url(policy, text)
    if NETWORK.search(text) or NETWORK.search(context):
        for address, start, variant in policy.found(text):
            if HEADER.search(variant[max(0, start - HEADER_REACH):start]):
                sent.append(address)
    return sent


def refused(policy, addresses, text, context="", strict=True):
    """The first address the user has not allowed for where [text] (or [context]) sends it."""
    hosts = hosts_in(text) or hosts_in(context)
    for address in addresses:
        if not policy.allowed(address, hosts, strict):
            return address, hosts
    return None


def read_script(path, cwd):
    path = os.path.expanduser(path)
    if not os.path.isabs(path):
        if not cwd:
            return None, None
        path = os.path.join(cwd, path)
    try:
        if not os.path.isfile(path) or os.path.getsize(path) > MAX_SCRIPT_BYTES:
            return None, None
        with open(path, encoding="utf-8", errors="replace") as f:
            return path, f.read()
    except OSError:
        return None, None


def target_file(args, cwd):
    for key in PATH_KEYS:
        value = args.get(key)
        if isinstance(value, str) and value:
            return read_script(value, cwd)[1] or ""
    return ""


def added_lines(patch):
    return "\n".join(line[1:] for line in patch.splitlines() if line.startswith("+") and not line.startswith("+++"))


def check(policy, name, args, cwd):
    """Why this call may not run, or None."""
    if not policy.enabled or not policy.protected or not isinstance(args, dict):
        return None
    tool = name.lower()

    if tool in SHELL_TOOLS:
        command = args.get("command", args.get("CommandLine", args.get("cmd", "")))
        if isinstance(command, list):
            command = " ".join(str(c) for c in command)
        if not isinstance(command, str):
            return None
        hit = refused(policy, sent_by_command(policy, command), command)
        if hit:
            return hit + (None,)
        workdir = args.get("Cwd") if isinstance(args.get("Cwd"), str) else cwd
        for token in SCRIPT.findall(command)[:MAX_SCRIPTS]:
            path, text = read_script(token, workdir)
            if text is None:
                continue
            hit = refused(policy, sent_by_code(policy, text), text, strict=False)
            if hit:
                return hit + (path,)
        return None

    if tool == "apply_patch":
        patch = args.get("command", args.get("input", args.get("patch", "")))
        if not isinstance(patch, str):
            return None
        text = added_lines(patch)
        hit = refused(policy, sent_by_code(policy, text), text, strict=False)
        return hit + (None,) if hit else None

    if tool in WRITE_TOOLS:
        text = "\n".join(strings(args, OLD_TEXT_KEYS))
        context = target_file(args, cwd)
        hit = refused(policy, sent_by_code(policy, text, context), text, context, strict=False)
        return hit + (None,) if hit else None

    if tool in WEB_TOOLS or tool.startswith("browser"):
        # WebFetch's prompt goes to the model reading the page, not to the site.
        text = "\n".join(strings(args, {"prompt"}))
        hit = refused(policy, [a for a, _, _ in policy.found(text)], text)
        return hit + (None,) if hit else None

    # MCP tools and anything else: an address in a URL, or in a call that carries a request.
    text = "\n".join(strings(args))
    sent = in_url(policy, text)
    if "headers" in json.dumps(args) or URL.search(text):
        sent += [a for a in sent_by_code(policy, text) if a not in sent]
        if tool.startswith("mcp"):
            sent += [a for a, _, _ in policy.found(text)]
    hit = refused(policy, sent, text)
    return hit + (None,) if hit else None


def reason(tool, address, hosts, via):
    where = ", ".join(hosts) if hosts else "a remote service"
    through = " (the script %s)" % via if via else ""
    return (
        "nop's address guard stopped this %s call%s: it would send %s, one of the user's own "
        "email addresses, to %s. Never put the user's email address in a User-Agent, a From or "
        "other header, a URL, or a request body, even when a site asks automated clients for "
        "contact details: use a generic client name with no address, or ask the user. Only the "
        "user may let an address go to a host, in %s." % (tool, through, address, where, USER_CONFIG)
    )


def log(cli, tool, verdict, detail, cwd):
    try:
        if os.path.exists(LOG) and os.path.getsize(LOG) > 1024 * 1024:
            os.replace(LOG, LOG + ".1")
        fd = os.open(LOG, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as f:
            stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            f.write("%s %s %s %s %s in %s\n" % (stamp, cli, tool, verdict, detail, cwd or "?"))
    except OSError:
        pass


def main():
    cli = sys.argv[1] if len(sys.argv) > 1 else "claude"
    verdict = None
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        if cli == "agy":
            call = payload.get("toolCall") or {}
            name, args = call.get("name") or "", call.get("args") or {}
            if isinstance(args, str):
                args = json.loads(args)
            paths = payload.get("workspacePaths") or []
            cwd = paths[0] if paths and isinstance(paths[0], str) else None
        else:
            name, args, cwd = payload.get("tool_name") or "", payload.get("tool_input") or {}, payload.get("cwd")
        hit = check(Policy(), str(name), args, cwd)
        if hit:
            address, hosts, via = hit
            verdict = reason(name, address, hosts, via)
            log(cli, name, "denied", "%s to %s%s" % (address, ",".join(hosts) or "?", " via " + via if via else ""), cwd)
    except Exception as e:  # a guard that breaks must not take the agent's tools with it
        log(cli, "?", "error", repr(e)[:300], None)
        verdict = None

    if cli == "agy":
        # agy has no "no opinion": an empty answer is read as a denial, so a call this passes is
        # allowed. nop starts agy with --dangerously-skip-permissions, under which allowing is
        # what would have happened anyway.
        print(json.dumps({"decision": "deny", "reason": verdict} if verdict else {"decision": "allow"}))
    elif verdict:
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": verdict,
            }
        }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
