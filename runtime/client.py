"""The external protocol client. No access to Earth 35's database or internals."""
import os
import time
import uuid
import httpx


class Earth35:
    def __init__(self, token=None, url=None):
        self.http = httpx.Client(
            base_url=(url or os.environ.get("E35_URL", "http://localhost:8080")) + "/v1",
            headers={"X-E35-Client": "agent", **({"Authorization": f"Bearer {token}"} if token else {})},
            timeout=20,
        )

    def call(self, method, path, body=None):
        # Body and requestId are reused across transport retries.
        for attempt in range(4):
            try:
                response = self.http.request(method, path, json=body)
                if response.status_code == 429 or response.status_code >= 500:
                    if attempt < 3:
                        time.sleep(min(30, int(response.headers.get("Retry-After", 2 ** attempt))))
                        continue
                response.raise_for_status()
                return response.json()
            except httpx.TransportError:
                if attempt == 3:
                    raise
                time.sleep(2 ** attempt)

    def observe(self, after=0):
        return self.call("GET", f"/agent/observe?after={after}")

    def action(self, action, **parameters):
        return self.call("POST", "/agent/action", {"requestId": str(uuid.uuid4()), "action": action, **parameters})

    def recall(self, query="", limit=10, entities=None):
        return self.call("POST", "/agent/memories/recall", {"query": query, "limit": limit, "entities": entities or []})

    def night_routine(self):
        return self.call("POST", "/agent/night-routine", {"requestId": str(uuid.uuid4())})["action"]

    def plan_night(self, reason, until):
        return self.call("PUT", "/agent/night-plan", {"reason": reason, "until": until})

    def plan_routine(self, wakeMinute, sleepMinute, morningActivity):
        return self.call("PUT", "/agent/routine", {"wakeMinute": wakeMinute, "sleepMinute": sleepMinute, "morningActivity": morningActivity})

    def remember(self, **memory):
        return self.call("POST", "/agent/memories", {"requestId": str(uuid.uuid4()), **memory})

    def close(self):
        self.http.close()
