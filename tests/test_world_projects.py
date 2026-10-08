"""Earned study -> collaborative research -> usable products -> funded startup, via HTTP.

All fixture writes and restart commands target the disposable earth35-test world only.
Knowledge, project progress, product stock and money transfers are earned by actions.
"""
import json
import subprocess
import uuid

import httpx

from test_city_life import BASE, COMPOSE, act, create, sql, teleport_test_resident
from test_memory_economy import owner_client, wait_for
from test_night_routine import clock
from test_library_capabilities import (
    freeze_clock, resume_clock, fresh_condition, observe, read_elapsed,
    authored_reasoning, post_command,
)

assert BASE == "http://127.0.0.1:8081/v1"
assert COMPOSE[:4] == ["docker", "compose", "-p", "earth35-test"]


def completed(agent, action, **fields):
    result = act(agent, action, **fields)
    assert result["status"] == "completed", result
    return result


def elapsed(agent_id, agent, action, duration, **fields):
    freeze_clock()
    fresh_condition(agent_id)
    before = observe(agent)
    result = act(agent, action, **fields)
    assert result["status"] == "accepted", result
    pending = observe(agent)["self"]["pending"]
    assert pending["type"] == action and pending["workMinutes"] == 0
    resume_clock()
    try:
        wait_for(lambda: agent.get(f"/agent/actions/{result['id']}").json()["status"] == "completed", timeout=60)
    finally:
        freeze_clock()
    after = observe(agent)
    assert after["self"]["pending"] is None
    assert after["worldMinute"] >= before["worldMinute"] + duration
    return result


def find(items, identifier):
    return next(item for item in items if item["id"] == identifier)


def library_session(agent, minutes):
    # The fast test clock also advances while HTTP/process polling finishes. Allow
    # headroom for that real idle time; move only to a later opening, never skip study.
    current = observe(agent)["worldMinute"]
    if current % 1440 + minutes + 200 >= 1200:
        clock((current // 1440 + 1) * 1440 + 540)
        freeze_clock()


def test_learning_collaboration_measured_research_real_products_and_startup_survive_restart():
    clock(540)
    freeze_clock()
    with owner_client() as owner:
        founder_id, founder = create(owner, "Applied project founder", "Curious and methodical")
        peer_id, peer = create(owner, "Interested project researcher", "Collaborative and careful")
        buyer_id, buyer = create(owner, "Useful product customer", "Practical")
        try:
            for agent_id in (founder_id, peer_id):
                teleport_test_resident(agent_id, "library")
                fresh_condition(agent_id)
            for book, minutes in (("science-everyday", 120), ("living-systems", 120), ("science-measurement-models", 160)):
                library_session(founder, minutes)
                completed(founder, "BORROW_BOOK", target=book)
                read_elapsed(founder_id, founder, book, minutes)
                completed(founder, "RETURN_BOOK", target=book)
            for book, minutes in (("science-everyday", 120), ("living-systems", 60)):
                library_session(peer, minutes)
                completed(peer, "BORROW_BOOK", target=book)
                read_elapsed(peer_id, peer, book, minutes)
                completed(peer, "RETURN_BOOK", target=book)
            assert observe(founder)["self"]["knowledge"]["science"]["points"] == 28
            assert observe(peer)["self"]["knowledge"]["science"]["points"] == 12

            # Move only fixture position/time to the next open day; all project work ticks normally.
            clock((observe(founder)["worldMinute"] // 1440 + 1) * 1440 + 540)
            freeze_clock()
            for agent_id in (founder_id, peer_id, buyer_id):
                teleport_test_resident(agent_id, "market")
                fresh_condition(agent_id)
            completed(founder, "DECLARE_INTEREST", target="science")
            completed(peer, "DECLARE_INTEREST", target="science")
            assert find(observe(founder)["innovations"]["interestedResidents"], peer_id)["interests"] == ["science"]
            concepts = observe(founder)["self"]["knowledge"]["science"]["concepts"]
            reasoning = completed(founder, "REASON", target="science", message=json.dumps(authored_reasoning(concepts)))
            content = (f"{concepts[0]} We will build a nourishment product for residents with measurable hunger. "
                       "Record needs first, construct a paid prototype and test its effect before manufacturing. "
                       "Discuss usefulness with interested residents and compare material costs before setting a price.")
            draft = completed(founder, "CREATE_WORK", target="science", item="product", message=json.dumps({
                "title": f"Neighborhood nourishment {uuid.uuid4()}", "content": content, "reasoningId": reasoning["id"],
            }))
            project = completed(founder, "START_PROJECT", target=draft["id"], item="nourishment", message="market")
            project_id = project["id"]
            assert not find(observe(founder)["innovations"]["projects"], project_id)["productId"]
            assert act(founder, "BUILD_PROJECT", target=project_id)["status"] == "rejected"
            proposal = "Let us research neighborhood hunger and test a nourishment prototype together."
            assert act(founder, "INVITE_PROJECT", target=peer_id, item=project_id, message=proposal)["status"] == "rejected"
            completed(founder, "TALK", target=peer_id, message=proposal)
            completed(peer, "TALK", target=founder_id, message="I am interested in studying whether this would be useful.")
            completed(founder, "END_CHAT", target=peer_id,
                      message="Thank you. I will research our idea now. Goodbye for now.")
            invitation = completed(founder, "INVITE_PROJECT", target=peer_id, item=project_id, message=proposal)
            assert peer_id not in find(observe(founder)["innovations"]["projects"], project_id)["members"]
            completed(peer, "ACCEPT_PROJECT", target=invitation["id"])
            assert peer_id in find(observe(founder)["innovations"]["projects"], project_id)["members"]

            elapsed(peer_id, peer, "RESEARCH_PROJECT", 30, target=project_id, item="observation", quantity=30)
            assert observe(peer)["self"]["knowledge"]["science"]["researchPoints"] == 2
            elapsed(peer_id, peer, "RESEARCH_PROJECT", 30, target=project_id, item="observation", quantity=30)
            assert observe(peer)["self"]["knowledge"]["science"]["researchPoints"] == 2
            money_before = observe(founder)["self"]["money"]
            elapsed(founder_id, founder, "BUILD_PROJECT", 60, target=project_id)
            obs = observe(founder)
            product_id = find(obs["innovations"]["projects"], project_id)["productId"]
            assert obs["self"]["inventory"][product_id] == 1
            assert obs["self"]["money"] == money_before - 1200
            elapsed(founder_id, founder, "RESEARCH_PROJECT", 30, target=project_id, item="test", quantity=30)
            obs = observe(founder)
            assert product_id not in obs["self"]["inventory"]
            findings = find(obs["innovations"]["projects"], project_id)["research"]
            test = next(f for f in findings if f["method"] == "test")
            assert test["evidence"]["beforeHunger"] > test["evidence"]["afterHunger"]
            assert test["evidence"]["unitsConsumed"] == 1
            assert obs["self"]["knowledge"]["science"]["researchPoints"] == 2
            completed(founder, "ENDORSE_BUSINESS", target=project_id)
            assert act(founder, "LAUNCH_BUSINESS", target=project_id, message="Useful neighborhood startup", amount=20000)["status"] == "rejected"
            completed(peer, "ENDORSE_BUSINESS", target=project_id)
            money_before = observe(founder)["self"]["money"]
            launch = completed(founder, "LAUNCH_BUSINESS", target=project_id, message="Useful neighborhood startup", quantity=1, amount=20000)
            business_id = "business:" + launch["id"]
            business = find(observe(founder)["innovations"]["businesses"], business_id)
            assert business["stock"] == 0 and business["balanceCents"] == 20000
            assert set(business["owners"]) == {founder_id, peer_id}
            assert observe(founder)["self"]["money"] == money_before - 25000
            assert act(buyer, "BUY", target=business_id, item=product_id, quantity=1)["status"] == "rejected"

            completed(peer, "APPLY_JOB", target=business["jobId"])
            completed(peer, "ACCEPT_JOB", target=business["jobId"])
            before_pay = observe(peer)["self"]["money"]
            elapsed(peer_id, peer, "WORK", 60)
            assert observe(peer)["self"]["money"] == before_pay + 1000
            assert find(observe(founder)["innovations"]["businesses"], business_id)["balanceCents"] == 19000
            elapsed(founder_id, founder, "PRODUCE_PRODUCT", 60, target=business_id, quantity=2)
            business = find(observe(founder)["innovations"]["businesses"], business_id)
            assert business["stock"] == 2 and business["balanceCents"] == 17800
            before_buy = observe(buyer)["self"]["money"]
            completed(buyer, "BUY", target=business_id, item=product_id, quantity=1)
            assert observe(buyer)["self"]["money"] == before_buy - 1200
            assert observe(buyer)["self"]["inventory"][product_id] == 1
            before_use = observe(buyer)["self"]["hunger"]
            completed(buyer, "USE_PRODUCT", target=product_id)
            assert observe(buyer)["self"]["hunger"] == max(0, before_use - 40)
            assert product_id not in observe(buyer)["self"]["inventory"]
            business = find(observe(founder)["innovations"]["businesses"], business_id)
            assert business["stock"] == 1 and business["sales"] == 1 and business["revenueCents"] == 1200
            assert business["balanceCents"] == 19000
            assert act(founder, "WITHDRAW_PROFIT", target=business_id, amount=2)["status"] == "rejected"
            outsider = find(observe(buyer)["innovations"]["businesses"], business_id)
            assert outsider["balanceCents"] is None and outsider["revenueCents"] is None
            public = owner.get("/world").json()["innovations"]
            assert not find(public["projects"], project_id)["research"]
            assert find(public["businesses"], business_id)["balanceCents"] is None
            assert "invitations" not in public
            with owner_client() as stranger:
                assert stranger.get(f"/owner/agents/{founder_id}").status_code == 404

            # A launched project remains a place to investigate economics and learn.
            elapsed(founder_id, founder, "RESEARCH_PROJECT", 30,
                    target=project_id, item="economics", quantity=30)
            obs = observe(founder)
            economics = next(f for f in find(obs["innovations"]["projects"], project_id)["research"]
                             if f["method"] == "economics")
            assert economics["evidence"]["unitMaterialCostCents"] == 600
            assert economics["evidence"]["productionMinutesPerUnit"] == 30
            assert obs["self"]["knowledge"]["science"]["researchPoints"] == 4
            assert find(obs["innovations"]["projects"], project_id)["status"] == "launched"

            # Repeat an in-flight batch request after a server restart: no duplicate stock/materials.
            fresh_condition(founder_id)
            body = {"requestId": str(uuid.uuid4()), "action": "PRODUCE_PRODUCT", "target": business_id, "quantity": 1}
            pending = post_command(founder, body)
            assert pending["status"] == "accepted"
            saved = observe(founder)
            assert saved["self"]["pending"]["workMinutes"] == 0
            assert find(saved["innovations"]["businesses"], business_id)["stock"] == 1
            subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=30)
            def ready():
                try:
                    return httpx.get(BASE + "/world", timeout=3).status_code == 200
                except httpx.TransportError:
                    return False
            wait_for(ready, timeout=60)
            freeze_clock()
            restored = observe(founder)
            assert restored["self"]["pending"] == saved["self"]["pending"]
            assert restored["self"]["researchFindings"] == saved["self"]["researchFindings"]
            assert post_command(founder, body) == pending
            assert find(restored["innovations"]["businesses"], business_id)["stock"] == 1
            resume_clock()
            wait_for(lambda: founder.get(f"/agent/actions/{pending['id']}").json()["status"] == "completed", timeout=60)
            freeze_clock()
            assert find(observe(founder)["innovations"]["businesses"], business_id)["stock"] == 2
            assert founder.post("/agent/action", json={**body, "quantity": 2}).status_code == 409
        finally:
            freeze_clock()
            founder.close()
            peer.close()
            buyer.close()
