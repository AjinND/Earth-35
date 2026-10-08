"""Real reading -> knowledge -> reasoning -> creation on earth35-test:8081 only."""
from collections import Counter, defaultdict
from contextlib import contextmanager
import json
import subprocess
import uuid

import httpx

from test_city_life import BASE, COMPOSE, act, create, sql, teleport_test_resident
from test_memory_economy import owner_client, wait_for
from test_night_routine import clock


assert BASE == "http://127.0.0.1:8081/v1"
assert COMPOSE[:4] == ["docker", "compose", "-p", "earth35-test"]


def freeze_clock():
    sql("UPDATE world_state SET last_tick_at=clock_timestamp()+interval '1 hour' WHERE id=1;")


def resume_clock():
    sql("UPDATE world_state SET last_tick_at=clock_timestamp()-interval '1 second' WHERE id=1;")


def observe(agent):
    response = agent.get("/agent/observe")
    assert response.status_code == 200, response.text
    return response.json()


def post_command(agent, body):
    response = agent.post("/agent/action", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def fresh_condition(agent_id):
    # Test setup changes physical needs only; study, concepts and artifacts are earned via HTTP.
    sql(f"UPDATE world_state SET data=jsonb_set(data,'{{agents,{agent_id}}}',"
        f"data#>'{{agents,{agent_id}}}' || '{{\"energy\":100,\"hunger\":10}}'::jsonb) WHERE id=1;")


@contextmanager
def library_resident(name):
    clock(540)
    freeze_clock()
    with owner_client() as owner:
        agent_id, agent = create(owner, name, "Curious and methodical")
        try:
            teleport_test_resident(agent_id, "library")
            fresh_condition(agent_id)
            yield owner, agent_id, agent
        finally:
            freeze_clock()
            agent.close()


def read_elapsed(agent_id, agent, book_id, minutes):
    """Let every requested minute execute through WorldClock; never set study or skip time."""
    remaining = minutes
    while remaining:
        freeze_clock()
        fresh_condition(agent_id)
        before = observe(agent)
        assert before["self"]["pending"] is None
        old_minutes = before["self"]["bookStudies"].get(book_id, {}).get("readingMinutes", 0)
        quantity = min(100, remaining)
        reading = act(agent, "READ_BOOK", target=book_id, quantity=quantity)
        assert reading["status"] == "accepted", reading
        # Keep the acceptance boundary frozen before allowing real scheduled ticks.
        freeze_clock()
        accepted = observe(agent)
        assert accepted["self"]["bookStudies"].get(book_id, {}).get("readingMinutes", 0) == old_minutes
        assert accepted["self"]["pending"]["type"] == "READ_BOOK"
        resume_clock()
        try:
            wait_for(lambda: agent.get(f"/agent/actions/{reading['id']}").json()["status"] == "completed", timeout=60)
        finally:
            freeze_clock()
        completed = observe(agent)
        assert completed["self"]["pending"] is None
        assert completed["self"]["bookStudies"][book_id]["readingMinutes"] == old_minutes + quantity
        assert completed["worldMinute"] >= before["worldMinute"] + quantity
        remaining -= quantity


def authored_reasoning(concepts):
    marker = str(uuid.uuid4())
    problem = f"How can a neighborhood water testing studio compare useful services? Private problem {marker}."
    analysis = (f"{concepts[0]} {concepts[1]} We will log measured results before interpreting them, "
                "then compare identical sample volumes with one treatment changed at a time. "
                f"A pilot will test whether the service gives reproducible results. Private analysis {marker}.")
    return {"problem": problem, "analysis": analysis, "concepts": concepts[:2]}


def test_catalog_has_substantive_depth_and_subject_progression_metadata():
    with library_resident("Library catalog reader") as (_, _, agent):
        observation = observe(agent)
        catalog = observation["library"]["catalog"]
        assert len(catalog) == 110
        assert len({book["id"] for book in catalog}) == 110
        grouped = defaultdict(list)
        for book in catalog:
            grouped[book["subject"]].append(book)
            assert book["description"] and book["lessonCount"] == 4
            minutes = {"introductory": 30, "intermediate": 40, "advanced": 60}[book["depth"]]
            assert book["lessonMinutes"] == minutes
            assert book["masteryMinutes"] == minutes * 4
            assert book["prerequisitePoints"] == {"introductory": 0, "intermediate": 12, "advanced": 28}[book["depth"]]
            assert book["unlocked"] == (book["depth"] == "introductory")
        assert len(grouped) == 22
        assert {"science", "technology", "business", "arts", "history"} <= grouped.keys()
        weights = {"introductory": 2, "intermediate": 3, "advanced": 4}
        for books in grouped.values():
            assert Counter(book["depth"] for book in books) == {"introductory": 2, "intermediate": 2, "advanced": 1}
            assert sum(book["lessonCount"] * weights[book["depth"]] for book in books) == 56
        assert not observation["self"]["knowledge"]


def test_possession_and_unread_subjects_cannot_bypass_learning():
    with library_resident("Library prerequisite reader") as (_, _, agent):
        advanced = act(agent, "READ_BOOK", target="science-research-design", quantity=60)
        assert advanced["status"] == "rejected" and "28 knowledge points" in advanced["result"]
        intermediate = act(agent, "READ_BOOK", target="science-measurement-models", quantity=40)
        assert intermediate["status"] == "rejected" and "12 knowledge points" in intermediate["result"]
        assert act(agent, "BORROW_BOOK", target="science-everyday")["status"] == "completed"
        assert not observe(agent)["self"]["knowledge"]
        draft = authored_reasoning(["An unread claimed concept.", "Another unread claimed concept."])
        rejected = act(agent, "REASON", target="science", message=json.dumps(draft))
        assert rejected["status"] == "rejected" and "Read books" in rejected["result"]
        rejected = act(agent, "CREATE_WORK", target="business", item="business",
                       message=json.dumps({"title": "Unlearned business", "content": "An unsupported plan. " * 10, "reasoningId": "missing"}))
        assert rejected["status"] == "rejected" and "Read books" in rejected["result"]
        assert act(agent, "RETURN_BOOK", target="science-everyday")["status"] == "completed"
        self = observe(agent)["self"]
        assert not self["knowledge"] and not self["bookStudies"]
        assert not self["reasoning"] and not self["creations"] and self["pending"] is None


def test_elapsed_learning_enables_reasoning_creation_and_survives_returns_retries_restart_privately():
    with library_resident("Library applied scientist") as (owner, agent_id, agent):
        for book in ("science-everyday", "living-systems"):
            assert act(agent, "BORROW_BOOK", target=book)["status"] == "completed"
            read_elapsed(agent_id, agent, book, 120)
        learned = observe(agent)
        knowledge = learned["self"]["knowledge"]["science"]
        assert knowledge["points"] == 16 and knowledge["maximumPoints"] == 56
        assert knowledge["studyMinutes"] == 240 and len(knowledge["concepts"]) == 8
        assert knowledge["canReason"] and not knowledge["canCreate"]
        assert knowledge["depth"] == "introductory"
        advanced = act(agent, "READ_BOOK", target="science-research-design", quantity=60)
        assert advanced["status"] == "rejected" and "28 knowledge points" in advanced["result"]
        draft = authored_reasoning(knowledge["concepts"])
        unread_subject = act(agent, "REASON", target="business", message=json.dumps(draft))
        assert unread_subject["status"] == "rejected" and "Read books" in unread_subject["result"]
        unread = "A purported lesson that this resident has never learned."
        bad_evidence = {**draft, "analysis": draft["analysis"] + " " + unread, "concepts": [draft["concepts"][0], unread]}
        assert act(agent, "REASON", target="science", message=json.dumps(bad_evidence))["status"] == "rejected"
        assert not observe(agent)["self"]["reasoning"]
        reasoning_body = {"requestId": str(uuid.uuid4()), "action": "REASON", "target": "science", "message": json.dumps(draft)}
        reasoning = post_command(agent, reasoning_body)
        assert reasoning["status"] == "completed", reasoning
        assert post_command(agent, reasoning_body) == reasoning
        assert agent.post("/agent/action", json={**reasoning_body, "message": json.dumps({**draft, "problem": draft["problem"] + " Changed."})}).status_code == 409
        proposed = {"title": "Neighborhood water testing studio", "reasoningId": reasoning["id"],
                    "content": (f"{draft['concepts'][0]} We propose a neighborhood business that records sample observations, "
                                "offers transparent comparison reports and tests customer demand through a small pilot. "
                                "The draft budget will separate equipment, staffing and recurring costs. "
                                f"We will revise the proposal after the pilot. Private business draft {uuid.uuid4()}.")}
        blocked = act(agent, "CREATE_WORK", target="science", item="business", message=json.dumps(proposed))
        assert blocked["status"] == "rejected" and "28 knowledge points" in blocked["result"]
        for book in ("science-everyday", "living-systems"):
            assert act(agent, "RETURN_BOOK", target=book)["status"] == "completed"
        assert observe(agent)["self"]["knowledge"]["science"]["points"] == 16
        assert act(agent, "BORROW_BOOK", target="science-measurement-models")["status"] == "completed"
        read_elapsed(agent_id, agent, "science-measurement-models", 160)
        applied = observe(agent)
        knowledge = applied["self"]["knowledge"]["science"]
        assert knowledge["points"] == 28 and knowledge["maximumPoints"] == 56
        assert knowledge["studyMinutes"] == 400 and len(knowledge["concepts"]) == 12
        assert knowledge["intermediateConcepts"] == 4 and knowledge["depth"] == "intermediate"
        assert knowledge["canReason"] and knowledge["canCreate"]
        assert next(book for book in applied["library"]["catalog"] if book["id"] == "science-research-design")["unlocked"]
        work_body = {"requestId": str(uuid.uuid4()), "action": "CREATE_WORK", "target": "science", "item": "business", "message": json.dumps(proposed)}
        work = post_command(agent, work_body)
        assert work["status"] == "completed", work
        assert post_command(agent, work_body) == work
        assert act(agent, "RETURN_BOOK", target="science-measurement-models")["status"] == "completed"
        private = observe(agent)
        assert private["self"]["knowledge"]["science"]["points"] == 28
        assert not private["self"]["bookLoans"]
        assert len(private["self"]["reasoning"]) == len(private["self"]["creations"]) == 1
        assert private["self"]["reasoning"][reasoning["id"]]["analysis"] == draft["analysis"]
        assert private["self"]["creations"][work["id"]]["content"] == proposed["content"]
        detail = owner.get(f"/owner/agents/{agent_id}").json()
        assert detail["state"]["creations"][work["id"]]["reasoningId"] == reasoning["id"]
        assert detail["library"]["reasoning"][0]["problem"] == draft["problem"]
        public = owner.get("/world").json()
        public_self = next(resident for resident in public["agents"] if resident["id"] == agent_id)
        assert not {"knowledge", "bookStudies", "reasoning", "creations"} & public_self.keys()
        events = owner.get("/events", params={"agent": agent_id, "limit": 100}).json()
        assert any(event["kind"] == "reasoning" for event in events)
        assert any(event["kind"] == "creation" for event in events)
        for secret in (draft["problem"], draft["analysis"], proposed["content"]):
            assert secret not in json.dumps(public)
            assert secret not in json.dumps(events)
        with owner_client() as stranger:
            assert stranger.get(f"/owner/agents/{agent_id}").status_code == 404
        assert 940 <= private["worldMinute"] < 1200
        subprocess.run(COMPOSE + ["restart", "api"], check=True, capture_output=True, timeout=30)
        wait_for(lambda: httpx.get(BASE + "/world", timeout=3).status_code == 200, timeout=60)
        freeze_clock()
        restored = observe(agent)
        assert restored["self"]["knowledge"]["science"]["points"] == 28
        assert restored["self"]["knowledge"]["science"]["studyMinutes"] == 400
        assert restored["self"]["reasoning"] == private["self"]["reasoning"]
        assert restored["self"]["creations"] == private["self"]["creations"]
        assert post_command(agent, reasoning_body) == reasoning
        assert post_command(agent, work_body) == work
