"""Run only against the isolated earth35-test Compose project with its mock provider."""
import time
import uuid
import httpx
import pytest

BASE = "http://127.0.0.1:8081/v1"
HEADERS = {"X-E35-Client": "web"}


def test_hosted_onboarding_and_autonomous_loop():
    with httpx.Client(base_url=BASE, headers=HEADERS, timeout=45) as owner, httpx.Client(base_url=BASE, timeout=20) as other:
        config = other.get("/hosted/config").json()
        initial_residents = len(other.get("/world").json()["agents"])
        assert config["available"] and "nvidia" in config["providers"]
        assert config["defaultModel"] == "openai/gpt-oss-20b"
        assert owner.post("/auth/register", json={"email": f"hosted-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        details = {"name": "Hosted Alex", "description": "An autonomous test resident", "personality": "Curious", "goals": "Explore New Eden", "homeType": "apartment", "fixture": False}
        bad = owner.post("/agents", json={**details, "hosted": {"provider": "nvidia", "model": config["defaultModel"], "apiKey": "wrong-key"}})
        assert bad.status_code == 422
        assert "API key" in bad.text and "NVIDIA Build" in bad.text
        retired = owner.post("/agents", json={**details, "hosted": {"provider": "nvidia", "model": "meta/llama-3.3-70b-instruct", "apiKey": "test-nvidia-key"}})
        assert retired.status_code == 422
        assert "deprecated" in retired.text
        assert len(other.get("/world").json()["agents"]) == initial_residents
        response = owner.post("/agents", json={**details, "hosted": {"provider": "nvidia", "model": config["defaultModel"], "apiKey": "test-nvidia-key"}})
        assert response.status_code == 200, response.text
        created = response.json()
        assert created["token"] is None
        agent_id = created["agent"]["id"]
        assert "test-nvidia-key" not in response.text
        assert other.get(f"/owner/agents/{agent_id}/runtime").status_code == 401
        until = time.monotonic() + 30
        while time.monotonic() < until:
            runtime = owner.get(f"/owner/agents/{agent_id}/runtime").json()
            detail = owner.get(f"/owner/agents/{agent_id}").json()
            if runtime["totalCalls"] and detail["memoryCount"] and any(a["status"] == "completed" for a in detail["actions"]):
                break
            time.sleep(.25)
        assert runtime["mode"] == "hosted" and runtime["status"] == "running", runtime
        assert runtime["provider"] == "nvidia" and runtime["model"] == config["defaultModel"]
        assert runtime["totalCalls"] > 0 and detail["memoryCount"] > 0
        assert any(a["status"] == "completed" for a in detail["actions"])
        assert "test-nvidia-key" not in str(runtime) + str(detail)
        assert owner.post(f"/owner/agents/{agent_id}/credentials").status_code == 409
        bad_update = owner.put(f"/owner/agents/{agent_id}/runtime", json={"provider": "nvidia", "model": config["defaultModel"], "apiKey": "wrong-key"})
        assert bad_update.status_code == 422
        assert owner.get(f"/owner/agents/{agent_id}/runtime").json()["status"] == "running"
        assert owner.put(f"/owner/agents/{agent_id}/runtime", json={"provider": "nvidia", "model": config["defaultModel"], "apiKey": "test-nvidia-key"}).status_code == 200
        assert owner.put(f"/owner/agents/{agent_id}/pause", json={"paused": True}).status_code == 200
        external = owner.post("/agents", json={**details, "name": "Converted Maya"}).json()
        former_token = external["token"]
        assert former_token
        assert owner.put(f"/owner/agents/{external['agent']['id']}/runtime", json={"provider": "nvidia", "model": config["defaultModel"], "apiKey": "test-nvidia-key"}).status_code == 200
        assert owner.get(f"/owner/agents/{external['agent']['id']}/runtime").json()["mode"] == "hosted"
        with httpx.Client(base_url=BASE, headers={"Authorization": f"Bearer {former_token}"}) as old_runtime:
            assert old_runtime.get("/agent/observe").status_code == 401


@pytest.mark.parametrize("provider", ["nvidia", "openrouter", "openai", "anthropic"])
def test_multiple_providers_register_run_and_switch_connections(provider):
    with httpx.Client(base_url=BASE, headers=HEADERS, timeout=45) as owner:
        config = owner.get("/hosted/config").json()
        options = {p["id"]: p for p in config["providerOptions"]}
        assert set(options) == {"nvidia", "openrouter", "openai", "anthropic"}
        assert config["defaultProvider"] == "nvidia"
        assert owner.post("/auth/register", json={"email": f"providers-{uuid.uuid4()}@example.test",
                                                "password": "one-very-long-test-password"}).status_code == 200
        details = {"name": f"Hosted {provider}", "description": "Provider integration resident",
                   "personality": "Curious", "goals": "Explore New Eden", "homeType": "any", "fixture": False}
        model = options[provider]["defaultModel"] + (":free" if provider == "openrouter" else "")
        setup = {"provider": provider, "model": model, "apiKey": f"test-{provider}-key"}
        invalid = owner.post("/agents", json={**details, "hosted": {**setup, "apiKey": "wrong-key"}})
        assert invalid.status_code == 422, invalid.text
        unsupported = owner.post("/agents", json={**details, "hosted": {**setup, "provider": "unknown"}})
        assert unsupported.status_code == 400, unsupported.text
        created = owner.post("/agents", json={**details, "hosted": setup})
        assert created.status_code == 200, created.text
        assert created.json()["token"] is None and setup["apiKey"] not in created.text
        agent_id = created.json()["agent"]["id"]
        home = created.json()["agent"]["home"]
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            runtime = owner.get(f"/owner/agents/{agent_id}/runtime").json()
            if runtime["totalCalls"] and runtime["totalTokens"]:
                break
            time.sleep(.25)
        assert runtime["provider"] == provider and runtime["model"] == model, runtime
        assert runtime["totalCalls"] > 0 and runtime["totalTokens"] >= 27, runtime
        assert runtime["status"] != "needs_attention", runtime
        assert owner.put(f"/owner/agents/{agent_id}/pause", json={"paused": True}).status_code == 200
        next_provider = {"nvidia": "openrouter", "openrouter": "anthropic", "anthropic": "openai", "openai": "nvidia"}[provider]
        replacement = {"provider": next_provider, "model": options[next_provider]["defaultModel"],
                       "apiKey": f"test-{next_provider}-key"}
        bad_update = owner.put(f"/owner/agents/{agent_id}/runtime", json={**replacement, "apiKey": "wrong-key"})
        assert bad_update.status_code == 422, bad_update.text
        assert owner.get(f"/owner/agents/{agent_id}/runtime").json()["provider"] == provider
        saved = owner.put(f"/owner/agents/{agent_id}/runtime", json=replacement)
        assert saved.status_code == 200, saved.text
        updated = owner.get(f"/owner/agents/{agent_id}/runtime").json()
        detail = owner.get(f"/owner/agents/{agent_id}").json()
        assert updated["provider"] == next_provider and updated["model"] == replacement["model"]
        assert detail["state"]["home"] == home and detail["state"]["paused"]
        assert all(key not in str(updated) + str(detail) for key in [setup["apiKey"], replacement["apiKey"]])
