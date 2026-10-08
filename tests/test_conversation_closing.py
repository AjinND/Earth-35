"""Spoken closings and their memory episodes in the disposable test world."""
import uuid
from test_city_life import create, act, teleport_test_resident, sql
from test_conversation_memory import pending
from test_memory_economy import owner_client
from test_night_routine import clock


def test_closing_is_public_idempotent_and_keeps_the_acknowledgment_in_one_episode():
    clock(720)
    with owner_client() as owner:
        aid, a = create(owner, "Closing Alex", "Sociable")
        bid, b = create(owner, "Closing Maya", "Warm")
        try:
            for ident in [aid, bid]:
                teleport_test_resident(ident, "park")
            a.get("/agent/observe"); b.get("/agent/observe")
            assert act(a, "TALK", target=bid, message="How was your day?")["status"] == "completed"
            assert act(a, "MOVE", target="office")["status"] == "rejected"
            assert act(a, "END_CHAT")["status"] == "rejected"
            assert len(a.get("/agent/observe").json()["conversations"][0]["turns"]) == 1
            goodbye = "I've got some things to do. Catch up with you later! Bye!"
            payload = {"requestId": str(uuid.uuid4()), "action": "END_CHAT", "target": bid, "message": goodbye}
            first = a.post("/agent/action", json=payload).json()
            assert first["status"] == "completed"
            assert a.post("/agent/action", json=payload).json() == first
            assert a.post("/agent/action", json={**payload, "message": "Different goodbye"}).status_code == 409
            thread = b.get("/agent/observe").json()["conversations"][0]
            assert thread["status"] == "closed" and thread["turns"][-1]["message"] == goodbye
            public = owner.get("/world").json()["events"]
            assert sum(e["actorId"] == aid and e["message"].endswith(goodbye) for e in public) == 1
            assert act(b, "END_CHAT", target=aid, message="Talk soon, Alex! Take care.")["status"] == "completed"
            assert act(a, "END_CHAT", target=bid, message="Another goodbye")["status"] == "rejected"
            assert act(b, "END_CHAT", target=aid, message="Another goodbye")["status"] == "rejected"
            job = pending(a)[0]
            assert len(job["turns"]) == 3 and len(pending(b)[0]["turns"]) == 3
            assert sql(f"SELECT count(DISTINCT conversation_episode) FROM world_events WHERE kind='conversation' AND (actor_id='{aid}' OR target_id='{aid}');") == "1"
            # A later topic is a new episode, while the closing remains in the first.
            assert act(b, "TALK", target=aid, message="One more thing: are you attending tomorrow's festival?")["status"] == "completed"
            assert act(a, "END_CHAT", message="I haven't decided yet. See you around! Bye!")["status"] == "completed"
            assert sql(f"SELECT count(DISTINCT conversation_episode) FROM world_events WHERE kind='conversation' AND (actor_id='{aid}' OR target_id='{aid}');") == "2"
            assert act(a, "MOVE", target="office")["status"] == "accepted"
        finally:
            a.close(); b.close(); clock(720)
