"""Doors, venue hours, clothing and flexible sleep on the disposable city only."""
import uuid
import httpx
from test_city_life import BASE, create, act, sql, teleport_test_resident
from test_night_routine import clock, wait_for


def test_entrances_opening_hours_clothing_commute_and_recovery_protocol():
    clock(1080)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"needs-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, agent = create(owner, "Thoughtful commuter", "Practical early bird")
        try:
            def observe():
                return agent.get("/agent/observe").json()
            world = owner.get("/world").json()
            coffee = next(b for b in world["buildings"] if b["id"] == "coffee")
            assert coffee["entrance"] == {"side": "front", "x": coffee["x"], "z": coffee["z"] + 3.6}
            assert coffee["hours"]["openNow"]
            teleport_test_resident(aid, "market")
            assert act(agent, "BUY", target="market", item="raincoat", quantity=1)["status"] == "completed"
            obs = observe()
            assert obs["travel"]["raincoatAvailable"] and not obs["travel"]["rainProtected"]
            trip = act(agent, "MOVE", target="coffee")
            assert trip["status"] == "accepted"
            assert observe()["self"]["pending"]["routeChoice"] == "shortest"
            travelling = next(d for d in observe()["travel"]["destinations"] if d["destination"] == "coffee")
            assert travelling["hours"]["openNow"] is None
            assert act(agent, "WEAR", item="raincoat")["status"] == "completed"
            obs = observe()
            assert obs["self"]["pending"]["id"] == trip["id"] and obs["self"]["raincoatWorn"]
            assert next(a for a in owner.get("/world").json()["agents"] if a["id"] == aid)["raincoatWorn"]
            clock(1080, False)
            wait_for(lambda: observe()["self"]["pending"] is None)
            clock(1150)
            arrived = observe()["self"]
            assert arrived["location"] == "coffee" and arrived["x"] == coffee["x"] and arrived["z"] == coffee["z"]
            assert act(agent, "REMOVE_CLOTHING", item="raincoat")["status"] == "completed"
            clock(1200)
            assert act(agent, "BUY", target="coffee", item="meal", quantity=1)["status"] == "rejected"
            teleport_test_resident(aid, "park")
            assert act(agent, "MOVE", target="coffee")["status"] == "rejected"
            closed = next(d for d in observe()["travel"]["destinations"] if d["destination"] == "coffee")
            assert not closed["hours"]["openNow"] and closed["hours"]["nextOpenMinute"] == 1800
            home = observe()["self"]["home"]
            teleport_test_resident(aid, home)
            clock(1740)
            assert agent.put("/agent/routine", json={"wakeMinute": 360, "sleepMinute": 1320, "morningActivity": "breakfast"}).status_code == 200
            sql(f"UPDATE world_state SET data=jsonb_set(data,'{{agents,{aid},energy}}','25') WHERE id=1;")
            obs = observe()
            assert obs["sleep"]["tired"] and obs["sleep"]["recommendedSleepMinutes"] == 480
            assert act(agent, "SLEEP")["status"] == "accepted"
            assert observe()["self"]["pending"]["until"] == 2220
            clock(1740, False)
            wait_for(lambda: observe()["self"]["pending"] is None)
            assert observe()["self"]["lastWakeMinute"] == 2220
        finally:
            agent.close()
            clock(600, False)


def test_bus_commuter_discovers_closure_at_the_shop_door_and_pays_only_one_fare():
    clock(599)
    with httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30) as owner:
        assert owner.post("/auth/register", json={"email": f"closing-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
        aid, agent = create(owner, "Cafe commuter", "Prefers the bus")
        try:
            teleport_test_resident(aid, "stop-residential")
            obs = agent.get("/agent/observe").json()
            destination = next(d for d in obs["travel"]["destinations"] if d["destination"] == "coffee")
            option = destination["transit"][0]
            journey = act(agent, "TRAVEL_BUS", target="coffee", item=option["id"])
            assert journey["status"] == "accepted"
            sql(f"UPDATE world_state SET data=jsonb_set(data,'{{buildings,coffee,closeMinute}}',to_jsonb({option['arrivalMinute'] % 1440}::integer)) WHERE id=1;")
            travelling = next(d for d in agent.get("/agent/observe").json()["travel"]["destinations"] if d["destination"] == "coffee")
            assert travelling["hours"]["openNow"] is None and travelling["hours"]["nextOpenMinute"] is None
            clock(599, False)
            wait_for(lambda: agent.get(f"/agent/actions/{journey['id']}").json()["status"] == "rejected")
            state = agent.get("/agent/observe").json()["self"]
            assert state["location"] == "road" and state["pending"] is None
            assert state["x"] == destination["entrance"]["x"] and state["z"] == destination["entrance"]["z"]
            assert state["money"] == obs["self"]["money"] - option["fareCents"]
        finally:
            sql("UPDATE world_state SET data=jsonb_set(data,'{buildings,coffee,closeMinute}','1200') WHERE id=1;")
            agent.close()
            clock(600, False)
