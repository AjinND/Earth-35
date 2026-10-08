"""Night routine integration checks against the disposable earth35-test world only."""
import subprocess
import time
import uuid
import httpx

BASE = "http://127.0.0.1:8081/v1"


def clock(minute, frozen=True):
    # This exact Compose project uses tmpfs and port 8081; never the main world.
    gate = "clock_timestamp()+interval '120 seconds'" if frozen else "clock_timestamp()-interval '1 second'"
    subprocess.run([
        "docker", "compose", "-p", "earth35-test", "-f", "compose.yaml", "-f", "compose.test.yaml",
        "exec", "-T", "db", "psql", "-U", "earth35", "-d", "earth35", "-c",
        f"UPDATE world_state SET data=jsonb_set(data,'{{minute}}',to_jsonb({minute}::bigint)),last_tick_at={gate} WHERE id=1;",
    ], check=True, capture_output=True, timeout=30)


def wait_for(check):
    until = time.monotonic() + 25
    while time.monotonic() < until:
        if check():
            return
        time.sleep(.1)
    raise AssertionError("The isolated world did not finish the expected action")


def test_night_plans_home_sleep_waking_and_idempotent_retries():
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        clock(720)
        assert owner.post("/auth/register", json={"email": f"night-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        created = owner.post("/agents", json={"name": "Night Routine Test", "description": "Isolated test resident", "personality": "Curious", "goals": "Meet a friend", "homeType": "house", "fixture": False}).json()
        agent_id, home = created["agent"]["id"], created["agent"]["home"]
        with httpx.Client(base_url=BASE, headers={"X-E35-Client": "agent", "Authorization": f"Bearer {created['token']}"}, timeout=30) as agent:
            assert agent.put("/agent/routine", json={"wakeMinute": 360, "sleepMinute": 1200, "morningActivity": "breakfast"}).status_code == 200
            def observe():
                return agent.get("/agent/observe").json()
            def routine(request_id):
                response = agent.post("/agent/night-routine", json={"requestId": request_id})
                assert response.status_code == 200, response.text
                return response.json()["action"]
            assert routine("day") is None
            assert agent.post("/agent/action", json={"requestId": "visit", "action": "MOVE", "target": "park"}).json()["status"] == "accepted"
            clock(720, False)
            wait_for(lambda: observe()["self"]["location"] == "park" and observe()["self"]["pending"] is None)
            clock(1200)
            subprocess.run([
                "docker", "compose", "-p", "earth35-test", "-f", "compose.yaml", "-f", "compose.test.yaml",
                "exec", "-T", "db", "psql", "-U", "earth35", "-d", "earth35", "-c",
                f"UPDATE world_state SET data=jsonb_set(data,'{{agents,{agent_id},energy}}','45') WHERE id=1;",
            ], check=True, capture_output=True, timeout=30)
            observation = observe()
            assert observation["night"] and observation["timeOfDay"] == "20:00" and observation["nextMorning"] == 1800
            assert agent.put("/agent/night-plan", json={"reason": "Meeting a friend", "until": 1260}).status_code == 200
            assert routine("planned") is None
            assert observe()["self"]["nightPlan"]["reason"] == "Meeting a friend"
            public = owner.get("/world").json()
            assert all("nightPlan" not in resident and "bedtimeRoutine" not in resident for resident in public["agents"])
            assert agent.put("/agent/night-plan", json={"reason": "Indefinite visit", "until": 1921}).status_code == 400
            assert agent.put("/agent/night-plan", json={"reason": "", "until": 0}).status_code == 200
            returning = routine("home")
            assert returning["status"] == "accepted"
            assert routine("home") == returning
            assert observe()["self"]["pending"]["target"] == home
            clock(1200, False)
            wait_for(lambda: observe()["self"]["location"] == home and observe()["self"]["pending"] is None)
            clock(1310)
            sleeping = routine("sleep")
            assert sleeping["status"] == "accepted"
            assert routine("sleep") == sleeping
            self = observe()["self"]
            assert self["pending"]["type"] == "SLEEP" and self["pending"]["until"] == 1800
            assert self["bedtimeRoutine"]
            # Let the accepted sleep actually recover energy before advancing
            # the clock to its last minute.
            clock(1310, False)
            wait_for(lambda: observe()["self"]["energy"] >= 99)
            clock(1799, False)
            wait_for(lambda: observe()["self"]["pending"] is None)
            assert not observe()["night"] and not observe()["self"]["bedtimeRoutine"]
            assert routine("morning") is None
            events = agent.get("/agent/events").json()
            assert any("went to sleep" in event["message"] for event in events)
            assert any("woke up" in event["message"] for event in events)
            assert owner.put(f"/owner/agents/{agent_id}/pause", json={"paused": True}).status_code == 200
            clock(1200)
            assert routine("paused") is None
            assert observe()["self"]["location"] == home
        clock(1200, False)
