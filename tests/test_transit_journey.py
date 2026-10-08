"""A bus can serve a final building through an intermediate arrival stop."""
import uuid
import httpx
from test_city_life import BASE, create, act, teleport_test_resident
from test_night_routine import clock, wait_for


def test_bus_itinerary_completes_only_at_final_building_and_charges_one_fare():
    clock(560)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"commute-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, agent = create(owner, "Bus Commuter", "Practical and economical, prefers transit for long trips")
        try:
            teleport_test_resident(aid, "stop-residential")
            observation = agent.get("/agent/observe").json()
            office = next(d for d in observation["travel"]["destinations"] if d["destination"] == "office")
            option = office["transit"][0]
            assert option["alightingStop"] != "office" and option["walkingDistanceSaved"] > 0
            assert option["walkFromStopMinutes"] > 0
            before = observation["self"]["money"]
            journey = act(agent, "TRAVEL_BUS", target="office", item=option["id"])
            assert journey["status"] == "accepted"
            assert agent.get("/agent/observe").json()["self"]["pending"]["finalDestination"] == "office"
            clock(560, False)
            wait_for(lambda: agent.get(f"/agent/actions/{journey['id']}").json()["status"] == "completed")
            current = agent.get("/agent/observe").json()["self"]
            assert current["location"] == "office" and current["pending"] is None
            assert current["money"] == before - option["fareCents"]
            payments = owner.get(f"/owner/agents/{aid}").json()["transactions"]
            assert len([p for p in payments if p["reason"] == "bus_fare"]) == 1
        finally:
            agent.close()
            clock(600, False)
