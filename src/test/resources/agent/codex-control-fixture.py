#!/usr/bin/env python3
"""Offline real-TUI fixture. The caller supplies a Codex binary and owns the scratch home."""
import http.server
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import threading
import time

binary, directory, launch_file = sys.argv[1:]
root = Path(directory)
home = root / "home"
work = root / "work"
(home / ".codex").mkdir(parents=True)
work.mkdir()
requests = []


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"models":[]}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests.append(body)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def emit(kind, **data):
            event = {"type": kind, **data}
            self.wfile.write(("event: " + kind + "\ndata: " + json.dumps(event) + "\n\n").encode())
            self.wfile.flush()

        message = {"type": "message", "id": "msg-fixture", "role": "assistant",
                   "content": [{"type": "output_text", "text": "Fixture received the message.", "annotations": []}]}
        emit("response.created", response={"id": "resp-fixture", "status": "in_progress"})
        emit("response.output_item.added", output_index=0, item={**message, "content": []})
        emit("response.output_text.delta", item_id="msg-fixture", output_index=0,
             content_index=0, delta="Fixture received the message.")
        emit("response.output_item.done", output_index=0, item=message)
        emit("response.completed", response={"id": "resp-fixture", "status": "completed", "output": [message],
             "usage": {"input_tokens": 20, "output_tokens": 6, "total_tokens": 26}})


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
(home / ".codex/config.toml").write_text(
    'model = "test-orchid"\nmodel_provider = "fixture"\nmodel_context_window = 64000\n'
    'model_auto_compact_token_limit = 50000\n[model_providers.fixture]\nname = "Offline fixture"\n'
    f'base_url = "http://127.0.0.1:{server.server_port}/v1"\nwire_api = "responses"\n'
    'env_key = "NOP_TEST_KEY"\n[projects.' + json.dumps(str(work)) + ']\ntrust_level = "trusted"\n')
env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
env.update(HOME=str(home), CODEX_HOME=str(home / ".codex"), NOP_TEST_KEY="offline-placeholder", TERM="xterm-256color")
env["XDG_RUNTIME_DIR"] = str(root / "runtime")
Path(env["XDG_RUNTIME_DIR"]).mkdir(mode=0o700)
tmux = ["tmux", "-S", str(root / "tmux.sock")]


def capture():
    return subprocess.check_output(tmux + ["capture-pane", "-p"], env=env).decode()


def wait_for(predicate, timeout=60):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(0.1)
    raise RuntimeError("TUI fixture timed out: " + capture())


def keys(*args):
    subprocess.run(tmux + ["send-keys", *args], env=env, check=True)


def send(text):
    keys("-l", text)
    time.sleep(0.35)
    keys("Enter")


try:
    launch = json.loads(Path(launch_file).read_text())
    env.update(launch["env"])
    socket = env["NOP_CODEX_CONTROL_SOCKET"]
    command = launch["argv"]
    command = ["env", *(key + "=" + env[key] for key in ("HOME", "CODEX_HOME", "NOP_TEST_KEY", "XDG_RUNTIME_DIR")), *command]
    subprocess.run(tmux + ["new-session", "-d", "-x", "140", "-y", "45", shlex.join(command)],
                   env=env, check=True)
    wait_for(lambda: "test-orchid" in capture())
    if not Path(socket).exists():
        keys("F2")
        time.sleep(0.2)
        keys("Right")
        time.sleep(0.2)
        raise RuntimeError("Codex did not start its control socket: " + capture())
    print(json.dumps({"home": str(home), "socket": socket}), flush=True)
    for line in sys.stdin:
        operation = json.loads(line)["op"]
        if operation == "draft":
            keys("-l", "Keep this unsent draft.")
            wait_for(lambda: "Keep this unsent draft." in capture())
        elif operation == "picker":
            keys("C-u")
            send("/model")
            wait_for(lambda: "Select Model" in capture())
        elif operation == "close_picker":
            keys("Escape")
        elif operation == "quit":
            break
        elif operation != "capture":
            raise RuntimeError("Unknown fixture operation")
        contexts = []
        for rollout in (home / ".codex/sessions").glob("**/*.jsonl"):
            for record in rollout.read_text().splitlines():
                event = json.loads(record)
                if event.get("type") == "turn_context":
                    contexts.append(event["payload"])
        print(json.dumps({"screen": capture(), "requests": requests, "contexts": contexts}), flush=True)
finally:
    subprocess.run(tmux + ["kill-server"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([binary, "app-server", "daemon", "stop"], env=env, timeout=20,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    server.shutdown()
