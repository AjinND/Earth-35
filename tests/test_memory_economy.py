"""Real embedding service and PostgreSQL tests; all mutations use the disposable test stack."""
import json
from contextlib import contextmanager
import subprocess
import time
import uuid
import httpx
from test_city_life import BASE, COMPOSE, sql, create, act, teleport_test_resident
from test_night_routine import clock


@contextmanager
def owner_client():
    owner = httpx.Client(base_url=BASE, headers={"X-E35-Client": "web"}, timeout=30)
    assert owner.post("/auth/register", json={"email": f"memory-{uuid.uuid4()}@example.test", "password": "one-very-long-test-password"}).status_code == 200
    try:
        yield owner
    finally:
        owner.close()


def wait_for(check, timeout=25):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            if check():
                return
        except httpx.TransportError:
            pass
        time.sleep(.2)
    raise AssertionError("Timed out waiting for the isolated test world")


def remember(agent, content, kind="semantic", **fields):
    body = {"requestId": str(uuid.uuid4()), "kind": kind, "content": content, "entities": [], "sourceEvents": [], **fields}
    response = agent.post("/agent/memories", json=body)
    assert response.status_code == 200, response.text
    return body, response.json()


def recall(agent, query, **fields):
    response = agent.post("/agent/memories/recall", json={"query": query, "limit": 5, **fields})
    assert response.status_code == 200, response.text
    return response.json()


def test_automatic_semantic_recall_tags_privacy_retries_outage_and_restart():
    clock(720)
    with owner_client() as owner:
        aid, a = create(owner, "Memory Alex", "Curious")
        bid, b = create(owner, "Maya Painter", "Creative")
        try:
            teleport_test_resident(aid, "park"); teleport_test_resident(bid, "park")
            a.get("/agent/observe"); b.get("/agent/observe")
            assert act(b, "TALK", target=aid, message="I create landscapes with a brush and canvas.")["status"] == "completed"
            source = next(e["id"] for e in a.get("/agent/events").json() if e["kind"] == "conversation" and e["actorId"] == bid)
            body, result = remember(a, "Maya Painter's enthusiasm at Central Park inspired my own interest in artistic painting.", "semantic", sourceEvents=[source])
            assert a.post("/agent/memories", json=body).json() == result
            assert a.post("/agent/memories", json={**body, "content": "Different"}).status_code == 409
            assert all(m["id"] != result["id"] for m in recall(a, ""))
            act(a, "END_CHAT", message="I should get going. See you later! Bye!")
            job = a.get("/agent/conversation-summary").json()["pending"][0]
            response = a.post(f"/agent/conversation-summary/{job['episode']}", json={"throughEvent": job["throughEvent"],
                "summary": "Maya described her landscape art. We had a neutral exchange, with no shared plan.",
                "reviewedReflections": [result["id"]], "approvedReflections": [result["id"]]})
            assert response.status_code == 200, response.text
            wait_for(lambda: sql(f"SELECT embedding IS NOT NULL FROM agent_memories WHERE id='{result['id']}';") == "t", timeout=40)
            for text in ["I had a warm cup of coffee.", "The bus runs past the market.", "Rent is due tomorrow.", "The grocery sells food.", "The sky is overcast.", "I like jogging."]:
                remember(a, text)
            memories = recall(a, "Who enjoys artistic painting?")
            assert memories[0]["id"] == result["id"], memories
            assert bid in json.loads(memories[0]["entities"]) and "park" in json.loads(memories[0]["entities"])
            assert "all-MiniLM-L6-v2" in sql(f"SELECT embedding_model FROM agent_memories WHERE id='{result['id']}';")
            assert sql(f"SELECT vector_dims(embedding) FROM agent_memories WHERE id='{result['id']}';") == "384"
            assert all(m["id"] != result["id"] for m in recall(b, "artistic painting"))
            assert all(m["id"] != result["id"] for m in recall(b, "", entities=[bid]))
            assert a.post("/agent/memories", json={**body, "requestId": str(uuid.uuid4()), "sourceEvents": [999999999]}).status_code == 400
            vector = [0.01] * 384
            _, custom = remember(a, "Custom vector space.", embedding=vector, embeddingModel="owner-custom-v1")
            assert recall(a, "", embedding=vector, embeddingModel="owner-custom-v1")[0]["id"] == custom["id"]
            assert a.post("/agent/memories/recall", json={"query": "", "limit": 5, "embedding": [0.0] * 384, "embeddingModel": "owner-custom-v1"}).status_code == 400
            # Service outage must not discard the text, and lexical/entity recall still works.
            subprocess.run(COMPOSE + ["stop", "embeddings"], check=True, capture_output=True, timeout=30)
            try:
                _, pending = remember(a, "The library is my quiet reading refuge.", entities=["library"])
                assert sql(f"SELECT embedding IS NULL FROM agent_memories WHERE id='{pending['id']}';") == "t"
                assert recall(a, "reading refuge", entities=["library"])[0]["id"] == pending["id"]
            finally:
                subprocess.run(COMPOSE + ["start", "embeddings"], check=True, capture_output=True, timeout=30)
            wait_for(lambda: sql(f"SELECT embedding IS NOT NULL FROM agent_memories WHERE id='{pending['id']}';") == "t", timeout=40)
            # Restart leaves memory content, tags, embedding and request idempotency intact.
            subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=30)
            wait_for(lambda: httpx.get(BASE + "/world", timeout=3).status_code == 200, timeout=60)
            clock(720)
            assert a.post("/agent/memories", json=body).json() == result
            assert recall(a, "Who enjoys artistic painting?")[0]["id"] == result["id"]
            legacy_id, legacy_request = str(uuid.uuid4()), str(uuid.uuid4())
            legacy_text = "Maya Painter visited Common Ground."
            sql(f"INSERT INTO agent_memories(id,agent_id,request_id,kind,content,entities,source_events,world_minute) VALUES('{legacy_id}','{aid}','{legacy_request}','episodic','{legacy_text}','[]','[{source}]',0);")
            wait_for(lambda: sql(f"SELECT embedding IS NOT NULL FROM agent_memories WHERE id='{legacy_id}';") == "t", timeout=40)
            tags = json.loads(sql(f"SELECT entities FROM agent_memories WHERE id='{legacy_id}';"))
            assert bid in tags and "coffee" in tags
            retry = a.post("/agent/memories", json={"requestId": legacy_request, "kind": "episodic", "content": legacy_text, "entities": [], "sourceEvents": [source]})
            assert retry.status_code == 200 and retry.json()["id"] == legacy_id, retry.text
            legacy_id, legacy_request = str(uuid.uuid4()), str(uuid.uuid4())
            legacy_text = "Maya Painter visited Common Ground."
            sql(f"INSERT INTO agent_memories(id,agent_id,request_id,kind,content,entities,source_events,world_minute) VALUES('{legacy_id}','{aid}','{legacy_request}','episodic','{legacy_text}','[]','[{source}]',0);")
            wait_for(lambda: sql(f"SELECT embedding IS NOT NULL FROM agent_memories WHERE id='{legacy_id}';") == "t", timeout=40)
            tags = json.loads(sql(f"SELECT entities FROM agent_memories WHERE id='{legacy_id}';"))
            assert bid in tags and "coffee" in tags
            retry = a.post("/agent/memories", json={"requestId": legacy_request, "kind": "episodic", "content": legacy_text, "entities": [], "sourceEvents": [source]})
            assert retry.status_code == 200 and retry.json()["id"] == legacy_id, retry.text
        finally:
            a.close(); b.close(); clock(720)


def test_hosted_decision_uses_durable_memory_and_saves_both_kinds():
    clock(720)
    with owner_client() as owner:
        aid, agent = create(owner, "Creative Recall", "Curious")
        try:
            home = agent.get("/agent/observe").json()["self"]["home"]
            remember(agent, "I prefer to practice watercolor painting in the garden.", entities=[home, "garden"])
            # Age the memory by a simulated month; the immediate situation still retrieves it.
            clock(30 * 1440 + 720)
            response = owner.put(f"/owner/agents/{aid}/runtime", json={"provider": "openai", "model": "gpt-4.1-mini-memory-test", "apiKey": "test-openai-key"})
            assert response.status_code == 200, response.text
            wait_for(lambda: owner.get(f"/owner/agents/{aid}").json()["actions"], timeout=30)
            assert sql(f"SELECT command->>'target' FROM agent_actions WHERE agent_id='{aid}' ORDER BY world_minute DESC LIMIT 1;") == "garden"
            wait_for(lambda: int(sql(f"SELECT count(DISTINCT kind) FROM agent_memories WHERE agent_id='{aid}';")) == 2, timeout=20)
            assert sql(f"SELECT bool_and(embedding IS NOT NULL) FROM agent_memories WHERE agent_id='{aid}';") == "t"
            owner.put(f"/owner/agents/{aid}/pause", json={"paused": True})
        finally:
            agent.close(); clock(720)


def test_economy_restock_funds_real_wages_and_survives_restart():
    clock(720)
    with owner_client() as owner:
        aid, agent = create(owner, "Paid Resident", "Practical")
        try:
            # Exhausted-world upgrade scenario. No mutation targets the main project.
            sql("""UPDATE world_state SET data=jsonb_set(jsonb_set(data,'{accounts}',
                (SELECT jsonb_object_agg(key,CASE WHEN key='housing' THEN '40000'::jsonb ELSE '0'::jsonb END)
                 FROM jsonb_each(data->'accounts'))),'{buildings}',
                (SELECT jsonb_object_agg(key,jsonb_set(value,'{stock}',
                    (SELECT coalesce(jsonb_object_agg(key,'0'::jsonb),'{}'::jsonb) FROM jsonb_each(value->'stock'))))
                 FROM jsonb_each(data->'buildings'))) WHERE id=1;""")
            day = (int(sql("SELECT data#>>'{economy,lastDay}' FROM world_state;")) // 7 + 1) * 7
            clock(day * 1440 + 359, False)
            wait_for(lambda: int(sql("SELECT data#>>'{economy,lastDay}' FROM world_state;")) >= day)
            clock(day * 1440 + 360)
            summary = owner.get("/world").json()["economy"]
            assert summary["subsidyTodayCents"] > 0 and summary["nextRestockMinute"] == (day + 1) * 1440 + 360
            assert sql("SELECT data#>>'{buildings,grocery,stock,meal}' FROM world_state;") == "100"
            assert sql("SELECT data#>>'{accounts,housing}' FROM world_state;") == "0"
            assert int(sql("SELECT count(*) FROM transactions WHERE reason='city_revenue';")) > 0
            clock(day * 1440 + 480)  # Grocery purchases require public opening hours.
            teleport_test_resident(aid, "grocery")
            assert act(agent, "BUY", target="grocery", item="meal", quantity=1)["status"] == "completed"
            assert act(agent, "EAT", item="meal")["status"] == "completed"
            teleport_test_resident(aid, "office")
            assert act(agent, "APPLY_JOB", target="job-office")["status"] == "completed"
            assert act(agent, "ACCEPT_JOB", target="job-office")["status"] == "completed"
            clock(day * 1440 + 540)
            before = agent.get("/agent/observe").json()["self"]["money"]
            work = act(agent, "WORK"); assert work["status"] == "accepted"
            clock(day * 1440 + 599, False)
            wait_for(lambda: agent.get(f"/agent/actions/{work['id']}").json()["status"] == "completed")
            clock(day * 1440 + 600)
            assert agent.get("/agent/observe").json()["self"]["money"] == before + 2000
            count = sql("SELECT count(*) FROM transactions WHERE reason LIKE 'restock:%' OR reason='city_subsidy';")
            subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=30)
            wait_for(lambda: httpx.get(BASE + "/world", timeout=3).status_code == 200, timeout=60)
            clock(day * 1440 + 600, False); time.sleep(.15); clock(day * 1440 + 600)
            assert sql("SELECT count(*) FROM transactions WHERE reason LIKE 'restock:%' OR reason='city_subsidy';") == count
            assert sql("SELECT data#>>'{buildings,grocery,stock,meal}' FROM world_state;") == "99"
            clock((day + 1) * 1440 + 359, False)
            wait_for(lambda: int(sql("SELECT data#>>'{economy,lastDay}' FROM world_state;")) >= day + 1)
            clock((day + 1) * 1440 + 360)
            assert sql("SELECT data#>>'{buildings,grocery,stock,meal}' FROM world_state;") == "100"
        finally:
            agent.close(); clock(720)


