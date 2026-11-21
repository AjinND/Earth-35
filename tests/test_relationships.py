"""Independent runtime relationship context and authored appraisal dispatch."""
from types import SimpleNamespace

from runtime.agent import RELATIONSHIP_ADVICE, TOOLS, decision_observation, run
from runtime.client import Earth35


def test_relationship_context_keeps_private_evidence_without_duplicating_internal_bonds():
    appraisal = {"feeling": "I like their humor but am unsure I can rely on them.", "warmth": 64, "trust": None}
    evidence = [{"id": "turn:4", "kind": "conversation", "actor": "maya", "description": "A real reply.", "minute": 800}]
    relationships = [{"other": "maya", "name": "Maya", "label": "acquaintance", "interactionCount": 2,
                      "lastInteractionMinute": 800, "minutesSinceInteraction": 4, "needsReflection": True,
                      "appraisal": appraisal, "appraisedMinute": 790, "evidence": evidence,
                      "history": [{"minute": 790, "appraisal": {"feeling": "Curious"}}]}]
    raw_bonds = {"maya": {"private_internal_key": "complete persisted bond"}}
    observation = {"self": {"id": "alex", "bonds": raw_bonds}, "relationships": relationships}

    compact = decision_observation(observation)

    assert compact["relationships"] == relationships
    assert compact["relationships"][0]["appraisal"]["trust"] is None
    assert "bonds" not in compact["self"]
    assert observation["self"]["bonds"] is raw_bonds
    assert evidence[0]["id"] == "turn:4"


def test_relationship_appraisal_tool_has_strict_nullable_independent_dimensions():
    tool = next(tool for tool in TOOLS if tool["name"] == "review_intention")
    schema = tool["parameters"]
    assert set(schema["required"]) == set(schema["properties"])
    nullable = schema["properties"]["relationship"]["anyOf"]
    assert nullable[0] == {"type": "null"}
    relationship = nullable[1]
    assert relationship["additionalProperties"] is False
    assert set(relationship["required"]) == set(relationship["properties"])
    assert relationship["properties"]["target"]["maxLength"] == 80
    assert relationship["properties"]["label"]["type"] == ["string", "null"]
    assert relationship["properties"]["feeling"]["maxLength"] == 160
    assert relationship["properties"]["reason"]["maxLength"] == 600
    assert relationship["properties"]["evidence"]["minItems"] == 1
    assert relationship["properties"]["evidence"]["maxItems"] == 6
    assert relationship["properties"]["evidence"]["items"]["maxLength"] == 160
    for dimension in ("warmth", "trust", "respect", "closeness", "attraction", "tension"):
        score = relationship["properties"][dimension]
        assert score["type"] == ["integer", "null"]
        assert score["minimum"] == 0 and score["maximum"] == 100


def test_runtime_gives_the_model_private_context_and_saves_its_own_appraisal_before_action(monkeypatch):
    appraisal = {"target": "maya", "label": None, "feeling": "Warm but cautious", "reason": "They listened, though I need more experience to trust them.",
                 "evidence": ["turn:4"], "warmth": 64, "trust": None, "respect": 58,
                 "closeness": None, "attraction": None, "tension": 12}
    review = {"intention": None, "clearIntention": False, "feeling": None, "lesson": None,
              "outcomeId": None, "relationship": appraisal}
    chosen_action = {"action": "TALK", "target": "maya", "message": "How has your afternoon been?"}
    relationship = {"other": "maya", "name": "Maya", "interactionCount": 2, "needsReflection": True,
                    "appraisal": None, "evidence": [{"id": "turn:4", "minute": 800, "actor": "maya",
                                                   "kind": "conversation", "description": "They replied to my greeting."}]}
    observation = {"worldMinute": 804, "self": {"id": "alex", "name": "Alex", "personality": "Thoughtful", "goals": "Enjoy the day",
                                               "location": "park", "pending": None, "paused": False},
                   "locations": [{"id": "park", "name": "Central Park"}], "nearby": [{"id": "maya", "name": "Maya"}],
                   "relationships": [relationship], "events": []}
    sent = []
    client = SimpleNamespace(observe=lambda cursor: observation, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(("action", parameters)) or {"status": "completed"},
                             review_intention=lambda **parameters: sent.append(("cognition", parameters)) or {"ok": True},
                             call=lambda *args: {}, close=lambda: None)

    def decide(instructions, context):
        assert RELATIONSHIP_ADVICE in instructions
        assert context["observation"]["relationships"] == [relationship]
        # Even if the model lists its action first, persist its own appraisal first.
        return [("act", chosen_action), ("review_intention", review)], 10

    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)

    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))

    assert sent == [("cognition", review), ("action", chosen_action)]
    assert sent[0][1]["relationship"] is appraisal
    assert observation["relationships"][0]["appraisal"] is None


def test_protocol_client_forwards_appraisal_to_authenticated_cognition_endpoint():
    received = []
    client = Earth35(token="test-token", url="http://test.invalid")
    client.call = lambda method, path, body: received.append((method, path, body)) or {"ok": True}
    appraisal = {"target": "maya", "feeling": "Cautiously comfortable", "reason": "They respected my request for quiet.",
                 "evidence": ["turn:4"], "trust": 42, "attraction": None}
    try:
        assert client.review_intention(relationship=appraisal) == {"ok": True}
    finally:
        client.close()
    assert received == [("PUT", "/agent/cognition", {"relationship": appraisal})]
