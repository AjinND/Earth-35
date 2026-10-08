import json
from types import SimpleNamespace
import httpx
import pytest
from runtime.agent import Provider, execute_action, night_fallback, decision_due, relevant_memories, decision_observation, ACTION_TYPES, TOOLS, WORLD_CREATION_ADVICE, CAREER_ADVICE, FINANCING_AND_PLACEMENT_ADVICE
from runtime.client import Earth35


def test_decision_context_bounds_authored_history_without_modifying_saved_observation():
    exercises = [{"id": f"reason-{i}", "analysis": "Authored reasoning"} for i in range(20)]
    creations = [{"id": f"work-{i}", "content": "Authored draft"} for i in range(20)]
    knowledge = {"science": {"points": 28, "concepts": ["Controlled comparisons reduce confounding."]}}
    observation = {"self": {"name": "Alex", "knowledge": knowledge,
                            "reasoning": {entry["id"]: entry for entry in exercises},
                            "creations": {entry["id"]: entry for entry in creations}},
                   "library": {"catalog": [{"id": "science-method"}], "reasoning": exercises, "creations": creations}}
    compact = decision_observation(observation)
    assert "reasoning" not in compact["self"] and "creations" not in compact["self"]
    assert compact["self"]["knowledge"] == knowledge
    assert compact["library"]["reasoning"] == exercises[:5]
    assert compact["library"]["creations"] == creations[:5]
    assert compact["library"]["catalog"] == observation["library"]["catalog"]
    assert len(observation["self"]["reasoning"]) == len(observation["self"]["creations"]) == 20
    assert len(observation["library"]["reasoning"]) == len(observation["library"]["creations"]) == 20
    assert compact is not observation and compact["self"] is not observation["self"]


def test_decision_context_supports_observations_before_any_authored_history():
    observation = {"self": {"name": "Alex"}, "library": {"catalog": []}}
    assert decision_observation(observation)["library"] == {"catalog": [], "reasoning": [], "creations": []}
    assert "library" not in decision_observation({"self": {"name": "Alex"}})


def test_project_context_keeps_real_evidence_and_interests_without_duplicate_unbounded_history():
    findings = [{"id": str(i), "result": f"Measured energy before {i}, after {i+20}", "evidence": {"before": i, "after": i+20}} for i in range(20)]
    innovations = {"projects": [{"id": "project-1", "research": findings}], "interests": ["science"],
                   "invitations": [{"id": "invite-1", "status": "pending"}],
                   "products": [{"id": "product:project-1", "effects": "energy +20"}],
                   "businesses": [{"id": "business-1", "stock": 3, "balanceCents": 18000}],
                   "interestedResidents": [{"id": "peer", "interests": ["science"]}]}
    observation = {"self": {"interests": ["science"], "researchFindings": {entry["id"]: entry for entry in findings}}, "innovations": innovations}
    compact = decision_observation(observation)
    assert "researchFindings" not in compact["self"]
    assert compact["self"]["interests"] == ["science"]
    assert compact["innovations"]["projects"][0]["research"] == findings[-8:]
    for field in ["interests", "invitations", "products", "businesses", "interestedResidents"]:
        assert compact["innovations"][field] == innovations[field]
    assert len(observation["innovations"]["projects"][0]["research"]) == 20
    assert compact["innovations"]["projects"][0] is not observation["innovations"]["projects"][0]


@pytest.mark.parametrize("activity", ["RESEARCH_PROJECT", "BUILD_PROJECT", "PRODUCE_PRODUCT", "BUILD_CONSTRUCTION", "PLACE_OBJECT", "MOVE_OBJECT"])
def test_project_activities_do_not_trigger_another_model_action(activity):
    observation = {"self": {"paused": False, "pending": {"type": activity}},
                   "social": {"reconsider": True}, "travel": {"reconsider": True}}
    assert not decision_due(observation)


@pytest.mark.parametrize("action", ["DECLARE_INTEREST", "START_PROJECT", "INVITE_PROJECT", "ACCEPT_PROJECT", "DECLINE_PROJECT", "RESEARCH_PROJECT", "BUILD_PROJECT", "USE_PRODUCT", "ENDORSE_BUSINESS", "LAUNCH_BUSINESS", "FUND_BUSINESS", "SET_PRODUCT_PRICE", "PRODUCE_PRODUCT", "WITHDRAW_PROFIT"])
def test_project_and_business_commands_reach_world_unchanged(action):
    sent = []
    payload = {"action": action, "target": "actual-target", "item": "actual-item", "message": "A concrete project proposal", "quantity": 2, "amount": 10000}
    client = SimpleNamespace(action=lambda **parameters: sent.append(parameters) or {"status": "accepted"}, night_routine=lambda: None)
    assert action in ACTION_TYPES
    assert action in next(tool for tool in TOOLS if tool["name"] == "act")["parameters"]["properties"]["action"]["enum"]
    assert execute_action(client, payload) == {"status": "accepted"}
    assert sent == [payload]


def test_project_advice_requires_consent_actual_results_and_funded_manufacture():
    assert "each agent chooses independently" in WORLD_CREATION_ADVICE
    assert "all endorsements" in WORLD_CREATION_ADVICE
    assert "No stock, customer demand or subsidy appears automatically" in WORLD_CREATION_ADVICE
    assert "before/after evidence" in WORLD_CREATION_ADVICE
    assert "continu" in WORLD_CREATION_ADVICE.lower()
    assert "distributes available profit equally to all owners" in WORLD_CREATION_ADVICE
    assert "Use plain strings" in WORLD_CREATION_ADVICE
    assert "Never report a proposal as built" in WORLD_CREATION_ADVICE


@pytest.mark.parametrize("payload", [
    {"action": "QUIT_JOB", "message": "I want work closer to my interests."},
    {"action": "START_BUSINESS", "target": "market", "item": "bakery", "message": "Morning Bread", "quantity": 0, "amount": 20000},
    {"action": "START_CONSTRUCTION", "target": "point:150,200", "item": "house", "message": "My Cottage", "amount": 90000},
    {"action": "BUILD_CONSTRUCTION", "target": "site-1"},
    {"action": "START_BUSINESS", "target": "market", "item": "bakery", "message": "Community Bakery", "amount": 20000, "loanYears": 3, "laborMode": None},
    {"action": "START_CONSTRUCTION", "target": "point:150,200", "item": "house", "message": "My Cottage", "loanYears": 5, "laborMode": "self"},
    {"action": "REPAY_LOAN", "target": "loan-1", "amount": 750},
    {"action": "PLACE_OBJECT", "target": "point:150,200", "item": "advertisement:business-1", "message": "Fresh bread daily"},
    {"action": "MOVE_OBJECT", "target": "object-1", "item": "point:151,200", "message": None},
])
def test_model_chosen_career_commands_pass_through_without_a_scripted_next_choice(payload):
    sent = []
    client = SimpleNamespace(action=lambda **parameters: sent.append(parameters) or {"status": "completed"})
    assert payload["action"] in ACTION_TYPES
    assert payload["action"] in next(tool for tool in TOOLS if tool["name"] == "act")["parameters"]["properties"]["action"]["enum"]
    assert execute_action(client, payload) == {"status": "completed"}
    assert sent == [payload]


def test_career_advice_exposes_optional_funded_paths_and_observations_remain_model_inputs():
    livelihood = {"jobs": [{"id": "new-job", "vacancies": 1}], "smallBusinesses": [{"kind": "bakery"}],
                  "construction": {"sites": [{"id": "site-1", "acceptingWorkers": True}]}}
    observation = {"self": {"job": "old-job"}, "livelihood": livelihood}
    assert decision_observation(observation)["livelihood"] == livelihood
    assert observation["self"]["job"] == "old-job"
    assert "not required steps or a prescribed sequence" in CAREER_ADVICE
    assert "independently choose whether and when" in CAREER_ADVICE
    assert "No career choice, quitting threshold" in CAREER_ADVICE
    assert "from your own funds" in CAREER_ADVICE
    assert "only completed work" in CAREER_ADVICE
    assert "measured test" in CAREER_ADVICE


def test_financing_and_placement_constraints_reach_models_with_observed_obligations():
    financing = {"offers": [{"termYears": 3, "installments": 36}],
                 "loans": [{"id": "loan-1", "outstandingCents": 12500, "nextDueMinute": 43800}],
                 "overdueCents": 0}
    objects = {"objects": [{"id": "object-1", "owner": "alex", "status": "placed"}],
               "placementCostCents": 2500, "reachMeters": 6, "rules": "Keep paths clear"}
    observation = {"self": {"id": "alex"}, "livelihood": {"financing": financing}, "worldObjects": objects}
    compact = decision_observation(observation)
    assert compact["livelihood"]["financing"] == financing
    assert compact["worldObjects"] == objects
    schema = next(tool for tool in TOOLS if tool["name"] == "act")["parameters"]
    assert schema["properties"]["loanYears"]["enum"] == [3, 5, None]
    assert schema["properties"]["laborMode"]["enum"] == ["paid", "self", None]
    assert set(schema["required"]) == set(schema["properties"])
    for text in ["only the funding shortfall", "unpaid amounts remain overdue", "No worker is assigned automatically",
                 "without paying yourself a wage", "only completed installation", "accepted travel routes"]:
        assert text in FINANCING_AND_PLACEMENT_ADVICE


def test_reference_runtime_summarizes_a_pending_encounter_before_ordinary_actions(monkeypatch):
    from runtime.agent import run
    calls = []
    job = {"episode": 42, "throughEvent": 45, "otherId": "maya", "otherName": "Maya", "previousSummary": "", "turns": [],
           "reflections": [{"id": "recap", "content": "We talked about plants."}, {"id": "insight", "content": "I prefer studying with Maya."}]}
    def call(method, path, body=None):
        calls.append((method, path, body))
        return {"pending": [job]} if method == "GET" else {}
    client = SimpleNamespace(observe=lambda cursor: {"self": {"name": "Alex", "paused": False, "pending": None}},
                             call=call, night_routine=lambda: None, close=lambda: None)
    def summarize(context):
        assert context["participant"] == "Alex" and context["conversation"] == job
        return json.dumps({"summary": "We discussed gardening in a friendly exchange, without making firm plans.", "approvedReflections": ["insight"]}), 20
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(summarize=summarize))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    saved = next(body for method, path, body in calls if path == "/agent/conversation-summary/42")
    assert saved["throughEvent"] == 45 and "gardening" in saved["summary"]
    assert saved["reviewedReflections"] == ["recap", "insight"] and saved["approvedReflections"] == ["insight"]
    assert any(path == "/agent/usage" for _, path, _ in calls)


def test_reference_runtime_does_not_acknowledge_invalid_summaries(monkeypatch):
    from runtime.agent import run
    calls = []
    def call(method, path, body=None):
        calls.append(path)
        return {"pending": [{"episode": 42, "throughEvent": 45}]} if method == "GET" else {}
    client = SimpleNamespace(observe=lambda cursor: {"self": {"name": "Alex", "paused": False, "pending": None}},
                             call=call, night_routine=lambda: None, close=lambda: None)
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(summarize=lambda _: ('{"summary":""}', 20)))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert "/agent/conversation-summary/42" not in calls


def test_reference_runtime_retries_when_review_approves_an_unknown_proposal(monkeypatch):
    from runtime.agent import run
    calls = []
    def call(method, path, body=None):
        calls.append(path)
        return {"pending": [{"episode": 42, "throughEvent": 45, "reflections": [{"id": "mine"}]}]} if method == "GET" else {}
    client = SimpleNamespace(observe=lambda cursor: {"self": {"name": "Alex", "paused": False, "pending": None}},
                             call=call, night_routine=lambda: None, close=lambda: None)
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(summarize=lambda _: ('{"summary":"A neutral chat.","approvedReflections":["someone-elses"]}', 20)))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert "/agent/conversation-summary/42" not in calls


def test_reference_runtime_carries_closed_chat_and_shared_travel_into_the_next_decision(monkeypatch):
    from runtime.agent import run
    captured = {}
    observation = {
        "worldMinute": 1184,
        "self": {"id": "test1", "name": "Test1", "personality": "Sociable", "goals": "Enjoy the evening",
                 "location": "courtyard", "paused": False, "pending": None,
                 "knowledge": {"science": {"studyMinutes": 30, "concepts": ["An observation records what is noticed."]}}},
        "locations": [{"id": "courtyard", "name": "Lantern Courtyard"}],
        "nearby": [{"id": "test", "name": "Test"}], "events": [],
        "conversations": [{"other": "test", "status": "closed", "turns": [
            {"speaker": "test1", "message": "Here are the coffees! Ready to head to the Lantern Courtyard?", "minute": 1094, "location": "bistro"},
            {"speaker": "test", "message": "Ready! Let's head to the Lantern Courtyard.", "minute": 1096, "location": "bistro"}],
            "travel": [{"resident": person, "origin": "bistro", "destination": "courtyard", "departedMinute": 1097,
                        "arrivedMinute": 1184, "status": "completed"} for person in ["test1", "test"]]}],
    }
    client = SimpleNamespace(observe=lambda cursor: observation, recall=lambda *args, **kwargs: [],
                             night_routine=lambda: None, call=lambda *args: {}, close=lambda: None)
    def decide(instructions, context):
        captured.update(instructions=instructions, context=context)
        return [], 10
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    thread = captured["context"]["observation"]["conversations"][0]
    assert len(thread["turns"]) == 2 and len(thread["travel"]) == 2
    assert captured["context"]["observation"]["events"] == []
    assert "not a conversational reset" in captured["instructions"]
    assert "continue that outing on arrival" in captured["instructions"]
    assert "respond to latestTurn" in captured["instructions"]
    assert "acknowledge the farewell" in captured["instructions"]
    assert "END_CHAT speaks this message and closes the chat atomically" in captured["instructions"]
    assert "optional honest reason" in captured["instructions"]
    assert "farewell cue" in captured["instructions"] and "sign-off" in captured["instructions"]
    assert "abrupt departures are rejected" in captured["instructions"]
    assert "world removes it automatically" in captured["instructions"]
    assert captured["context"]["observation"]["self"]["knowledge"]["science"]["studyMinutes"] == 30
    assert "Use those concepts naturally" in captured["instructions"]
    assert "At the library, prioritize gaining knowledge, READ_BOOK and BORROW_BOOK; conversation is secondary" in captured["instructions"]
    assert "observation.seating" in captured["instructions"]
    assert "Sitting is optional" in captured["instructions"]
    from runtime.agent import ACTION_TYPES
    assert {"READ_BOOK", "BORROW_BOOK", "RETURN_BOOK", "REASON", "CREATE_WORK"}.issubset(ACTION_TYPES)
    assert "Include each cited concept verbatim" in captured["instructions"]
    assert "Quote at least one concept" in captured["instructions"]
    assert "does not verify correctness" in captured["instructions"]
    assert CAREER_ADVICE in captured["instructions"]


def test_openai_adapter_maps_one_structured_action_without_storing_context():
    captured = {}
    def create(**kwargs):
        captured.update(kwargs)
        return SimpleNamespace(output=[SimpleNamespace(type="function_call", name="act", arguments=json.dumps({"action":"MOVE","target":"park"}))], usage=SimpleNamespace(total_tokens=37))
    provider = Provider.__new__(Provider)
    provider.kind, provider.model, provider.sdk = "openai", "owner-model", SimpleNamespace(responses=SimpleNamespace(create=create))
    calls, tokens = provider.decide("owner instructions", {"worldMinute": 500})
    assert calls == [("act", {"action":"MOVE", "target":"park"})]
    assert tokens == 37
    assert captured["store"] is False
    assert captured["model"] == "owner-model"
    assert all(tool["strict"] for tool in captured["tools"])


def test_claude_adapter_maps_client_tool_call_and_usage():
    captured = {}
    def create(**kwargs):
        captured.update(kwargs)
        return SimpleNamespace(content=[SimpleNamespace(type="tool_use", name="act", input={"action":"TALK","target":"maya"})], usage=SimpleNamespace(input_tokens=50, output_tokens=9))
    provider = Provider.__new__(Provider)
    provider.kind, provider.model, provider.sdk = "claude", "owner-model", SimpleNamespace(messages=SimpleNamespace(create=create))
    calls, tokens = provider.decide("owner instructions", {"worldMinute": 500})
    assert calls == [("act", {"action":"TALK", "target":"maya"})]
    assert tokens == 59
    assert next(t for t in captured["tools"] if t["name"] == "act")["input_schema"]["properties"]["action"]["enum"]


def test_protocol_client_retries_the_same_action_request(monkeypatch):
    sent = []
    def handler(request):
        sent.append(request)
        return httpx.Response(503 if len(sent) == 1 else 200, json={"status":"completed"})
    monkeypatch.setattr("runtime.client.time.sleep", lambda _: None)
    client = Earth35(token="test-token")
    client.http.close()
    client.http = httpx.Client(base_url="http://localhost:8080/v1", headers={"X-E35-Client":"agent","Authorization":"Bearer test-token"}, transport=httpx.MockTransport(handler))
    try:
        assert client.action("LOOK")["status"] == "completed"
        assert len(sent) == 2
        assert sent[0].content == sent[1].content
        assert sent[0].headers["Authorization"] == "Bearer test-token"
    finally:
        client.close()


def test_recall_query_keeps_the_immediate_place_people_and_destination_before_long_goals():
    captured = {}
    def recall(query, **parameters):
        captured.update(query=query, **parameters)
        return [{"content": "Maya enjoys painting"}]
    client = SimpleNamespace(recall=recall)
    observation = {"self": {"location": "park", "goals": "x" * 2000, "pending": {"target": "garden"}},
                   "locations": [{"id": "park", "name": "Central Park"}], "nearby": [{"id": "maya", "name": "Maya"}]}
    assert relevant_memories(client, observation)[0]["content"] == "Maya enjoys painting"
    assert len(captured["query"]) == 300 and captured["query"].startswith("Central Park")
    assert captured["entities"] == ["park", "maya", "garden"] and captured["limit"] == 10


def test_idle_decisions_use_the_night_default_but_planned_actions_still_run():
    actual = []
    fallback = {"status": "accepted", "result": "Going home"}
    client = SimpleNamespace(night_routine=lambda: fallback, action=lambda **parameters: actual.append(parameters))
    assert execute_action(client, {"action": "LOOK"}) == fallback
    assert actual == []
    execute_action(client, {"action": "TALK", "target": "maya"})
    assert actual == [{"action": "TALK", "target": "maya"}]
    client.night_routine = lambda: None  # Daytime or a declared night plan.
    execute_action(client, {"action": "REST"})
    assert actual[-1] == {"action": "REST"}


def test_provider_fallback_uses_the_world_and_tolerates_an_unavailable_world():
    assert night_fallback(SimpleNamespace(night_routine=lambda: {"status": "accepted"})) == {"status": "accepted"}
    def unavailable():
        raise RuntimeError("private network context")
    assert night_fallback(SimpleNamespace(night_routine=unavailable)) is None


def test_protocol_client_sends_private_plans_and_reads_nullable_routine_actions():
    sent = []
    def handler(request):
        sent.append(request)
        return httpx.Response(200, json={"ok": True} if request.method == "PUT" else {"action": None})
    client = Earth35(token="test-token")
    client.http.close()
    client.http = httpx.Client(base_url="http://localhost:8080/v1", transport=httpx.MockTransport(handler))
    try:
        assert client.plan_night("Meet a friend", 1500) == {"ok": True}
        assert json.loads(sent[0].content) == {"reason": "Meet a friend", "until": 1500}
        assert sent[0].url.path == "/v1/agent/night-plan"
        assert client.night_routine() is None
        assert sent[1].url.path == "/v1/agent/night-routine"
    finally:
        client.close()


def test_daily_preferences_are_submitted_through_the_agent_endpoint():
    sent = []
    client = Earth35(token="test-token")
    client.http.close()
    client.http = httpx.Client(base_url="http://localhost:8080/v1", transport=httpx.MockTransport(lambda request: (sent.append(request) or httpx.Response(200, json={"ok": True}))))
    try:
        assert client.plan_routine(330, 1290, "jogging") == {"ok": True}
        assert sent[0].url.path == "/v1/agent/routine"
        assert json.loads(sent[0].content) == {"wakeMinute": 330, "sleepMinute": 1290, "morningActivity": "jogging"}
    finally:
        client.close()


def test_travel_review_reaches_the_model_without_interrupting_other_actions():
    observation = {"self": {"paused": False, "pending": {"type": "MOVE"}}, "travel": {"reconsider": False}}
    assert not decision_due(observation)
    observation["travel"]["reconsider"] = True
    assert decision_due(observation)
    observation["self"]["paused"] = True
    assert not decision_due(observation)
    assert not decision_due({"self": {"paused": False, "pending": {"type": "WORK"}}})


def test_social_encounters_wake_a_walking_decision_but_never_interrupt_work_or_sleep():
    observation = {"self": {"paused": False, "pending": {"type": "MOVE"}},
                   "travel": {"reconsider": False}, "social": {"reconsider": True}}
    assert decision_due(observation)
    observation["social"]["reconsider"] = False
    assert not decision_due(observation)
    observation["social"]["reconsider"] = True
    for activity in ["WORK", "SLEEP", "RIDE_BUS", "READ_BOOK"]:
        observation["self"]["pending"]["type"] = activity
        assert not decision_due(observation)
    observation["self"]["pending"]["type"] = "RUN"
    observation["self"]["paused"] = True
    assert not decision_due(observation)


@pytest.mark.parametrize("payload", [
    {"action": "CALL_OUT", "target": "passing-stranger", "message": "Hello! Do you know where the market is?"},
    {"action": "ACKNOWLEDGE", "target": "caller", "message": "Hello!"},
    {"action": "ACKNOWLEDGE", "target": "caller", "message": None},
    {"action": "DECLINE_CALL", "target": "caller", "message": None},
])
def test_public_call_choices_are_model_available_and_reach_the_world_without_automatic_followups(payload):
    sent = []
    client = SimpleNamespace(action=lambda **parameters: sent.append(parameters) or {"status": "completed"})
    assert payload["action"] in ACTION_TYPES
    assert payload["action"] in next(tool for tool in TOOLS if tool["name"] == "act")["parameters"]["properties"]["action"]["enum"]
    assert execute_action(client, payload) == {"status": "completed"}
    assert sent == [payload]


@pytest.mark.parametrize("activity", ["MOVE", "RUN", "REST", "WAIT_RAIN", "WAIT_BUS"])
def test_heard_pending_calls_offer_a_choice_during_walking_rest_and_waits(activity):
    observation = {"self": {"id": "listener", "paused": False, "pending": {"type": activity}},
                   "social": {"reconsider": False, "calls": [{"caller": "stranger", "listener": "listener", "status": "pending"}]}}
    assert decision_due(observation)
    observation["self"]["paused"] = True
    assert not decision_due(observation)


@pytest.mark.parametrize("status", ["acknowledged", "declined", "ignored", "expired"])
def test_resolved_calls_do_not_keep_interrupting_a_chosen_journey(status):
    observation = {"self": {"id": "listener", "paused": False, "pending": {"type": "MOVE"}},
                   "social": {"reconsider": False, "calls": [{"caller": "stranger", "listener": "listener", "status": status}]}}
    assert not decision_due(observation)


def test_outgoing_calls_do_not_wake_the_caller_or_interrupt_busy_activities():
    observation = {"self": {"id": "caller", "paused": False, "pending": {"type": "REST"}},
                   "social": {"reconsider": False, "calls": [{"caller": "caller", "listener": "listener", "status": "pending"}]}}
    assert not decision_due(observation)
    observation["self"]["id"] = "listener"
    for activity in ["WORK", "SLEEP", "BOARD_BUS", "RIDE_BUS", "READ_BOOK", "RESEARCH_PROJECT", "BUILD_PROJECT", "PRODUCE_PRODUCT", "BUILD_CONSTRUCTION"]:
        observation["self"]["pending"]["type"] = activity
        assert not decision_due(observation)


def test_reference_runtime_presents_a_heard_call_to_the_model_and_performs_only_its_chosen_acknowledgment(monkeypatch):
    from runtime.agent import run
    captured, sent = {}, []
    pending = {"type": "REST", "id": "chosen-rest"}
    call = {"id": "call-1", "caller": "stranger", "listener": "alex", "message": "Hello there!",
            "minute": 800, "expires": 810, "status": "pending", "response": None, "responseMinute": None}
    social = {"reconsider": False, "encounters": [{"other": "stranger", "name": "Sam", "metBefore": False,
                                                 "relationship": None, "distance": 15, "available": True}], "calls": [call]}
    observation = {"worldMinute": 800, "self": {"id": "alex", "name": "Alex", "personality": "Quiet", "goals": "Enjoy a break",
                                               "location": "park", "paused": False, "pending": pending},
                   "locations": [{"id": "park", "name": "Central Park"}], "nearby": [], "social": social, "events": []}
    chosen = {"action": "ACKNOWLEDGE", "target": "stranger", "message": None, "item": None, "quantity": None, "amount": None}
    client = SimpleNamespace(observe=lambda cursor: observation, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(parameters) or {"status": "completed"},
                             night_routine=lambda: None, call=lambda *args: {}, close=lambda: None)
    def decide(instructions, context):
        captured.update(instructions=instructions, context=context)
        return [("act", chosen)], 10
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert captured["context"]["observation"]["social"] == social
    assert captured["context"]["observation"]["social"]["encounters"][0]["metBefore"] is False
    assert "proximity creates no obligation" in captured["instructions"]
    assert "turn toward them briefly without stopping or agreeing to talk" in captured["instructions"]
    assert "until they independently choose TALK to engage" in captured["instructions"]
    assert sent == [chosen]
    assert observation["self"]["pending"] == pending and social["calls"][0]["status"] == "pending"


def test_expired_pending_public_calls_do_not_wake_resting_residents():
    observation = {"worldMinute": 810, "self": {"id": "listener", "paused": False, "pending": {"type": "REST"}},
                   "social": {"calls": [{"caller": "caller", "listener": "listener", "status": "pending", "expires": 810}]}}
    assert not decision_due(observation)
    observation["social"]["calls"][0]["expires"] = 811
    assert decision_due(observation)
