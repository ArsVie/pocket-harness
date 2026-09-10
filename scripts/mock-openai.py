#!/usr/bin/env python3
"""OpenAI-compatible mock endpoint for PocketHarness end-to-end runs.

It does two jobs that matter:

  1. It answers `/chat/completions` with a scripted sequence, so the whole loop (prompt assembly →
     tool dispatch → transcript → thread UI) can be exercised on a device without spending real
     tokens.
  2. It *conforms* every request against SPEC §2.2 and records violations. The wire contract is the
     part that a mock can check better than a real provider: which fields are present, in what order,
     and that no vendor-specific ones leaked in.

Run:
    python3 scripts/mock-openai.py --port 8111 --script scripts/mock-scripts/basic.json
    python3 scripts/mock-openai.py --port 8111 --list          # what scripts are available

Endpoints:
    POST /v1/chat/completions, POST /chat/completions   the model
    GET  /_requests                                     every request received, newest last
    GET  /_violations                                   conformance violations only
    POST /_reset                                        clear state and restart the script

Each `/chat/completions` call consumes the next step of the script. A step is either a message:

    {"text": "hi", "reasoning": "thinking...", "tool_calls": [{"name": "bash",
      "arguments": {"command": "ls -la"}}], "finish_reason": "stop"}

…or a failure injection:

    {"http": 429, "body": {"error": {"message": "rate limited"}}}
    {"malformed": true}                 # 200 with a body that parses but has no choices
    {"transport": "close"}              # accept then hang up without a response

Flags: --reasoning-efforts high,max (what the endpoint advertises, for docs), --require-order
(on by default: top-level key order must be model, messages, tools, reasoning_effort).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SPEC_FIELD_ORDER = ["model", "messages", "tools", "reasoning_effort"]
FORBIDDEN_FIELDS = ["stream", "stream_options", "thinking", "reasoning", "n", "logprobs", "logit_bias"]


class State:
    def __init__(self, steps: list[dict]) -> None:
        self.lock = threading.Lock()
        self.steps = steps
        self.index = 0
        self.requests: list[dict] = []
        self.violations: list[dict] = []

    def next_step(self) -> dict:
        with self.lock:
            if self.index < len(self.steps):
                step = self.steps[self.index]
            else:
                step = {"text": "(script exhausted — no more scripted steps)", "finish_reason": "stop"}
            self.index += 1
        return step

    def record(self, record: dict) -> None:
        with self.lock:
            self.requests.append(record)

    def violate(self, kind: str, detail: str) -> None:
        with self.lock:
            self.violations.append({"kind": kind, "detail": detail, "at": time.time()})


def top_level_order(raw: str) -> list[str]:
    """Top-level keys of a JSON object in source order, without a full parse.

    Captures each key at its *opening* quote while at object depth 1, so values — which may contain
    quotes, commas and colons of their own — cannot be mistaken for keys. This is what lets the
    conformance check assert SPEC §2.2's field order on the raw body.
    """
    keys: list[str] = []
    depth = 0
    in_string = False
    escaped = False
    key_start = -1
    expecting_key = False
    for i, ch in enumerate(raw):
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
                if key_start >= 0:
                    keys.append(raw[key_start + 1:i])
                    key_start = -1
                    expecting_key = False
            continue
        if ch == '"':
            if depth == 1 and expecting_key:
                key_start = i
            in_string = True
        elif ch == "{":
            depth += 1
            expecting_key = depth == 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                break
        elif ch == ",":
            expecting_key = depth == 1
        elif ch == ":":
            expecting_key = False
    return keys


def check_conformance(raw: str, parsed: dict, state: State, require_order: bool) -> None:
    order = top_level_order(raw)
    if require_order and order and order != [f for f in SPEC_FIELD_ORDER if f in order]:
        state.violate("field-order", f"top-level order was {order}")

    for field in FORBIDDEN_FIELDS:
        if field in parsed:
            state.violate("forbidden-field", f"{field!r} must not be sent (ADR-003)")

    messages = parsed.get("messages") or []
    if not messages or messages[0].get("role") != "system":
        state.violate("prompt-shape", "first message is not the system persona")
    for message in messages:
        role = message.get("role")
        if role == "tool" and "tool_call_id" not in message:
            state.violate("tool-call-id", "a tool message is missing tool_call_id")
        if role == "assistant" and message.get("reasoning_content") and not message.get("tool_calls"):
            state.violate("reasoning-replay",
                          "reasoning replayed on a turn without tool calls (SPEC §2.5)")
        for forbidden in ("name", "function_call", "audio", "refusal"):
            if forbidden in message:
                state.violate("forbidden-message-field", f"{forbidden!r} in a message")


class Handler(BaseHTTPRequestHandler):
    state: State
    require_order: bool = True
    reasoning_efforts: list[str] = []

    def log_message(self, fmt: str, *args) -> None:  # quieter default
        sys.stderr.write("mock-openai: " + fmt % args + "\n")

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.startswith("/_violations"):
            self._json(200, {"violations": self.state.violations})
        elif self.path.startswith("/_requests"):
            self._json(200, {"requests": self.state.requests})
        elif self.path.startswith("/_health"):
            self._json(200, {"ok": True, "steps": len(self.state.steps), "served": self.state.index})
        else:
            self._json(404, {"error": {"message": f"no such path {self.path}"}})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.startswith("/_reset"):
            with self.state.lock:
                self.state.index = 0
                self.state.requests = []
                self.state.violations = []
            self._json(200, {"reset": True})
            return

        if not self.path.endswith("/chat/completions"):
            self._json(404, {"error": {"message": f"no such path {self.path}"}})
            return

        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        auth = self.headers.get("Authorization") or ""
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError as exc:
            self.state.record({"raw": raw, "parse_error": str(exc)})
            self._json(400, {"error": {"message": f"unparseable request: {exc}"}})
            return

        self.state.record({
            "auth": "bearer" if auth.lower().startswith("bearer ") else auth or "none",
            "path": self.path,
            "raw": raw,
            "parsed": parsed,
        })
        check_conformance(raw, parsed, self.state, self.require_order)

        step = self.state.next_step()
        if "transport" in step:
            self.close_connection = True
            return
        if "http" in step:
            self._json(int(step["http"]), step.get("body") or {"error": {"message": "injected"}})
            return
        if step.get("malformed"):
            self._json(200, {"id": "chatcmpl-mock", "object": "chat.completion"})
            return

        tool_calls = []
        for call in step.get("tool_calls") or []:
            arguments = call.get("arguments")
            tool_calls.append({
                "id": call.get("id") or f"call_{uuid.uuid4().hex[:8]}",
                "type": "function",
                "function": {
                    "name": call["name"],
                    "arguments": arguments if isinstance(arguments, str) else json.dumps(arguments),
                },
            })

        message: dict = {"role": "assistant", "content": step.get("text")}
        if step.get("reasoning"):
            message["reasoning_content"] = step["reasoning"]
        if tool_calls:
            message["tool_calls"] = tool_calls

        prompt_tokens = sum(len(json.dumps(m)) for m in parsed.get("messages") or []) // 4
        self._json(200, {
            "id": f"chatcmpl-mock-{self.state.index}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": parsed.get("model", "mock"),
            "choices": [{
                "index": 0,
                "message": message,
                "finish_reason": step.get("finish_reason", "stop"),
            }],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": len(str(step.get("text") or "")) // 4 + 1,
                "total_tokens": prompt_tokens + 1,
                "prompt_tokens_details": {"cached_tokens": 0},
            },
        })


def load_script(path: Path | None) -> list[dict]:
    if path is None:
        return []
    data = json.loads(path.read_text())
    if isinstance(data, dict):
        return data.get("steps", [])
    return data


def selftest() -> int:
    """Assertions on the two things a mock can get wrong quietly: key order, and conformance."""
    cases = [
        ('{"model":"m","messages":[],"reasoning_effort":"high"}',
         ["model", "messages", "reasoning_effort"]),
        ('{"stream":true,"reasoning_effort":"max","model":"m","messages":[{"role":"user","content":"a, b: c"}]}',
         ["stream", "reasoning_effort", "model", "messages"]),
        ('{"model":"m","messages":[{"role":"tool","tool_call_id":"c1","content":"{\\"a\\": 1}"}],"tools":[]}',
         ["model", "messages", "tools"]),
        ('{}', []),
        ('{"a":1, "b": {"nested":"x, y"}, "c": 2}', ["a", "b", "c"]),
    ]
    failures = 0
    for raw, expected in cases:
        actual = top_level_order(raw)
        status = "ok  " if actual == expected else "FAIL"
        if actual != expected:
            failures += 1
        print(f"{status} order {actual} (expected {expected})")

    state = State([])
    check_conformance('{"stream":true,"model":"m","messages":[{"role":"user","content":"x"}]}',
                      {"stream": True, "model": "m", "messages": [{"role": "user", "content": "x"}]},
                      state, require_order=True)
    kinds = sorted(v["kind"] for v in state.violations)
    # field-order is expected too: that body lists stream before model, so the order rule fires.
    expected_kinds = ["field-order", "forbidden-field", "prompt-shape"]
    status = "ok  " if kinds == expected_kinds else "FAIL"
    if kinds != expected_kinds:
        failures += 1
    print(f"{status} violations {kinds} (expected {expected_kinds})")

    state = State([])
    check_conformance(
        '{"model":"m","messages":[{"role":"system","content":"p"},'
        '{"role":"assistant","content":"x","reasoning_content":"r","tool_calls":[{"id":"1"}]},'
        '{"role":"tool","tool_call_id":"1","content":"out"}],"reasoning_effort":"high"}',
        {"model": "m", "reasoning_effort": "high", "messages": [
            {"role": "system", "content": "p"},
            {"role": "assistant", "content": "x", "reasoning_content": "r", "tool_calls": [{"id": "1"}]},
            {"role": "tool", "tool_call_id": "1", "content": "out"},
        ]},
        state, require_order=True)
    status = "ok  " if not state.violations else "FAIL"
    if state.violations:
        failures += 1
        print(f"      unexpected: {state.violations}")
    print(f"{status} a conforming request raises no violations")

    print("mock-openai selftest: " + ("all passed" if not failures else f"{failures} failed"))
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8111)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--script", type=Path, default=None)
    parser.add_argument("--list", action="store_true", help="list scripts under scripts/mock-scripts")
    parser.add_argument("--selftest", action="store_true", help="check the parser and conformance rules")
    parser.add_argument("--reasoning-efforts", default="high,max")
    parser.add_argument("--allow-any-order", action="store_true",
                        help="do not enforce SPEC §2.2 top-level field order")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    scripts_dir = Path(__file__).resolve().parent / "mock-scripts"
    if args.list:
        for path in sorted(scripts_dir.glob("*.json")):
            print(path.name)
        return 0

    steps = load_script(args.script)
    Handler.state = State(steps)
    Handler.require_order = not args.allow_any_order
    Handler.reasoning_efforts = args.reasoning_efforts.split(",")

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"mock-openai listening on http://{args.host}:{args.port} "
          f"({len(steps)} scripted step(s), order enforcement {'on' if Handler.require_order else 'off'})")
    print("  POST /v1/chat/completions   GET /_requests   GET /_violations   POST /_reset")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping")
    return 0


if __name__ == "__main__":
    sys.exit(main())
