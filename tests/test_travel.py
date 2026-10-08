"""Travel choices exercise the real protocol on the disposable test city only."""
import uuid
import httpx
from test_city_life import BASE, create, act, teleport_test_resident
from test_night_routine import clock, wait_for


def test_independent_routes_midtrip_decisions_shelter_and_raincoat():
    clock(570)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"travel-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, a = create(owner, "Careful walker", "Avoids rain and enjoys quiet walks")
        bid, b = create(owner, "Energetic commuter", "Punctual and athletic")
        try:
            teleport_test_resident(aid, "park"); teleport_test_resident(bid, "park")
            first = act(a, "MOVE", target="office", item="east-west-first")
            second = act(b, "MOVE", target="office", item="north-south-first")
            assert first["status"] == second["status"] == "accepted"
            before = a.get("/agent/observe").json()
            other = b.get("/agent/observe").json()
            assert before["self"]["pending"]["route"] != other["self"]["pending"]["route"]
            assert any(d["sheltered"] for d in before["travel"]["destinations"])
            # Weather changes while travelling: the resident gets another decision,
            # but the world does not force a switch to running or shelter.
            clock(1080)
            changed = a.get("/agent/observe").json()
            assert changed["weather"] == "Rain" and changed["travel"]["reconsider"]
            assert changed["self"]["pending"]["type"] == "MOVE"
            keep = act(a, "CONTINUE_TRAVEL")
            assert keep["status"] == "completed"
            continued = a.get("/agent/observe").json()
            assert continued["self"]["pending"]["id"] == first["id"] and not continued["travel"]["reconsider"]
            assert act(a, "RUN", target="library", item="invented-route")["status"] == "rejected"
            assert a.get(f"/agent/actions/{first['id']}").json()["status"] == "accepted"
            reroute = act(a, "RUN", target="library", item="north-south-first")
            assert reroute["status"] == "accepted"
            assert a.get(f"/agent/actions/{first['id']}").json()["status"] == "cancelled"
            current = a.get("/agent/observe").json()["self"]
            assert (current["x"], current["z"]) == (changed["self"]["x"], changed["self"]["z"])
            clock(1080, False)
            wait_for(lambda: a.get(f"/agent/actions/{reroute['id']}").json()["status"] == "completed")
            clock(1080)
            shelter = a.get("/agent/observe").json()
            assert shelter["self"]["location"] == "library" and shelter["travel"]["sheltered"]
            waiting = act(a, "WAIT_RAIN")
            assert waiting["status"] == "accepted"
            clock(1440, False)
            wait_for(lambda: a.get(f"/agent/actions/{waiting['id']}").json()["status"] == "completed")
            clock(600)
            teleport_test_resident(aid, "market")
            money = a.get("/agent/observe").json()["self"]["money"]
            assert act(a, "BUY", target="market", item="raincoat", quantity=1)["status"] == "completed"
            protected = a.get("/agent/observe").json()
            assert not protected["travel"]["rainProtected"] and protected["self"]["money"] == money - 3500
            assert act(a, "WEAR", item="raincoat")["status"] == "completed"
            assert a.get("/agent/observe").json()["travel"]["rainProtected"]
        finally:
            a.close(); b.close(); clock(600, False)
