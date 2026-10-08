"""Real livelihood actions on earth35-careers-test:8081/5436 only.

Run after starting the disposable Compose project:
    python -m pytest tests/test_livelihood.py -q

Position, needs and the idle clock are test fixtures. Employment, capital, stock,
income and construction progress are earned through HTTP actions and real ticks.
No hosted agent, provider credential or main-world state is touched.
"""
from contextlib import contextmanager
import json
import subprocess
import time
import uuid

import httpx


BASE = "http://127.0.0.1:8081/v1"
COMPOSE = ["docker", "compose", "-p", "earth35-careers-test",
           "-f", "compose.yaml", "-f", "compose.test.yaml"]
assert COMPOSE[3] == "earth35-careers-test"


def disposable_stack():
    # Check both published endpoints before any fixture write or API registration.
    for service, internal, expected in (("api", "8080", "127.0.0.1:8081"),
                                        ("db", "5432", "127.0.0.1:5436")):
        result = subprocess.run(COMPOSE + ["port", service, internal], check=True,
                                capture_output=True, text=True, timeout=15)
        assert result.stdout.strip() == expected, (service, result.stdout)


def sql(statement):
    return subprocess.run(COMPOSE + ["exec", "-T", "db", "psql", "-U", "earth35",
                                      "-d", "earth35", "-Atc", statement],
                          check=True, capture_output=True, text=True, timeout=30).stdout.strip()


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def freeze_clock():
    sql("UPDATE world_state SET last_tick_at=clock_timestamp()+interval '1 hour' WHERE id=1;")


def resume_clock():
    sql("UPDATE world_state SET last_tick_at=clock_timestamp()-interval '1 second' WHERE id=1;")


def clock(minute):
    assert isinstance(minute, int) and minute >= 0
    sql(f"UPDATE world_state SET data=jsonb_set(data,'{{minute}}',to_jsonb({minute}::bigint)),"
        "last_tick_at=clock_timestamp()+interval '1 hour' WHERE id=1;")


def patch_resident(agent_id, fields):
    path = "ARRAY['agents'," + literal(agent_id) + "]"
    sql(f"UPDATE world_state SET data=jsonb_set(data,{path},data#>{path} || "
        f"{literal(json.dumps(fields))}::jsonb) WHERE id=1;")


def fresh_condition(agent_id):
    patch_resident(agent_id, {"energy": 100, "hunger": 10})


def teleport(agent_id, building):
    point = json.loads(sql("SELECT jsonb_build_object('x',data#>ARRAY['buildings',"
                           + literal(building) + ",'x'],'z',data#>ARRAY['buildings',"
                           + literal(building) + ",'z']) FROM world_state WHERE id=1;"))
    patch_resident(agent_id, {"location": building, **point})


def observe(agent):
    response = agent.get("/agent/observe")
    assert response.status_code == 200, response.text
    return response.json()


def post_command(agent, body):
    response = agent.post("/agent/action", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def act(agent, action, **fields):
    return post_command(agent, {"requestId": str(uuid.uuid4()), "action": action, **fields})


def completed(agent, action, **fields):
    receipt = act(agent, action, **fields)
    assert receipt["status"] == "completed", receipt
    return receipt


def find(items, identifier):
    return next(item for item in items if item["id"] == identifier)


def wait_for(check, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            result = check()
            if result:
                return result
        except httpx.TransportError:
            pass
        time.sleep(.04)
    raise AssertionError("Timed out waiting for the disposable livelihood world")


def finish_pending(agent, receipt, duration, before):
    resume_clock()
    try:
        def settled():
            result = agent.get(f"/agent/actions/{receipt['id']}").json()
            return result if result["status"] != "accepted" else None
        result = wait_for(settled)
    finally:
        freeze_clock()
    assert result["status"] == "completed", result
    after = observe(agent)
    assert after["self"]["pending"] is None
    assert after["worldMinute"] >= before + duration
    return result


def elapsed(agent_id, agent, action, duration, **fields):
    freeze_clock()
    fresh_condition(agent_id)
    before = observe(agent)
    assert before["self"]["pending"] is None
    receipt = act(agent, action, **fields)
    assert receipt["status"] == "accepted", receipt
    assert observe(agent)["self"]["pending"]["type"] == action
    return finish_pending(agent, receipt, duration, before["worldMinute"])


@contextmanager
def residents(*names):
    disposable_stack()
    clock(600)
    owner = httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30)
    agents = []
    try:
        response = owner.post("/auth/register", json={
            "email": f"livelihood-{uuid.uuid4()}@example.test",
            "password": "one-very-long-test-password",
        })
        assert response.status_code == 200, response.text
        for name in names:
            response = owner.post("/agents", json={
                "name": name, "description": "Isolated livelihood regression",
                "personality": "Thoughtful and independent", "goals": "Choose a sustainable livelihood",
                "homeType": "any", "fixture": False,
            })
            assert response.status_code == 200, response.text
            registered = response.json()
            agent = httpx.Client(base_url=BASE, headers={"X-E35-Client": "agent",
                "Authorization": "Bearer " + registered["token"]}, timeout=30)
            agents.append((registered["agent"]["id"], agent))
        yield owner, agents
    finally:
        freeze_clock()
        for _, agent in agents:
            agent.close()
        owner.close()


def test_voluntary_quit_reemployment_and_solo_shop_earn_actual_livelihood_income():
    with residents("Career changing resident", "Independent job seeker", "Bakery customer") as (owner, agents):
        (founder_id, founder), (seeker_id, seeker), (buyer_id, buyer) = agents
        response = httpx.get("http://127.0.0.1:8081/v3/api-docs", timeout=30)
        assert response.status_code == 200, response.text
        schemas = response.json()["components"]["schemas"]
        assert schemas["Observation"]["properties"]["livelihood"]["$ref"].endswith("/LivelihoodView")
        assert schemas["LivelihoodView"]["properties"]["construction"]["$ref"].endswith("/ConstructionView")
        assert schemas["ConstructionView"]["properties"]["options"]["items"]["$ref"].endswith("/ConstructionOption")
        command_schema = schemas["Command"]["properties"]
        action_schema = command_schema["action"]
        if "$ref" in action_schema:
            action_schema = schemas[action_schema["$ref"].rsplit("/", 1)[-1]]
        assert "QUIT_JOB" in action_schema["enum"]
        assert command_schema["quantity"]["minimum"] == 0
        for agent_id in (founder_id, seeker_id):
            teleport(agent_id, "office")
        options = observe(founder)
        assert {"QUIT_JOB", "START_BUSINESS", "START_CONSTRUCTION", "BUILD_CONSTRUCTION"} <= set(options["actions"])
        assert {"bakery", "fruit-shop"} == {entry["kind"] for entry in options["livelihood"]["smallBusinesses"]}
        assert options["livelihood"]["dailyRentCents"] == 2000
        vacancies = find(options["livelihood"]["jobs"], "job-office")["vacancies"]
        completed(founder, "APPLY_JOB", target="job-office")
        completed(founder, "ACCEPT_JOB", target="job-office")
        assert find(observe(founder)["livelihood"]["jobs"], "job-office")["vacancies"] == vacancies - 1
        completed(founder, "TAKE_LEAVE")
        money = observe(founder)["self"]["money"]
        body = {"requestId": str(uuid.uuid4()), "action": "QUIT_JOB"}
        quit_receipt = post_command(founder, body)
        assert quit_receipt["status"] == "completed", quit_receipt
        quit_state = observe(founder)
        assert quit_state["self"]["job"] == "" and quit_state["self"]["occupation"] == "Unemployed"
        assert quit_state["self"]["leaveDay"] == -1 and quit_state["self"]["workPermit"] is None
        assert quit_state["self"]["pending"] is None and quit_state["self"]["money"] == money
        assert quit_state["workSchedule"] is None
        assert find(quit_state["livelihood"]["jobs"], "job-office")["vacancies"] == vacancies
        assert post_command(founder, body) == quit_receipt
        assert founder.post("/agent/action", json={**body, "target": "job-office"}).status_code == 409

        # Another resident chooses the released job; quitting does not assign either resident a path.
        assert observe(seeker)["self"]["job"] == ""
        completed(seeker, "APPLY_JOB", target="job-office")
        completed(seeker, "ACCEPT_JOB", target="job-office")
        seeker_money = observe(seeker)["self"]["money"]
        elapsed(seeker_id, seeker, "WORK", 60)
        assert observe(seeker)["self"]["money"] == seeker_money + 2000
        assert observe(founder)["self"]["job"] == "" and observe(founder)["self"]["pending"] is None

        for agent_id in (founder_id, buyer_id):
            teleport(agent_id, "market")
        founder_money = observe(founder)["self"]["money"]
        launch_body = {"requestId": str(uuid.uuid4()), "action": "START_BUSINESS", "target": "market",
                       "item": "bakery", "message": f"Independent bakery {uuid.uuid4().hex[:8]}",
                       "quantity": 0, "amount": 20000}
        launch = post_command(founder, launch_body)
        assert launch["status"] == "completed", launch
        business_id = "business:" + launch["id"]
        business = find(observe(founder)["innovations"]["businesses"], business_id)
        product_id = business["productId"]
        assert business["kind"] == "bakery" and business["owners"] == [founder_id]
        assert business["stock"] == 0 and business["balanceCents"] == 20000
        assert find(observe(founder)["jobs"], business["jobId"])["capacity"] == 0
        assert observe(founder)["self"]["money"] == founder_money - 25000
        assert post_command(founder, launch_body) == launch
        assert observe(founder)["self"]["money"] == founder_money - 25000
        assert act(buyer, "BUY", target=business_id, item=product_id)["status"] == "rejected"
        assert act(founder, "WITHDRAW_PROFIT", target=business_id, amount=1)["status"] == "rejected"
        assert act(buyer, "BUY", target=business_id, item=product_id, quantity=0)["status"] == "rejected"
        elapsed(founder_id, founder, "PRODUCE_PRODUCT", 60, target=business_id, quantity=2)
        manufactured = find(observe(founder)["innovations"]["businesses"], business_id)
        assert manufactured["stock"] == 2 and manufactured["balanceCents"] == 18800
        before_buy = observe(buyer)["self"]["money"]
        completed(buyer, "BUY", target=business_id, item=product_id, quantity=2)
        assert observe(buyer)["self"]["money"] == before_buy - 2400
        assert observe(buyer)["self"]["inventory"][product_id] == 2
        sold = find(observe(founder)["innovations"]["businesses"], business_id)
        assert sold["stock"] == 0 and sold["sales"] == 2 and sold["revenueCents"] == 2400
        assert sold["balanceCents"] == 21200 and sold["availableProfitCents"] == 1200
        before_distribution = observe(founder)["self"]["money"]
        completed(founder, "WITHDRAW_PROFIT", target=business_id, amount=1000)
        assert observe(founder)["self"]["money"] == before_distribution + 1000
        assert find(observe(founder)["innovations"]["businesses"], business_id)["balanceCents"] == 20200
        outsider = find(observe(buyer)["innovations"]["businesses"], business_id)
        assert outsider["balanceCents"] is None and outsider["revenueCents"] is None
        assert find(owner.get("/world").json()["innovations"]["businesses"], business_id)["balanceCents"] is None
        detail = owner.get(f"/owner/agents/{founder_id}").json()
        assert sum(t["amount"] for t in detail["transactions"] if t["reason"] == "business_profit_distribution") == 1000
        assert observe(founder)["self"]["job"] == "" and observe(seeker)["self"]["job"] == "job-office"


def test_resident_initiated_construction_pays_only_real_work_and_completes_after_restart():
    with residents("Independent building sponsor", "Voluntary construction builder") as (owner, agents):
        (sponsor_id, sponsor), (builder_id, builder) = agents
        observation = observe(sponsor)
        option = next(o for o in observation["livelihood"]["construction"]["options"] if o["type"] == "bakery")
        assert option["workHours"] == 8 and option["budgetCents"] == 24000
        assert any(site["sponsor"] == "city" and site["acceptingWorkers"]
                   for site in observation["livelihood"]["construction"]["sites"])
        before_funding = observation["self"]["money"]
        # Site availability is authoritative. Rejected candidate positions never reserve money or work.
        attempts = []
        for target in ("point:-250,-600", "point:-400,-450", "point:-450,-400", "point:-450,-350", "point:-530,-250"):
            start = act(sponsor, "START_CONSTRUCTION", target=target, item="bakery",
                        message=f"Voluntary bakery project {uuid.uuid4().hex[:8]}", amount=option["budgetCents"])
            attempts.append(start)
            if start["status"] == "completed":
                break
        assert start["status"] == "completed", attempts
        site_id = "site:" + start["id"]
        site = find(observe(sponsor)["livelihood"]["construction"]["sites"], site_id)
        assert site["completedHours"] == 0 and site["reservedHours"] == 0
        assert observe(sponsor)["self"]["money"] == before_funding - 24000
        assert observe(sponsor)["self"]["pending"] is None
        assert observe(builder)["self"]["pending"] is None and observe(builder)["self"]["job"] == ""
        assert act(builder, "BUILD_CONSTRUCTION", target=site_id)["status"] == "rejected"
        axes = site["entranceTarget"].removeprefix("point:").split(",")
        patch_resident(builder_id, {"location": site["entranceTarget"], "x": float(axes[0]), "z": float(axes[1])})
        fresh_condition(builder_id)
        builder_money = observe(builder)["self"]["money"]
        build_body = {"requestId": str(uuid.uuid4()), "action": "BUILD_CONSTRUCTION", "target": site_id}
        build = post_command(builder, build_body)
        assert build["status"] == "accepted", build
        saved = observe(builder)
        assert saved["self"]["pending"]["workMinutes"] == 0
        assert saved["self"]["money"] == builder_money
        assert find(saved["livelihood"]["construction"]["sites"], site_id)["reservedHours"] == 1
        assert act(sponsor, "MOVE", target=site_id)["status"] == "rejected"

        subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=60)
        wait_for(lambda: httpx.get(BASE + "/world", timeout=3).status_code == 200)
        freeze_clock()
        restored = observe(builder)
        assert restored["self"]["pending"] == saved["self"]["pending"]
        assert post_command(builder, build_body) == build
        assert find(restored["livelihood"]["construction"]["sites"], site_id)["completedHours"] == 0
        finish_pending(builder, build, 60, saved["worldMinute"])
        assert observe(builder)["self"]["money"] == builder_money + 2000
        assert builder.post("/agent/action", json={**build_body, "target": "construction-201"}).status_code == 409

        for hour in range(1, option["workHours"]):
            # Move the idle clock to the next working day only if a full hour no longer fits.
            current = observe(builder)["worldMinute"]
            if current % 1440 + 100 > 1200:
                clock((current // 1440 + 1) * 1440 + 600)
            elapsed(builder_id, builder, "BUILD_CONSTRUCTION", 60, target=site_id)
            credited = find(observe(builder)["livelihood"]["construction"]["sites"], site_id)
            assert credited["completedHours"] == hour + 1 and credited["reservedHours"] == 0

        finished = observe(builder)
        final_site = find(finished["livelihood"]["construction"]["sites"], site_id)
        assert final_site["status"] == "completed" and not final_site["acceptingWorkers"]
        assert final_site["unreservedBudgetCents"] == 0
        assert find(owner.get("/world").json()["buildings"], site_id)["type"] == "bakery"
        detail = owner.get(f"/owner/agents/{builder_id}").json()
        wages = [transaction for transaction in detail["transactions"] if transaction["reason"] == "construction_wage"]
        assert len(wages) == 8 and sum(t["amount"] for t in wages) == 16000
        assert finished["self"]["job"] == "" and finished["self"]["pending"] is None
        assert act(builder, "BUILD_CONSTRUCTION", target=site_id)["status"] == "rejected"
