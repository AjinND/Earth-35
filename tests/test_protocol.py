"""Run only against compose.test.yaml on localhost:8081; its PostgreSQL state is tmpfs."""
import concurrent.futures
import time
import uuid
import httpx
import pytest

BASE = "http://127.0.0.1:8081/v1"
WEB = {"X-E35-Client": "web"}
AGENT = {"X-E35-Client": "agent"}


def request(client, method, path, data=None, status=200):
    result = client.request(method, path, json=data)
    assert result.status_code == status, (path, result.status_code, result.text)
    return result.json()


def command(client, action, target=None, **fields):
    return request(client, "POST", "/agent/action", {"requestId": str(uuid.uuid4()), "action": action, "target": target, **fields})


def wait_until(check, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            result = check()
        except httpx.TransportError:
            result = False
        if result:
            return result
        time.sleep(.05)
    pytest.fail("Timed out waiting for world action")


def observe(client):
    return request(client, "GET", "/agent/observe")


def move(client, destination):
    result = command(client, "MOVE", destination)
    assert result["status"] == "accepted", result
    wait_until(lambda: observe(client)["self"]["location"] == destination and observe(client)["self"]["pending"] is None)


def test_external_protocol_persistence_privacy_and_capacity():
    public = httpx.Client(base_url=BASE, timeout=30)
    owner1 = httpx.Client(base_url=BASE, headers=WEB, timeout=30)
    owner2 = httpx.Client(base_url=BASE, headers=WEB, timeout=30)
    agents = []
    try:
        world = request(public, "GET", "/world")
        assert len(world["buildings"]) == 76
        assert len(world["agents"]) == 0, "The isolated test world must begin empty"
        assert request(public, "GET", "/owner/agents", status=401)["status"] == 401
        for index, owner in enumerate((owner1, owner2)):
            request(owner, "POST", "/auth/register", {"email": f"owner{index}@example.test", "password": "one-very-long-test-password"})
        registrations = []
        for owner, name in ((owner1, "Alex"), (owner2, "Maya")):
            result = request(owner, "POST", "/agents", {"name": name, "description": f"{name} explores New Eden", "personality": "Curious", "goals": "Meet people and find work", "homeType": "apartment" if name == "Alex" else "house", "fixture": False})
            registrations.append(result)
            agents.append(httpx.Client(base_url=BASE, headers={**AGENT, "Authorization": "Bearer " + result["token"]}, timeout=30))
        alex, maya = agents
        alex_id, maya_id = (r["agent"]["id"] for r in registrations)
        assert registrations[0]["agent"]["home"].startswith("apt-")
        assert registrations[1]["agent"]["home"].startswith("house-")
        public_agents = request(public, "GET", "/world")["agents"]
        assert {a["name"] for a in public_agents} == {"Alex", "Maya"}
        assert all("personality" not in a for a in public_agents)
        assert request(owner1, "GET", f"/owner/agents/{maya_id}", status=404)["status"] == 404
        assert request(maya, "GET", "/agent/observe")["self"]["name"] == "Maya"

        move(alex, "park")
        move(maya, "park")
        assert any(row["id"] == maya_id for row in observe(alex)["nearby"])
        spoken = command(alex, "TALK", maya_id, message="Hello Maya. Shall we explore the city?")
        assert spoken["status"] == "completed"
        event = request(alex, "GET", "/agent/events")[-1]
        assert event["kind"] == "conversation" and event["targetId"] == maya_id
        transfer_id = str(uuid.uuid4())
        payload = {"requestId": transfer_id, "action": "TRANSFER", "target": maya_id, "amount": 2500}
        transferred = request(alex, "POST", "/agent/action", payload)
        same = request(alex, "POST", "/agent/action", payload)
        assert transferred == same and transferred["status"] == "completed"
        gifts = [t for t in request(owner1, "GET", f"/owner/agents/{alex_id}")["transactions"] if t["reason"] == "gift"]
        assert len(gifts) == 1 and gifts[0]["amount"] == 2500 and gifts[0]["destination"] == maya_id
        changed = {**payload, "amount": 3500}
        assert request(alex, "POST", "/agent/action", changed, status=409)["status"] == 409
        assert command(alex, "MOVE", "apt-102")["status"] == "rejected"

        embedding = [0.01] * 384
        memory = request(alex, "POST", "/agent/memories", {"requestId": str(uuid.uuid4()), "kind": "episodic", "content": "Maya seems friendly after our talk in Central Park.", "entities": [maya_id, "Maya"], "sourceEvents": [event["id"]], "embedding": embedding, "embeddingModel": "test-embedding"})
        assert memory["id"]
        remembered = request(alex, "POST", "/agent/memories/recall", {"query": "Maya", "limit": 5})
        assert any(m["id"] == memory["id"] for m in remembered)
        vector_recall = request(alex, "POST", "/agent/memories/recall", {"query": "", "limit": 5, "embedding": embedding, "embeddingModel": "test-embedding"})
        assert vector_recall[0]["id"] == memory["id"]
        assert all(m["id"] != memory["id"] for m in request(maya, "POST", "/agent/memories/recall", {"query": "Maya", "limit": 5}))
        assert request(public, "GET", f"/owner/agents/{alex_id}", status=401)["status"] == 401
        request(alex, "POST", "/agent/usage", {"requestId": str(uuid.uuid4()), "tokens": 123})

        assert command(alex, "END_CHAT", message="I need to head to work. See you around! Bye!")["status"] == "completed"
        move(alex, "office")
        assert command(alex, "APPLY_JOB", "job-office")["status"] == "completed"
        assert command(alex, "ACCEPT_JOB", "job-office")["status"] == "completed"
        # Movement and provider work advance the accelerated clock. Employment
        # now has opening hours and weekdays, so wait for a real available shift.
        wait_until(lambda: observe(alex)["workSchedule"]["open"], 80)
        work = command(alex, "WORK")
        assert work["status"] == "accepted", work
        wait_until(lambda: request(alex, "GET", f"/agent/actions/{work['id']}")["status"] == "completed")
        wages = [t for t in request(owner1, "GET", f"/owner/agents/{alex_id}")["transactions"] if t["reason"] == "wage"]
        assert len(wages) == 1 and wages[0]["amount"] == 2000
        assert request(owner1, "GET", f"/owner/agents/{alex_id}")["reportedTokens"] == 123

        move(alex, "grocery")
        assert command(alex, "BUY", "grocery", item="meal", quantity=2)["status"] == "completed"
        purchases = [t for t in request(owner1, "GET", f"/owner/agents/{alex_id}")["transactions"] if t["reason"] == "purchase"]
        assert len(purchases) == 1 and purchases[0]["amount"] == 2400
        assert observe(alex)["self"]["inventory"]["meal"] == 4
        assert command(alex, "SELL", "grocery", item="meal", quantity=1)["status"] == "completed"
        assert command(alex, "EAT", item="meal")["status"] == "completed"

        # Restart checks that world facts, private memories, and economic state survive.
        wait_until(lambda: request(public, "GET", "/world")["minute"] % 1440 < 500, 25)
        before_restart = request(public, "GET", "/world")
        snapshot = (observe(alex)["self"]["money"], next(a["home"] for a in before_restart["agents"] if a["id"] == alex_id), memory["id"])
        import subprocess
        subprocess.run(["docker", "compose", "-p", "earth35-test", "-f", "compose.yaml", "-f", "compose.test.yaml", "restart", "api"], check=True, timeout=60)
        wait_until(lambda: public.get("/world").status_code == 200, 40)
        after = request(public, "GET", "/world")
        assert after["minute"] >= before_restart["minute"]
        assert observe(alex)["self"]["money"] == snapshot[0]
        assert next(a["home"] for a in after["agents"] if a["id"] == alex_id) == snapshot[1]
        assert any(m["id"] == snapshot[2] for m in request(alex, "POST", "/agent/memories/recall", {"query": "Maya", "limit": 5}))
        wait_until(lambda: request(public, "GET", "/world")["minute"] >= event["worldMinute"] + 1440, 30)
        assert any(m["id"] == snapshot[2] for m in request(alex, "POST", "/agent/memories/recall", {"query": "Maya", "limit": 5}))

        # Concurrency plus fixed capacity: all registrations share a locked world state.
        def add_resident(i):
            with httpx.Client(base_url=BASE, headers=WEB, cookies=owner1.cookies, timeout=60) as session:
                return session.post("/agents", json={"name": f"Fixture {i}", "description": "Scripted protocol fixture", "personality": "Scripted", "goals": "Test capacity", "fixture": True})
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            rows = list(pool.map(add_resident, range(48)))
        assert all(row.status_code == 200 for row in rows), [(row.status_code, row.text[:100]) for row in rows if row.status_code != 200]
        assert len(request(public, "GET", "/world")["agents"]) == 50
        assert add_resident(49).status_code == 409
    finally:
        public.close()
        owner1.close()
        owner2.close()
        for agent in agents:
            agent.close()
