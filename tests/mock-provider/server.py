"""Deterministic provider-shaped endpoints for isolated integration tests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(min(length, 262144))
        providers = {
            "/v1/chat/completions": ("nvidia", "openai/gpt-oss-20b"),
            "/openrouter/v1/chat/completions": ("openrouter", "openai/gpt-4.1-mini"),
            "/openai/v1/chat/completions": ("openai", "gpt-4.1-mini"),
            "/v1/messages": ("anthropic", "claude-haiku-4-5"),
        }
        provider, model = providers.get(self.path, ("", ""))
        authenticated = (self.headers.get("x-api-key") == "test-anthropic-key" and
                         self.headers.get("anthropic-version") == "2023-06-01" and
                         not self.headers.get("Authorization")) if provider == "anthropic" else (
                             self.headers.get("Authorization") == f"Bearer test-{provider}-key")
        if not provider or not authenticated:
            self.send_response(401)
            self.end_headers()
            return
        body = json.loads(raw)
        allowed = [model, model + ":free" if provider == "openrouter" else model]
        if provider == "openai":
            allowed.append("gpt-4.1-mini-memory-test")
        if body.get("model") not in allowed:
            self.send_response(404)
            self.end_headers()
            return
        valid = body.get("stream") is False
        if provider == "nvidia":
            valid = valid and body.get("reasoning_effort") == "low"
        else:
            valid = valid and "reasoning_effort" not in body
        if provider == "openai":
            valid = valid and "max_completion_tokens" in body and "max_tokens" not in body
        else:
            valid = valid and "max_tokens" in body and "max_completion_tokens" not in body
        if provider == "anthropic":
            valid = valid and bool(body.get("system")) and all(m["role"] != "system" for m in body["messages"])
        if not valid:
            self.send_response(422)
            self.end_headers()
            return
        user = body["messages"][-1]["content"]
        if user == "Connection check":
            content = "OK"
        elif "CONVERSATION_SUMMARY" in (body.get("system", "") or body["messages"][0].get("content", "")):
            context = json.loads(user)
            conversation = context["conversation"]
            prior = {r["content"] for r in conversation.get("previousReflections", [])}
            approved = []
            for reflection in conversation.get("reflections", []):
                if reflection["content"].startswith("I now prefer") and reflection["content"] not in prior:
                    approved.append(reflection["id"])
                    prior.add(reflection["content"])
            content = json.dumps({"summary": conversation.get("previousSummary") or
                                  f"{context['participant']} discussed gardening and compost at the library. The exchange was friendly; interest was expressed, but no definite meeting was agreed.",
                                  "approvedReflections": approved})
        else:
            context = json.loads(user)
            event = next((e["id"] for e in context["recentEvents"] if e["kind"] == "arrival"), None)
            content = json.dumps({"action": "LOOK", "target": None, "message": None, "item": None,
                                  "quantity": None, "amount": None,
                                  "memory": {"content": "I remember arriving in New Eden.", "sourceEventId": event} if event else None})
            if body["model"] == "gpt-4.1-mini-memory-test":
                learned = any("watercolor" in m["content"] for m in context["relevantMemories"])
                content = json.dumps({"action": "MOVE", "target": "garden" if learned else "park",
                                      "memories": [{"kind": "semantic", "content": "I enjoy quiet creative places.",
                                                    "entities": ["garden"], "sourceEvents": []},
                                                   {"kind": "episodic", "content": "I recall my arrival in the city.",
                                                    "entities": [], "sourceEvents": [event]}] if event else []})
        result = {"choices": [{"message": {"content": content}}], "usage": {"total_tokens": 27}}
        if provider == "anthropic":
            result = {"content": [{"type": "text", "text": content}], "stop_reason": "end_turn",
                      "usage": {"input_tokens": 20, "output_tokens": 7}}
        output = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(output)))
        self.end_headers()
        self.wfile.write(output)

    def log_message(self, *_):
        pass


ThreadingHTTPServer(("0.0.0.0", 9999), Handler).serve_forever()
