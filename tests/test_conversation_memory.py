"""Conversation summaries use the disposable test stack, never the live world."""
import json
import subprocess
import uuid
import httpx
from test_city_life import BASE, COMPOSE, sql, create, act, teleport_test_resident
from test_memory_economy import owner_client, recall, wait_for, remember
from test_night_routine import clock


def pending(agent):
    response = agent.get("/agent/conversation-summary")
    assert response.status_code == 200, response.text
    return response.json()["pending"]


def test_conversation_summaries_are_grouped_private_durable_and_not_transcripts():
    clock(720)
    with owner_client() as owner:
        aid, a = create(owner, "Conversation Alex", "Curious")
        bid, b = create(owner, "Conversation Maya", "Creative")
        _, outsider = create(owner, "Uninvolved Resident", "Quiet")
        try:
            for ident in [aid, bid]:
                teleport_test_resident(ident, "park")
            a.get("/agent/observe"); b.get("/agent/observe")
            payload = {"requestId": str(uuid.uuid4()), "action": "TALK", "target": bid,
                       "message": "I plan to study gardening at the library tomorrow."}
            first = a.post("/agent/action", json=payload).json()
            assert first["status"] == "completed"
            assert a.post("/agent/action", json=payload).json() == first
            assert act(a, "TALK", target=bid, message="Pretending you replied.")["status"] == "rejected"
            assert act(b, "TALK", target=aid, message="I would like to learn about compost too.")["status"] == "completed"
            assert pending(a) == [] and recall(a, "") == []  # Ongoing dialogue is not a memory.
            assert act(a, "END_CHAT", message="It was good talking. See you around! Take care.")["status"] == "completed"
            job = pending(a)[0]
            assert len(job["turns"]) == 3 and job["otherId"] == bid
            assert len(pending(b)[0]["turns"]) == 3 and pending(outsider) == []
            endpoint = f"/agent/conversation-summary/{job['episode']}"
            paraphrase = "We discussed learning about plants and compost at the library. The exchange was friendly, with shared interest but no firm arrangement."
            body = {"throughEvent": job["throughEvent"], "summary": paraphrase}
            assert outsider.post(endpoint, json=body).status_code == 404
            assert a.post(endpoint, json={**body, "throughEvent": 999999999}).status_code == 400
            assert a.post(endpoint, json={**body, "summary": payload["message"]}).status_code == 400
            assert a.post(endpoint, json={**body, "summary": "x" * 601}).status_code == 400
            assert len(pending(a)) == 1
            # A restart cannot lose work awaiting a model summary.
            subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=30)
            wait_for(lambda: httpx.get(BASE + "/world", timeout=3).status_code == 200, timeout=60)
            clock(720)
            assert pending(a)[0]["episode"] == job["episode"]
            subprocess.run(COMPOSE + ["stop", "embeddings"], check=True, capture_output=True, timeout=30)
            try:
                saved = a.post(endpoint, json=body)
                assert saved.status_code == 200, saved.text
                assert a.post(endpoint, json=body).json() == saved.json()
                assert pending(a) == [] and len(pending(b)) == 1
                memories = recall(a, "", entities=[bid])
                assert len(memories) == 1
                memory = memories[0]
                assert paraphrase in memory["content"] and "Conversation Maya" in memory["content"]
                assert payload["message"] not in memory["content"]
                assert len(json.loads(memory["sourceEvents"])) == 3
                assert bid in json.loads(memory["entities"])
                assert memory["worldMinute"] == 720
                assert recall(outsider, "") == [] and recall(b, "") == []
                assert sql(f"SELECT embedding IS NULL FROM agent_memories WHERE id='{memory['id']}';") == "t"
            finally:
                subprocess.run(COMPOSE + ["start", "embeddings"], check=True, capture_output=True, timeout=30)
            wait_for(lambda: sql(f"SELECT embedding IS NOT NULL FROM agent_memories WHERE id='{memory['id']}';") == "t", timeout=60)
            # A later meeting accumulates a new gist instead of overwriting the
            # first encounter or copying all its individual spoken lines.
            a.get("/agent/observe"); b.get("/agent/observe")
            assert act(a, "TALK", target=bid, message="It is good to see you again. I tried making compost.")["status"] == "completed"
            assert act(b, "TALK", target=aid, message="That sounds promising; I have been reading about soil.")["status"] == "completed"
            act(a, "END_CHAT", message="I should get going. Talk soon! Bye!")
            second = pending(a)[0]
            assert second["episode"] != job["episode"] and len(second["turns"]) == 3
            response = a.post(f"/agent/conversation-summary/{second['episode']}", json={
                "throughEvent": second["throughEvent"], "summary": "We caught up on our gardening interests and shared progress on compost and soil. The reunion was warm, without a new commitment."})
            assert response.status_code == 200, response.text
            assert len(recall(a, "", entities=[bid])) == 2
            assert any(m["id"] == memory["id"] for m in recall(a, "", entities=[bid]))
            # The hosted runtime must process its queue even if the normal decision
            # would omit optional memories. No manual remember call for this participant.
            response = owner.put(f"/owner/agents/{bid}/runtime", json={"provider": "openai", "model": "gpt-4.1-mini", "apiKey": "test-openai-key"})
            assert response.status_code == 200, response.text
            wait_for(lambda: sql(f"SELECT saved_event=through_event FROM conversation_memory_queue WHERE agent_id='{bid}' AND episode={job['episode']};") == "t", timeout=40)
            owner.put(f"/owner/agents/{bid}/pause", json={"paused": True})
            owned = owner.post(f"/owner/agents/{bid}/memories/recall", json={"query": "", "limit": 5}).json()
            assert any("gardening and compost" in m["content"] for m in owned)
            assert all(payload["message"] not in m["content"] for m in owned)
            assert all(m["id"] != memory["id"] for m in owned)
            # Summary persistence does not force reciprocal relationship labels.
            assert owner.get(f"/owner/agents/{aid}").json()["state"]["relationships"] == {}
            assert owner.get(f"/owner/agents/{bid}").json()["state"]["relationships"] == {}
            # Event rollback also rolls back its queue entry.
            before = sql("SELECT count(*) FROM conversation_memory_queue;")
            sql(f"BEGIN; INSERT INTO world_events(world_minute,kind,actor_id,target_id,message) VALUES(900,'conversation','{aid}','{bid}','rollback sentinel'); ROLLBACK;")
            assert sql("SELECT count(*) FROM conversation_memory_queue;") == before
        finally:
            a.close(); b.close(); outsider.close(); clock(720)


def test_both_perspectives_and_only_distinct_reflections_survive_review():
    clock(720)
    with owner_client() as owner:
        aid, a = create(owner, "Reflective Alex", "Curious")
        bid, b = create(owner, "Reflective Maya", "Creative")
        _, outsider = create(owner, "Reflection Outsider", "Quiet")
        try:
            for ident in [aid, bid]:
                teleport_test_resident(ident, "park")
            a.get("/agent/observe"); b.get("/agent/observe")
            act(a, "TALK", target=bid, message="The library has useful gardening books for beginners.")
            act(b, "TALK", target=aid, message="I enjoy growing herbs and would like to learn more.")
            act(a, "END_CHAT", message="Thanks for the chat. See you soon! Take care.")
            job = pending(a)[0]
            episode, source = job["episode"], job["throughEvent"]
            endpoint = f"/agent/conversation-summary/{episode}"
            a_gist = "Maya and I exchanged ideas about learning to grow plants at the library. It was a friendly chat without a firm plan."
            b_gist = "Alex suggested a place to learn about horticulture. I shared my interest in herbs; the discussion ended on a warm note."
            a_id = a.post(endpoint, json={"throughEvent": source, "summary": a_gist}).json()["id"]
            b_id = b.post(endpoint, json={"throughEvent": source, "summary": b_gist}).json()["id"]
            assert a_id != b_id and len(recall(a, "")) == len(recall(b, "")) == 1
            assert a_gist in recall(a, "")[0]["content"] and b_gist in recall(b, "")[0]["content"]
            assert recall(outsider, "") == []

            for content in ["The library has useful gardening books for beginners.", "Reflective Maya " + "x" * 600]:
                response = a.post("/agent/memories", json={"requestId": str(uuid.uuid4()), "kind": "semantic", "content": content,
                    "entities": [bid], "sourceEvents": []})
                assert response.status_code == 400, response.text

            recap_body, recap = remember(a, "Ended a friendly conversation with Reflective Maya about gardening books, then left.", "episodic", sourceEvents=[source])
            _, insight = remember(a, "I now prefer learning practical skills alongside Reflective Maya rather than studying alone.", sourceEvents=[source])
            _, repeated = remember(a, "I now prefer learning practical skills alongside Reflective Maya rather than studying alone.", sourceEvents=[source])
            # Pending proposals never enter either text or explicit vector recall/counts.
            assert len(recall(a, "")) == 1
            assert recall(a, "", embedding=[.01] * 384, embeddingModel="owner-custom-v1") == []
            assert owner.get(f"/owner/agents/{aid}").json()["memoryCount"] == 1
            review = pending(a)[0]
            reviewed = [r["id"] for r in review["reflections"]]
            assert set(reviewed) == {recap["id"], insight["id"], repeated["id"]}
            assert review["previousSummary"].endswith(a_gist)
            # The deterministic guard also catches identical insights even if a
            # model mistakenly approves both; process the preferred one first.
            reviewed = [insight["id"], repeated["id"], recap["id"]]
            body = {"throughEvent": source, "summary": a_gist, "reviewedReflections": reviewed, "approvedReflections": [insight["id"], repeated["id"]]}
            assert outsider.post(endpoint, json=body).status_code == 404
            assert a.post(endpoint, json={**body, "approvedReflections": [b_id]}).status_code == 400
            assert a.post(endpoint, json={**body, "reviewedReflections": [b_id], "approvedReflections": []}).status_code == 400
            response = a.post(endpoint, json=body)
            assert response.status_code == 200 and response.json()["id"] == a_id, response.text
            assert a.post(endpoint, json=body).json() == response.json()
            assert pending(a) == []
            active = recall(a, "", entities=[bid])
            assert {m["id"] for m in active} == {a_id, insight["id"]}
            assert owner.get(f"/owner/agents/{aid}").json()["memoryCount"] == 2
            assert a.post("/agent/memories", json=recap_body).json() == recap  # Archived retry remains idempotent.
            assert sql(f"SELECT count(*) FROM agent_memories WHERE id='{recap['id']}';") == "1"
            assert len(recall(b, "")) == 1 and pending(b) == []  # Other perspective unaffected.
            wait_for(lambda: sql(f"SELECT embedding IS NOT NULL FROM agent_memories WHERE id='{insight['id']}';") == "t", timeout=60)

            # A source-free, differently worded recap still requires review; a failed
            # or restarted provider cannot accidentally make it an active memory.
            _, source_free = remember(a, "Reflective Maya and I discussed books about growing plants. The chat was pleasant.")
            assert pending(a)[0]["reflections"][0]["id"] == source_free["id"]
            assert pending(a)[0]["previousReflections"][0]["id"] == insight["id"]
            subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=30)
            wait_for(lambda: httpx.get(BASE + "/world", timeout=3).status_code == 200, timeout=60)
            clock(720)
            assert pending(a)[0]["reflections"][0]["id"] == source_free["id"]
            assert len(recall(a, "")) == 2
            # Exercise the hosted runtime's actual review response/save path.
            assert owner.put(f"/owner/agents/{aid}/runtime", json={"provider": "openai", "model": "gpt-4.1-mini", "apiKey": "test-openai-key"}).status_code == 200
            wait_for(lambda: sql(f"SELECT reviewed AND NOT accepted FROM conversation_reflections WHERE memory_id='{source_free['id']}';") == "t", timeout=40)
            owner.put(f"/owner/agents/{aid}/pause", json={"paused": True})
            own = owner.post(f"/owner/agents/{aid}/memories/recall", json={"query": "", "limit": 5}).json()
            assert source_free["id"] not in {m["id"] for m in own}
            assert a_id in {m["id"] for m in own} and insight["id"] in {m["id"] for m in own}
            assert len(recall(b, "")) == 1
            assert owner.get(f"/owner/agents/{aid}").json()["state"]["relationships"] == {}
            assert owner.get(f"/owner/agents/{bid}").json()["state"]["relationships"] == {}
        finally:
            a.close(); b.close(); outsider.close(); clock(720)
