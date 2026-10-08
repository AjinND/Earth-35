"""City-life regressions; all database operations target the disposable test project."""
import json
import subprocess
import time
import uuid
import httpx
from test_night_routine import clock, wait_for

BASE = "http://127.0.0.1:8081/v1"
COMPOSE = ["docker", "compose", "-p", "earth35-test", "-f", "compose.yaml", "-f", "compose.test.yaml"]


def sql(statement):
    return subprocess.run(COMPOSE + ["exec", "-T", "db", "psql", "-U", "earth35", "-d", "earth35", "-Atc", statement], check=True, capture_output=True, text=True, timeout=30).stdout.strip()


def create(owner, name, personality):
    response = owner.post("/agents", json={"name": name, "description": "City-life regression", "personality": personality, "goals": "Enjoy the city", "homeType": "any", "fixture": False})
    assert response.status_code == 200, response.text
    result = response.json()
    return result["agent"]["id"], httpx.Client(base_url=BASE, headers={"X-E35-Client": "agent", "Authorization": f"Bearer {result['token']}"}, timeout=30)


def act(agent, action, **fields):
    response = agent.post("/agent/action", json={"requestId": str(uuid.uuid4()), "action": action, **fields})
    assert response.status_code == 200, response.text
    return response.json()


def teleport_test_resident(agent_id, place):
    # Test setup only. Production movement is exercised below and in protocol tests.
    sql(f"UPDATE world_state SET data=jsonb_set(data,'{{agents,{agent_id}}}',data#>'{{agents,{agent_id}}}' || jsonb_build_object('location','{place}','x',data#>'{{buildings,{place},x}}','z',data#>'{{buildings,{place},z}}')) WHERE id=1;")


def test_conversation_availability_actual_turns_routines_and_work_hours():
    clock(600)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"life-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, a = create(owner, "Early Maya", "Early bird who enjoys jogging")
        bid, b = create(owner, "Late Test", "Night owl who sleeps in")
        try:
            teleport_test_resident(aid, "park"); teleport_test_resident(bid, "park")
            ra = a.get("/agent/observe").json()["self"]["routine"]
            rb = b.get("/agent/observe").json()["self"]["routine"]
            assert ra["wakeMinute"] < rb["wakeMinute"] and ra["morningActivity"] == "jogging"
            first = act(a, "TALK", target=bid, message="Hello. What is the neighborhood like?")
            assert first["status"] == "completed"
            followup = act(a, "TALK", target=bid, message="Thanks for the information!")
            assert followup["status"] == "rejected" and "No reply" in followup["result"]
            obs = a.get("/agent/observe?after=999999999").json()
            assert not obs["events"] and obs["conversations"][0]["status"] == "waiting_for_reply"
            assert obs["conversations"][0]["turns"][0]["message"] == "Hello. What is the neighborhood like?"
            assert act(b, "TALK", target=aid, message="There is a garden nearby.")["status"] == "completed"
            assert act(a, "TALK", target=bid, message="Thanks, I will visit it.")["status"] == "completed"
            owner.put(f"/owner/agents/{bid}/pause", json={"paused": True})
            assert not next(c for c in a.get("/agent/observe").json()["contacts"] if c["agent"] == bid)["available"]
            assert act(a, "TALK", target=bid, message="Are you there?")["status"] == "rejected"
            owner.put(f"/owner/agents/{bid}/pause", json={"paused": False})
            # A credential failure must override a fresh observe heartbeat.
            sql(f"INSERT INTO hosted_agents(agent_id,provider,model,encrypted_key,status) VALUES('{bid}','nvidia','test','not-a-key','needs_attention');")
            assert not next(c for c in a.get("/agent/observe").json()["contacts"] if c["agent"] == bid)["available"]
            assert a.get("/agent/observe").json()["conversations"][0]["status"] == "unavailable"
            assert act(b, "TALK", target=aid, message="I am back.")["status"] == "completed"
            assert act(a, "TALK", target=bid, message="Welcome back.")["status"] == "rejected"
            sql(f"DELETE FROM hosted_agents WHERE agent_id='{bid}';")
            b.get("/agent/observe")
            assert act(a, "TALK", target=bid, message="Welcome back.")["status"] == "completed"
            assert a.put("/agent/routine", json={"wakeMinute": 330, "sleepMinute": 1290, "morningActivity": "jogging"}).status_code == 200
            assert a.put("/agent/routine", json={"wakeMinute": 330, "sleepMinute": 300, "morningActivity": "jogging"}).status_code == 400
            teleport_test_resident(aid, "office")
            assert act(a, "APPLY_JOB", target="job-office")["status"] == "completed"
            assert act(a, "ACCEPT_JOB", target="job-office")["status"] == "completed"
            clock(390)
            assert act(a, "WORK")["status"] == "rejected"
            clock(540)
            work = act(a, "WORK")
            assert work["status"] == "accepted"
            clock(599, False)
            wait_for(lambda: a.get(f"/agent/actions/{work['id']}").json()["status"] == "completed")
            clock(600)
            detail = owner.get(f"/owner/agents/{aid}").json()
            assert detail["workSchedule"]["startMinute"] == 540 and detail["workSchedule"]["minutesWorkedToday"] == 60
            assert detail["state"]["routine"]["wakeMinute"] == 330
        finally:
            a.close(); b.close(); clock(600, False)


def test_transit_booking_arrival_capacity_and_idempotency():
    clock(599)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"bus-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, agent = create(owner, "Bus rider", "Curious")
        try:
            world = owner.get("/world").json()
            assert len(world["transport"]["stops"]) == 5 and len(world["transport"]["routes"]) == 2
            assert len(world["transport"]["signals"]) == 8
            assert any(v["type"] == "car" for v in world["transport"]["vehicles"])
            assert act(agent, "WAIT_BUS", target="R1", item="stop-park")["status"] == "rejected"
            teleport_test_resident(aid, "stop-residential")
            assert act(agent, "BOARD_BUS", target="bus:R1:0:600", item="stop-park")["status"] == "rejected"
            before = agent.get("/agent/observe").json()["self"]["money"]
            payload = {"requestId": "bus-reservation", "action": "WAIT_BUS", "target": "R1", "item": "stop-park"}
            receipt = agent.post("/agent/action", json=payload).json()
            assert receipt["status"] == "accepted"
            assert agent.post("/agent/action", json=payload).json() == receipt
            state = agent.get("/agent/observe").json()["self"]
            assert state["pending"]["type"] == "WAIT_BUS" and state["money"] == before
            arrival = state["pending"]["until"]
            clock(599, False)
            wait_for(lambda: agent.get(f"/agent/actions/{receipt['id']}").json()["status"] == "completed")
            clock(arrival)
            state = agent.get("/agent/observe").json()["self"]
            assert state["location"] == "stop-park" and state["pending"] is None
            assert state["money"] == before - 250
            fares = [t for t in owner.get(f"/owner/agents/{aid}").json()["transactions"] if t["reason"] == "bus_fare"]
            assert len(fares) == 1 and fares[0]["amount"] == 250
            events = agent.get("/agent/events").json()
            assert any("boarded" in e["message"] for e in events) and any("left the bus" in e["message"] for e in events)
        finally:
            agent.close(); clock(600, False)


def test_existing_city_migration_preserves_history_and_recovers_waiting_turn():
    clock(700)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"upgrade-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, a = create(owner, "Migration Maya", "Early bird")
        bid, b = create(owner, "Migration Test", "Night owl")
        try:
            teleport_test_resident(aid, "park"); teleport_test_resident(bid, "park")
            a.get("/agent/observe"); b.get("/agent/observe")
            assert act(a, "TALK", target=bid, message="An unanswered question.")["status"] == "completed"
            count = sql("SELECT count(*) FROM world_events")
            # Recreate the old schema shape, including removal of the new memory schema,
            # so Flyway upgrades V6 through V9 in their normal order.
            sql("UPDATE world_state SET data=jsonb_set(data-'conversations'-'economy','{buildings,stop-residential,z}','-48'); ALTER TABLE agent_memories DROP COLUMN request; DROP INDEX agent_memories_entities; DROP INDEX agent_memories_pending_embedding; DELETE FROM flyway_schema_history WHERE version IN ('6','7','8','9');")
            subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=60)
            
            def ready():
                try:
                    return httpx.get(BASE + "/world", timeout=5).status_code == 200
                except httpx.TransportError:
                    return False
            wait_for(ready)
            b.get("/agent/observe")
            state = a.get("/agent/observe").json()
            assert state["self"]["name"] == "Migration Maya" and state["self"]["routine"]
            assert state["conversations"][0]["status"] == "waiting_for_reply"
            assert act(a, "TALK", target=bid, message="Thanks for your imaginary reply.")["status"] == "rejected"
            assert sql("SELECT count(*) FROM world_events") == count
            assert len(owner.get("/world").json()["transport"]["stops"]) == 5
            assert next(b for b in owner.get("/world").json()["buildings"] if b["id"] == "stop-residential")["z"] == -42
        finally:
            a.close(); b.close(); clock(600, False)
