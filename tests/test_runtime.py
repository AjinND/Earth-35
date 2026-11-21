import json
from types import SimpleNamespace
import httpx
import pytest
from runtime.agent import Provider, execute_action, decision_due, relevant_memories, decision_observation, ACTION_TYPES, TOOLS, WORLD_CREATION_ADVICE, CAREER_ADVICE, FINANCING_AND_PLACEMENT_ADVICE
from runtime.client import Earth35
from runtime.agent import social_decision_due, SHARED_COMMITMENT_ADVICE, NIGHT_RETURN_ADVICE, PRESENCE_ADVICE


@pytest.mark.parametrize("activity", ["REST", "MOVE", "RUN", "WORK", "READ_BOOK", "WAIT_BUS", "RESEARCH_PROJECT", "BUILD_PROJECT", "PRODUCE_PRODUCT", "BUILD_CONSTRUCTION", "PLACE_OBJECT", "MOVE_OBJECT", "DO_ACTIVITY"])
def test_persistent_perception_changes_offer_decisions_during_interruptible_activities(activity):
    observation = {"self": {"id": "listener", "paused": False, "pending": {"type": activity}},
                   "cognition": {"reconsider": True}}
    assert decision_due(observation)
    observation["self"]["paused"] = True
    assert not decision_due(observation)


@pytest.mark.parametrize("activity", ["SLEEP", "BOARD_BUS", "RIDE_BUS"])
def test_cognition_cannot_interrupt_sleep_or_a_bus_in_motion(activity):
    observation = {"self": {"id": "listener", "paused": False, "pending": {"type": activity}},
                   "cognition": {"reconsider": True}}
    assert not decision_due(observation)


@pytest.mark.parametrize("action", ["CONTINUE_ACTIVITY", "PAUSE_ACTIVITY", "RESUME_ACTIVITY", "STOP_ACTIVITY"])
@pytest.mark.parametrize("activity", ["WORK", "READ_BOOK", "RESEARCH_PROJECT", "PRODUCE_PRODUCT", "BUILD_CONSTRUCTION"])
def test_awake_activity_reviews_dispatch_only_the_residents_chosen_control(action, activity):
    pending = {"id": "actual-task", "type": activity, "workMinutes": 19}
    observation = {"self": {"id": "alex", "paused": False, "pending": pending}, "cognition": {"reconsider": True}}
    parameters = {"action": action, "target": pending["id"]}
    sent = []
    client = SimpleNamespace(action=lambda **chosen: sent.append(chosen) or {"status": "completed"})
    assert decision_due(observation)
    assert action in ACTION_TYPES
    execute_action(client, parameters, observation)
    assert sent == [parameters]
    assert observation["self"]["pending"] is pending and pending["workMinutes"] == 19


def test_persistent_intention_is_visible_without_duplicating_internal_perception_state():
    cognition = {"intention": {"focus": "Study gardening"}, "noticedChanges": []}
    observation = {"self": {"id": "self", "mind": {"perceived": {"hidden-internal-key": "value"}}}, "cognition": cognition}
    compact = decision_observation(observation)
    assert compact["cognition"] == cognition
    assert "mind" not in compact["self"]
    assert "mind" in observation["self"]


def test_deferred_payroll_reaches_the_model_as_earned_unpaid_money_without_internal_accounts():
    payroll = [{"job": "job-office", "payAtMinute": 1020, "earnedCents": 2000, "workedMinutes": 60}]
    observation = {"self": {"money": 100, "workPayroll": {"internal-account": {"earnedCents": 2000}}}, "payroll": payroll}
    compact = decision_observation(observation)
    assert compact["payroll"] == payroll
    assert compact["self"]["money"] == 100
    assert "workPayroll" not in compact["self"]
    assert "workPayroll" in observation["self"]


def test_review_intention_tool_uses_the_authenticated_agent_endpoint():
    client = Earth35(token="test-token", url="http://test.invalid")
    received = []
    client.call = lambda method, path, body: received.append((method, path, body)) or {"ok": True}
    try:
        assert client.review_intention(intention=None, clearIntention=True, feeling=None, lesson=None, outcomeId=None) == {"ok": True}
        assert received[0][0:2] == ("PUT", "/agent/cognition")
        assert received[0][2]["clearIntention"] is True
        tool = next(t for t in TOOLS if t["name"] == "review_intention")
        assert set(tool["parameters"]["required"]) == {"intention", "clearIntention", "feeling", "lesson", "outcomeId", "relationship"}
    finally:
        client.close()


def test_resting_agent_can_notice_and_decline_a_conversation_opportunity():
    rest = {"type": "REST", "id": "seat-break"}
    observation = {"worldMinute": 720, "self": {"id": "listener", "paused": False, "pending": rest},
                   "social": {"reconsider": True, "encounters": [{"available": True, "decisionNeeded": True}], "calls": []}}
    assert decision_due(observation)
    assert social_decision_due(observation)
    assert observation["self"]["pending"] is rest
    observation["social"]["reconsider"] = False
    observation["social"]["encounters"][0]["decisionNeeded"] = False
    assert not decision_due(observation)
    assert not social_decision_due(observation)


@pytest.mark.parametrize("activity", ["WORK", "SLEEP", "READ_BOOK", "WAIT_BUS", "WAIT_RAIN", "RIDE_BUS"])
def test_conversation_opportunities_do_not_interrupt_exclusive_activities_or_waits(activity):
    observation = {"self": {"id": "listener", "paused": False, "pending": {"type": activity}},
                   "social": {"reconsider": True, "calls": []}}
    assert not decision_due(observation)


def test_awareness_is_passed_to_the_independent_model_without_changing_its_evidence():
    awareness = {"activity": "REST", "nearbyPeople": [{"id": "peer", "canTalk": True}],
                 "placesToConsider": [{"id": "park", "otherResidentsHere": 1}],
                 "presence": {"location": "park", "people": [{"id": "peer", "name": "Theo Brooks"}]}}
    observation = {"self": {"id": "listener"}, "awareness": awareness}
    assert decision_observation(observation)["awareness"] == awareness


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


def test_preferred_night_away_from_home_does_not_delay_memory_or_author_a_life_action(monkeypatch):
    from runtime.agent import run
    calls, actions, defaults = [], [], []
    job = {"episode": 42, "throughEvent": 45, "otherId": "maya", "otherName": "Maya", "turns": []}
    observation = {"worldMinute": 1300, "nightReturnDue": True,
                   "self": {"id": "alex", "name": "Alex", "home": "alex-home", "location": "park",
                            "paused": False, "pending": None}, "social": {}}
    def call(method, path, body=None):
        calls.append((method, path, body))
        return {"pending": [job]} if method == "GET" else {}
    client = SimpleNamespace(observe=lambda cursor: observation, call=call,
                             action=lambda **parameters: actions.append(parameters),
                             night_routine=lambda: defaults.append(True), close=lambda: None)
    def summarize(context):
        assert context == {"participant": "Alex", "conversation": job}
        return json.dumps({"summary": "Maya and I discussed gardening without making a firm plan.", "approvedReflections": []}), 20
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(summarize=summarize))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    saved = next(body for method, path, body in calls if path == "/agent/conversation-summary/42")
    assert saved["throughEvent"] == 45 and "gardening" in saved["summary"]
    assert actions == [] and defaults == []
    assert observation["self"]["location"] == "park"


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
    assert "no fixed farewell formula" in captured["instructions"]
    assert "nonblank closing message" in captured["instructions"]
    assert "abrupt departures are rejected" in captured["instructions"]
    assert "world removes it automatically" in captured["instructions"]
    assert captured["context"]["observation"]["self"]["knowledge"]["science"]["studyMinutes"] == 30
    assert "Use those concepts naturally" in captured["instructions"]
    assert "available residents can also choose to talk there" in captured["instructions"]
    assert "personality as tendencies rather than a script" in captured["instructions"]
    assert "no productivity or variety quota" in captured["instructions"]
    assert "not buying quotas" in captured["instructions"]
    assert "other residents have private motives you cannot read" in captured["instructions"]
    assert "observation.seating" in captured["instructions"]
    assert "Sitting is optional" in captured["instructions"]
    from runtime.agent import ACTION_TYPES
    assert {"READ_BOOK", "BORROW_BOOK", "RETURN_BOOK", "REASON", "CREATE_WORK"}.issubset(ACTION_TYPES)
    assert "Include each cited concept verbatim" in captured["instructions"]
    assert "Quote at least one concept" in captured["instructions"]
    assert "does not verify correctness" in captured["instructions"]
    assert CAREER_ADVICE in captured["instructions"]
    assert PRESENCE_ADVICE in captured["instructions"]
    assert "accepted plans are not attendance" in captured["instructions"]
    assert "not evidence that they came, spoke or participated" in captured["instructions"]


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


@pytest.mark.parametrize("action", ["LOOK", "TALK", "REST", "USE_OBJECT", "CALL_OUT", "PAUSE_TRAVEL"])
def test_model_chosen_casual_actions_are_not_replaced_by_a_night_default(action):
    actual = []
    def unexpected_default():
        raise AssertionError("A routine must never replace the resident's choice")
    client = SimpleNamespace(night_routine=unexpected_default,
                             action=lambda **parameters: actual.append(parameters) or {"status": "completed"})
    chosen = {"action": action, "target": "maya", "message": "My own words"}
    assert execute_action(client, chosen, {"nightReturnDue": True}) == {"status": "completed"}
    assert actual == [chosen]


def test_night_return_keeps_the_residents_authored_closing_and_homeward_action():
    sent, defaults = [], []
    def unexpected_default():
        defaults.append(True)
        return {"status": "accepted", "result": "Going home"}
    client = SimpleNamespace(night_routine=unexpected_default, action=lambda **parameters: sent.append(parameters))
    closing = {"action": "END_CHAT", "target": None, "message": "It's late, so I'm heading home. See you later! Take care."}
    homeward = {"action": "MOVE", "target": "alex-home"}
    execute_action(client, closing)
    execute_action(client, homeward)
    assert sent == [closing, homeward]
    assert defaults == []


def test_runtime_asks_for_each_night_choice_and_preserves_the_residents_decision_to_stay_out(monkeypatch):
    from runtime.agent import run
    sent, calls, contexts, defaults = [], [], [], []
    chat = {"other": "maya", "status": "waiting_for_reply", "turns": [
        {"speaker": "alex", "message": "What do you like growing?", "minute": 1300}]}
    first = {"worldMinute": 1301, "nightReturnDue": True,
             "self": {"id": "alex", "name": "Alex", "personality": "Sociable", "goals": "Enjoy gardening",
                      "location": "garden", "home": "alex-home", "paused": False, "pending": None},
             "locations": [{"id": "garden", "name": "Meadow Commons"}], "nearby": [], "events": [],
             "social": {}, "conversations": [chat]}
    second = {**first, "worldMinute": 1302, "conversations": [{**chat, "status": "closed"}]}
    observations = iter([first, second])
    current = {}
    def observe(cursor):
        current["observation"] = next(observations)
        return current["observation"]
    def routine():
        defaults.append(True)
        raise AssertionError("Night timing is context for a model choice")
    def call(*parameters):
        calls.append(parameters)
        return {"pending": []}
    closing = {"action": "END_CHAT", "target": None, "message": "Time for me to go home. See you around! Bye."}
    stay_out = {"action": "REST", "quantity": 20}
    client = SimpleNamespace(observe=observe, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(parameters) or {"status": "completed"},
                             night_routine=routine, call=call, close=lambda: None)
    def decide(instructions, context):
        contexts.append(context)
        assert NIGHT_RETURN_ADVICE in instructions
        return [("act", closing if len(contexts) == 1 else stay_out)], 10
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=2, max_tokens=100, interval=1))
    assert sent == [closing, stay_out]
    assert defaults == []
    assert [context["observation"]["worldMinute"] for context in contexts] == [1301, 1302]
    assert sum(parameters[1] == "/agent/conversation-summary" for parameters in calls) == 2
    assert first["conversations"][0]["status"] == "waiting_for_reply"


def test_runtime_records_a_concrete_night_plan_before_its_social_action(monkeypatch):
    from runtime.agent import run
    sent = []
    observation = {"worldMinute": 1301, "nightReturnDue": True,
                   "self": {"id": "alex", "name": "Alex", "personality": "Curious", "goals": "Meet a friend",
                            "location": "garden", "paused": False, "pending": None},
                   "locations": [{"id": "garden", "name": "Meadow Commons"}], "nearby": [], "events": [],
                   "conversations": [{"other": "maya", "status": "your_turn", "turns": [
                       {"speaker": "maya", "message": "Shall we watch the stars?", "minute": 1300}]}]}
    plan = {"reason": "Finish our agreed stargazing visit", "until": 1340}
    chosen = {"action": "TALK", "target": "maya", "message": "Let's watch the stars until ten twenty."}
    def routine():
        # Before the declaration, the active chat needs an authored decision.
        return None
    client = SimpleNamespace(observe=lambda cursor: observation, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(("act", parameters)) or {"status": "completed"},
                             plan_night=lambda **parameters: sent.append(("night_plan", parameters)) or {"ok": True},
                             night_routine=routine, call=lambda *parameters: {}, close=lambda: None)
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(
        decide=lambda instructions, context: ([("act", chosen), ("night_plan", plan)], 10)))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert sent == [("night_plan", plan), ("act", chosen)]


@pytest.mark.parametrize("outcome", ["empty_response", "provider_failure", "action_failure", "zero_budget"])
def test_runtime_failure_or_missing_choice_never_creates_a_replacement_action(monkeypatch, outcome):
    from runtime.agent import run
    observation = {"worldMinute": 1300, "nightReturnDue": True,
                   "self": {"id": "alex", "name": "Alex", "personality": "Quiet", "goals": "Enjoy the night",
                            "location": "park", "paused": False, "pending": None, "bedtimeRoutine": True},
                   "locations": [{"id": "park", "name": "Central Park"}], "nearby": [], "events": []}
    sent, defaults, closed = [], [], []
    def routine():
        defaults.append(True)
        raise AssertionError("No inference means no new resident action")
    def action(**parameters):
        sent.append(parameters)
        raise RuntimeError("Action delivery unavailable")
    def decide(*args):
        if outcome == "provider_failure":
            raise RuntimeError("Provider unavailable")
        return ([("act", {"action": "REST"})] if outcome == "action_failure" else []), 10
    client = SimpleNamespace(observe=lambda cursor: observation, recall=lambda *args, **kwargs: [],
                             action=action, night_routine=routine, call=lambda *args: {}, close=lambda: closed.append(True))
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=0 if outcome == "zero_budget" else 1,
                        max_tokens=100, interval=1))
    assert sent == ([{"action": "REST"}] if outcome == "action_failure" else [])
    assert defaults == [] and closed == [True]


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


def test_outgoing_calls_do_not_wake_caller_but_awake_workers_can_choose_their_response():
    observation = {"self": {"id": "caller", "paused": False, "pending": {"type": "REST"}},
                   "social": {"reconsider": False, "calls": [{"caller": "caller", "listener": "listener", "status": "pending"}]}}
    assert not decision_due(observation)
    observation["self"]["id"] = "listener"
    for activity in ["WORK", "SLEEP", "BOARD_BUS", "RIDE_BUS", "READ_BOOK", "RESEARCH_PROJECT", "BUILD_PROJECT", "PRODUCE_PRODUCT", "BUILD_CONSTRUCTION"]:
        observation["self"]["pending"]["type"] = activity
        assert decision_due(observation) == (activity not in {"SLEEP", "BOARD_BUS", "RIDE_BUS"})


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


def test_runtime_reviews_intentions_before_acting_on_changed_surroundings(monkeypatch):
    from runtime.agent import run
    sent, calls = [], []
    cognition = {"reconsider": True, "intentionReviewDue": True, "intention": {"focus": "Learn gardening"}}
    daily_life = {"rhythm": {"recentDays": [{"day": 0, "activityMinutes": {"REST": 90}, "completedActions": {"READ_BOOK": 1}}]},
                  "upcoming": [{"kind": "accepted_meeting", "minute": 900, "walkingMinutes": 25, "leaveByMinute": 875}]}
    observation = {"worldMinute": 800, "dailyLife": daily_life, "self": {"id": "alex", "name": "Alex", "personality": "Curious", "goals": "Grow food",
                   "location": "park", "paused": False, "pending": {"type": "REST"}},
                   "locations": [{"id": "park", "name": "Central Park"}], "nearby": [], "events": [], "cognition": cognition}
    update = {"intention": None, "clearIntention": False, "feeling": "Curious", "lesson": None, "outcomeId": None}
    client = SimpleNamespace(observe=lambda cursor: observation, recall=lambda *args, **kwargs: [],
                             action=lambda **p: sent.append(("act", p)) or {"status": "completed"},
                             review_intention=lambda **p: sent.append(("review_intention", p)),
                             night_routine=lambda: None, call=lambda *args: calls.append(args) or {}, close=lambda: None)
    def decide(instructions, context):
        assert context["observation"]["cognition"] == cognition
        assert context["observation"]["dailyLife"] == daily_life
        assert "There is no daily quota" in instructions
        assert "UNSCHEDULED time includes waiting for the runtime" in instructions
        return [("act", {"action": "LOOK"}), ("review_intention", update)], 10
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert sent == [("review_intention", update), ("act", {"action": "LOOK"})]
    assert not any(call[1] == "/agent/conversation-summary" for call in calls)
    assert observation["self"]["pending"]["type"] == "REST"


def test_expired_pending_public_calls_do_not_wake_resting_residents():
    observation = {"worldMinute": 810, "self": {"id": "listener", "paused": False, "pending": {"type": "REST"}},
                   "social": {"calls": [{"caller": "caller", "listener": "listener", "status": "pending", "expires": 810}]}}
    assert not decision_due(observation)
    observation["social"]["calls"][0]["expires"] = 811
    assert decision_due(observation)


@pytest.fixture
def accepted_commitment():
    return {"plan": "outing-1", "other": "maya", "otherName": "Maya", "destination": "courtyard",
            "destinationName": "Lantern Courtyard", "activity": "Walk together after coffee", "meetingMinute": 803,
            "expires": 863, "priority": 1, "due": True, "progress": "pending", "nextAction": "JOIN_PLAN",
            "interruptionStartedMinute": None, "interruptionUntil": None, "interruptionTurns": 0,
            "remainingInterruptionTurns": 2, "returnDue": False}


@pytest.mark.parametrize("activity", ["REST", "MOVE", "RUN"])
def test_due_accepted_outing_offers_a_decision_before_an_interruptible_activity_continues(activity, accepted_commitment):
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False, "pending": {"type": activity}},
                   "social": {"commitments": [accepted_commitment], "reconsider": False}}
    assert decision_due(observation)
    assert social_decision_due(observation)
    observation["self"]["paused"] = True
    assert not decision_due(observation)


@pytest.mark.parametrize("activity", ["WORK", "SLEEP", "READ_BOOK", "BOARD_BUS", "RIDE_BUS", "WAIT_BUS", "WAIT_RAIN",
                                      "RESEARCH_PROJECT", "BUILD_PROJECT", "PRODUCE_PRODUCT", "BUILD_CONSTRUCTION"])
def test_due_outing_does_not_interrupt_exclusive_work_or_waits(activity, accepted_commitment):
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False, "pending": {"type": activity}},
                   "social": {"commitments": [{**accepted_commitment, "returnDue": True}], "reconsider": False}}
    assert not decision_due(observation)
    assert not social_decision_due(observation)


def test_future_expired_and_arrived_commitments_do_not_repeatedly_interrupt_rest(accepted_commitment):
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False, "pending": {"type": "REST"}},
                   "social": {"commitments": []}}
    for change in [{"due": False}, {"expires": 800}, {"progress": "at_destination", "nextAction": "WAIT"}]:
        observation["social"]["commitments"] = [{**accepted_commitment, **change}]
        assert not decision_due(observation)
        assert not social_decision_due(observation)


def test_only_the_first_ranked_commitment_wakes_an_interruptible_activity(accepted_commitment):
    secondary = {**accepted_commitment, "plan": "outing-2", "priority": 2}
    first = {**accepted_commitment, "due": False}
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False, "pending": {"type": "REST"}},
                   "social": {"commitments": [secondary, first]}}
    assert not decision_due(observation)
    assert not social_decision_due(observation)
    observation["social"]["commitments"] = [secondary, accepted_commitment]
    assert decision_due(observation)
    assert social_decision_due(observation)
    observation["social"]["commitments"] = [secondary]
    assert not decision_due(observation)


def test_reviewed_departure_waits_fifteen_minutes_during_rest_but_closing_and_idle_departure_do_not(accepted_commitment):
    assert "depart after REST finishes; JOIN_PLAN cannot cancel rest" in SHARED_COMMITMENT_ADVICE
    assert "subject to your current activity scope" in SHARED_COMMITMENT_ADVICE
    rest = {"id": "seat-break", "type": "REST"}
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False, "pending": rest,
                                               "encounterReviewed": {"plan:outing-1": 800}},
                   "social": {"commitments": [accepted_commitment]}}
    assert not decision_due(observation)
    assert not social_decision_due(observation)
    observation["worldMinute"] = 814
    assert not decision_due(observation)
    observation["worldMinute"] = 815
    assert decision_due(observation)
    assert social_decision_due(observation)
    assert observation["self"]["pending"] is rest
    observation["worldMinute"] = 800
    observation["social"]["commitments"] = [{**accepted_commitment, "nextAction": "END_CHAT"}]
    assert decision_due(observation)
    observation["social"]["commitments"] = [accepted_commitment]
    observation["self"]["pending"] = None
    assert decision_due(observation)
    assert social_decision_due(observation)


def test_matching_journey_continues_without_polling_until_an_interruption_needs_a_return(accepted_commitment):
    travelling = {**accepted_commitment, "progress": "travelling", "nextAction": "CONTINUE_TRAVEL"}
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False,
                                               "pending": {"type": "MOVE", "target": "courtyard", "socialPlanId": "outing-1"}},
                   "social": {"commitments": [travelling]}}
    assert not decision_due(observation)
    assert social_decision_due(observation)  # If a decision is needed, this journey comes before summary work.
    observation["social"]["commitments"] = [{**travelling, "returnDue": True, "interruptionTurns": 2,
                                              "remainingInterruptionTurns": 0}]
    assert not decision_due(observation)  # A spent interruption budget does not pause an already resumed walk.
    observation["self"]["pending"].update(socialWith="sam", socialUntil=803)
    assert decision_due(observation)
    assert social_decision_due(observation)
    observation["worldMinute"] = 803
    observation["self"]["pending"].update(socialWith=None, socialUntil=0)
    assert not decision_due(observation)


def test_recall_prioritizes_the_original_partner_and_destination_over_new_people_and_long_intentions(accepted_commitment):
    captured = {}
    client = SimpleNamespace(recall=lambda query, **parameters: captured.update(query=query, **parameters) or [])
    later = {**accepted_commitment, "plan": "later", "other": "later-peer", "otherName": "Later Peer",
             "destination": "library", "destinationName": "Library", "priority": 2, "due": False}
    expired = {**accepted_commitment, "other": "expired-peer", "expires": 800}
    observation = {"worldMinute": 800, "self": {"location": "park", "goals": "g" * 2000},
                   "cognition": {"intention": {"focus": "i" * 1000}},
                   "locations": [{"id": "park", "name": "Central Park"}],
                   "nearby": [{"id": "sam", "name": "Sam"}, {"id": "jo", "name": "Jo"}, {"id": "lee", "name": "Lee"}],
                   "social": {"commitments": [later, expired, accepted_commitment]}}
    relevant_memories(client, observation)
    assert captured["query"].startswith("Central Park Maya Lantern Courtyard Walk together after coffee")
    assert len(captured["query"]) == 300
    assert captured["entities"][:3] == ["park", "maya", "courtyard"]
    assert "later-peer" in captured["entities"] and "expired-peer" not in captured["entities"]
    assert captured["limit"] == 10


def test_runtime_closes_an_interruption_then_returns_to_the_same_outing_before_summary_work(monkeypatch, accepted_commitment):
    from runtime.agent import run
    calls, sent, contexts = [], [], []
    interruption = {**accepted_commitment, "nextAction": "END_CHAT", "interruptionStartedMinute": 800,
                    "interruptionUntil": 803, "interruptionTurns": 2, "remainingInterruptionTurns": 0, "returnDue": True}
    original = {"other": "maya", "status": "closed", "turns": [
        {"speaker": "maya", "message": "Let's walk to the courtyard together.", "minute": 798},
        {"speaker": "alex", "message": "Agreed. I'll come with you.", "minute": 799}]}
    newcomer = {"other": "sam", "status": "reply_due", "turns": [
        {"speaker": "sam", "message": "I have another story to tell you.", "minute": 802}]}
    first = {"worldMinute": 802, "self": {"id": "alex", "name": "Alex", "personality": "Sociable", "goals": "Enjoy a walk",
                                         "location": "park", "paused": False, "pending": {"type": "REST"}},
             "locations": [{"id": "park", "name": "Central Park"}], "nearby": [{"id": "sam", "name": "Sam"}], "events": [],
             "social": {"commitments": [interruption]}, "conversations": [newcomer, original],
             "conversationFocus": [{"other": "sam", "latestTurn": newcomer["turns"][-1]}]}
    second = {**first, "worldMinute": 803, "social": {"commitments": [{**interruption, "nextAction": "JOIN_PLAN"}]},
              "conversations": [{**newcomer, "status": "closed"}, original]}
    observations = iter([first, second])
    closing = {"action": "END_CHAT", "target": "sam", "message": "I promised Maya we would head out. Catch up later! Take care."}
    joining = {"action": "JOIN_PLAN", "target": "outing-1"}
    client = SimpleNamespace(observe=lambda cursor: next(observations), recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(parameters) or {"status": "completed"},
                             night_routine=lambda: None, call=lambda *args: calls.append(args) or {}, close=lambda: None)
    def decide(instructions, context):
        contexts.append(context)
        assert SHARED_COMMITMENT_ADVICE in instructions
        assert "promptly record that outing" in instructions
        assert "timing reminders, not a conversation quota" in instructions
        assert "not the priority" in instructions
        return [("act", closing if len(contexts) == 1 else joining)], 10
    def summarize(context):
        raise AssertionError("An interrupted due commitment must be considered before queued summaries")
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide, summarize=summarize))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=2, max_tokens=100, interval=1))
    assert sent == [closing, joining]
    assert not any(call[1] == "/agent/conversation-summary" for call in calls)
    assert [context["observation"]["social"]["commitments"][0]["plan"] for context in contexts] == ["outing-1", "outing-1"]
    assert contexts[0]["observation"]["conversations"] == [newcomer, original]
    assert contexts[0]["observation"]["social"]["commitments"][0] == interruption
    assert first["social"]["commitments"][0]["interruptionTurns"] == 2


def test_shared_plan_tool_schema_is_strict_and_supports_explicit_participant_tasks():
    schema = next(tool for tool in TOOLS if tool["name"] == "act")["parameters"]
    assert "DO_ACTIVITY" in schema["properties"]["action"]["enum"]
    assert "WAIT_PLAN" in schema["properties"]["action"]["enum"]
    assert set(schema["required"]) == set(schema["properties"])
    shared = schema["properties"]["sharedPlan"]["anyOf"][1]
    assert shared["additionalProperties"] is False
    assert set(shared["required"]) == set(shared["properties"])
    assert shared["properties"]["operation"]["enum"] == ["propose", "accept", "decline", "cancel"]
    for field in ["delayMinutes", "durationMinutes"]:
        assert shared["properties"][field]["type"] == ["integer", "null"]
        assert shared["properties"][field]["minimum"] == 1
        assert shared["properties"][field]["maximum"] == 100
    assert shared["properties"]["activity"]["maxLength"] == 1000
    tasks = shared["properties"]["tasks"]
    assert tasks["maxItems"] == 2
    assert tasks["items"]["additionalProperties"] is False
    assert set(tasks["items"]["required"]) == {"resident", "destination", "activity", "durationMinutes", "action", "target", "item"}
    assert tasks["items"]["properties"]["durationMinutes"]["type"] == ["integer", "null"]
    assert tasks["items"]["properties"]["action"]["enum"] == ["DO_ACTIVITY", "USE_OBJECT", "READ_BOOK", "RESEARCH_PROJECT", "BUILD_PROJECT", "BUILD_CONSTRUCTION", "PRODUCE_PRODUCT", None]


@pytest.mark.parametrize("operation", ["propose", "accept", "decline", "cancel"])
def test_dialogue_plan_metadata_reaches_the_world_in_the_authored_action_only(operation):
    sent = []
    shared = {"operation": operation, "plan": None, "destination": None, "activity": None,
              "delayMinutes": None, "durationMinutes": None, "tasks": None}
    if operation == "propose":
        shared.update(destination="trekking-trail", activity="Search for flowers together", delayMinutes=1,
                      durationMinutes=15, tasks=[{"resident": "alex", "destination": "point:150,200",
                                                "activity": "Search the trailhead", "durationMinutes": None},
                                               {"resident": "maya", "destination": "point:152,200",
                                                "activity": "Search the open area", "durationMinutes": 20}])
    else:
        shared["plan"] = "observed-outing-1"
    payload = {"action": "TALK", "target": "maya", "message": "Here is my own reply.", "sharedPlan": shared}
    client = SimpleNamespace(action=lambda **parameters: sent.append(parameters) or {"status": "completed"},
                             night_routine=lambda: None)
    assert execute_action(client, payload)["status"] == "completed"
    assert sent == [payload]
    assert sent[0]["sharedPlan"] is shared


def test_protocol_client_transmits_structured_acceptance_with_its_farewell():
    received = []
    def handler(request):
        received.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"status": "completed"})
    client = Earth35(token="test-token", url="http://test.invalid")
    client.http.close()
    client.http = httpx.Client(base_url="http://test.invalid/v1", transport=httpx.MockTransport(handler))
    metadata = {"operation": "accept", "plan": "observed-outing-1"}
    payload = {"action": "END_CHAT", "target": "maya", "message": "Agreed. See you at the trail!", "sharedPlan": metadata}
    try:
        assert execute_action(client, payload)["status"] == "completed"
    finally:
        client.close()
    assert len(received) == 1
    assert received[0][0] == "/v1/agent/action"
    assert {key: value for key, value in received[0][1].items() if key != "requestId"} == payload
    assert received[0][1]["requestId"]


@pytest.mark.parametrize("action,target,item,duration", [
    ("READ_BOOK", "observed-book", None, 30),
    ("USE_OBJECT", "observed-bench", None, 15),
    ("RESEARCH_PROJECT", "joined-project", "observation", 30),
    ("BUILD_PROJECT", "joined-project", None, 15),
    ("BUILD_CONSTRUCTION", "funded-site", None, 15),
    ("PRODUCE_PRODUCT", "owned-business", None, 15),
])
def test_typed_shared_tasks_preserve_real_action_references_without_runtime_invention(action, target, item, duration):
    task = {"resident": "alex", "destination": "observed-workplace", "activity": "Carry out the agreed task",
            "durationMinutes": duration, "action": action, "target": target, "item": item}
    payload = {"action": "TALK", "target": "maya", "message": "Shall we carry out this task together?",
               "sharedPlan": {"operation": "propose", "destination": "park", "activity": "Work together",
                              "delayMinutes": 1, "durationMinutes": 15, "tasks": [task]}}
    captured = {}
    def create(**kwargs):
        captured.update(kwargs)
        return SimpleNamespace(output=[SimpleNamespace(type="function_call", name="act", arguments=json.dumps(payload))],
                               usage=SimpleNamespace(total_tokens=15))
    provider = Provider.__new__(Provider)
    provider.kind, provider.model, provider.sdk = "openai", "owner-model", SimpleNamespace(responses=SimpleNamespace(create=create))
    calls, tokens = provider.decide(SHARED_COMMITMENT_ADVICE, {"social": {"plans": []}})
    sent = []
    client = SimpleNamespace(action=lambda **parameters: sent.append(parameters) or {"status": "completed"},
                             night_routine=lambda: None)
    for name, parameters in calls:
        assert name == "act"
        execute_action(client, parameters)
    assert tokens == 15
    assert sent == [payload]
    assert sent[0]["sharedPlan"]["tasks"] == [task]
    assert all(tool["strict"] for tool in captured["tools"])


def test_waiting_for_the_shared_meeting_reacts_to_actual_social_changes_without_repeated_departure(accepted_commitment):
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False,
                   "pending": {"type": "PLAN_WAIT", "socialPlanId": "outing-1"}},
                   "social": {"commitments": [{**accepted_commitment, "progress": "at_destination", "nextAction": "WAIT"}]}}
    assert not decision_due(observation)
    assert not social_decision_due(observation)
    observation["social"]["reconsider"] = True
    assert decision_due(observation)
    assert social_decision_due(observation)
    observation["self"]["paused"] = True
    assert not decision_due(observation)


def test_timed_shared_activity_offers_a_choice_without_automatically_stopping_it(accepted_commitment):
    observation = {"worldMinute": 800, "self": {"id": "alex", "paused": False,
                   "pending": {"type": "DO_ACTIVITY", "socialPlanId": "outing-1"}},
                   "cognition": {"reconsider": True}, "social": {"reconsider": True,
                   "commitments": [{**accepted_commitment, "returnDue": True}],
                   "calls": [{"listener": "alex", "status": "pending", "expires": 810}]}}
    assert decision_due(observation)
    assert observation["self"]["pending"]["socialPlanId"] == "outing-1"
    observation["self"]["pending"] = None
    assert decision_due(observation)


def test_executable_agreement_advice_requires_explicit_consent_and_actual_world_follow_through():
    for text in ["same action's sharedPlan metadata", "independently attaches operation accept",
                 "Verbal enthusiasm alone never creates an agreement", "Close all your active chats",
                 "before splitting up", "only after the real meeting", "each actual task must finish",
                 "never invented flowers", "Existing saved plans without tasks"]:
        assert text in SHARED_COMMITMENT_ADVICE
