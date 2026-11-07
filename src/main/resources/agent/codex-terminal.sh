#!/bin/bash
# One private backend and its TUI share this terminal's lifetime and process group.
main() {
    umask 077
    local socket="$1" binary="$2" temporary="$3"
    shift 3
    local server=("$binary" app-server --listen "unix://$socket")
    while [[ "$1" != --nop-terminal-- ]]; do
        server+=("$1")
        shift
    done
    shift
    local server_pid="" terminal_pid=""
    cleanup() {
        [[ -z "$terminal_pid" ]] || kill "$terminal_pid" 2>/dev/null || true
        [[ -z "$server_pid" ]] || kill "$server_pid" 2>/dev/null || true
        [[ -z "$terminal_pid" ]] || wait "$terminal_pid" 2>/dev/null || true
        [[ -z "$server_pid" ]] || wait "$server_pid" 2>/dev/null || true
        rm -f -- "$socket" "$socket.log"
        [[ -z "$temporary" ]] || rmdir -- "$temporary" 2>/dev/null || true
    }
    trap cleanup EXIT
    trap 'exit 129' HUP
    trap 'exit 143' TERM
    "${server[@]}" </dev/null >"$socket.log" 2>&1 &
    server_pid=$!
    local attempt
    for ((attempt=0; attempt<400; attempt++)); do
        if [[ -S "$socket" ]]; then break; fi
        if ! kill -0 "$server_pid" 2>/dev/null; then
            tail -40 "$socket.log" >&2
            exit 1
        fi
        sleep 0.05
    done
    if [[ ! -S "$socket" ]]; then
        printf '%s\n' 'Codex did not open its local control socket.' >&2
        exit 1
    fi
    "$binary" "$@" <&0 &
    terminal_pid=$!
    wait "$terminal_pid"
    local result=$?
    terminal_pid=""
    exit "$result"
}
main "$@"
