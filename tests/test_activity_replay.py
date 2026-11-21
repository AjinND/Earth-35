from types import SimpleNamespace

import pytest

from runtime.agent import execute_action, run


def observation():
    return {"worldMinute": 720,
            "self": {"id": "mei", "name": "Mei Lin", "personality": "Curious", "goals": "Explore native plants",
                     "location": "park", "paused": False, "pending": None,
                     "activityHistory": [{"id": "search-1", "label": "Search for native plants", "location": "park",
                                          "mode": "search", "activeMinutes": 15, "inspectedStops": 3, "distance": 18, "minute": 719}]},
            "cognition": {"recentOutcomes": [{"id": "search-1", "action": "DO_ACTIVITY", "status": "completed", "minute": 719}]},
            "locations": [{"id": "park", "name": "Central Park"}], "nearby": [], "events": []}


@pytest.mark.parametrize("label, mode", [
    ("Search for native plants", "search"),
    ("Spend another 15 minutes searching for plants", None),
    ("Walk around the park again", "walk"),
])
def test_repeated_activity_is_the_residents_choice_without_a_reflection_requirement(label, mode):
    observed = observation()
    choice = {"action": "DO_ACTIVITY", "message": label, "item": mode, "quantity": 15}
    sent = []
    receipt = {"status": "accepted", "result": "Activity started"}
    client = SimpleNamespace(action=lambda **parameters: sent.append(parameters) or receipt)
    assert execute_action(client, choice, observed) == receipt
    assert sent == [choice]
    assert "reflections" not in observed["cognition"]


def configure_runtime(monkeypatch, client, decide):
    monkeypatch.setenv("E35_TOKEN", "test-token")
    monkeypatch.setattr("runtime.agent.Earth35", lambda token: client)
    monkeypatch.setattr("runtime.agent.Provider", lambda *args: SimpleNamespace(decide=decide))
    monkeypatch.setattr("runtime.agent.time.sleep", lambda _: None)


def test_runtime_presents_previous_activity_and_dispatches_a_deliberate_repeat_in_one_decision(monkeypatch):
    observed = observation()
    sent, contexts = [], []
    choice = {"action": "DO_ACTIVITY", "message": "Search for native plants", "quantity": 15}
    memories = [{"content": "I enjoy searching quietly in this park."}]
    client = SimpleNamespace(observe=lambda cursor: observed, recall=lambda *args, **kwargs: memories,
                             action=lambda **parameters: sent.append(parameters) or {"status": "accepted"},
                             call=lambda *args: {}, close=lambda: None)

    def decide(instructions, context):
        contexts.append(context)
        assert context["observation"]["self"]["activityHistory"] == observed["self"]["activityHistory"]
        assert context["observation"]["cognition"] == observed["cognition"]
        assert context["relevant_memories"] == memories
        assert "repeat an activity without a new reflection" in instructions
        return [("act", choice)], 10

    configure_runtime(monkeypatch, client, decide)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert sent == [choice] and len(contexts) == 1


def test_optional_reflection_is_persisted_before_the_models_chosen_continuation(monkeypatch):
    observed = observation()
    sent = []
    choice = {"action": "DO_ACTIVITY", "message": "Search for native plants", "quantity": 15}
    review = {"intention": None, "clearIntention": False, "feeling": "Absorbed",
              "lesson": "The entrance search was quiet; I want to inspect the nearby edge.", "outcomeId": "search-1"}
    client = SimpleNamespace(observe=lambda cursor: observed, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(("act", parameters)) or {"status": "accepted"},
                             review_intention=lambda **parameters: sent.append(("review_intention", parameters)) or {"ok": True},
                             call=lambda *args: {}, close=lambda: None)
    configure_runtime(monkeypatch, client, lambda *args: ([("act", choice), ("review_intention", review)], 10))
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=1, max_tokens=100, interval=1))
    assert sent == [("review_intention", review), ("act", choice)]


def test_failed_optional_reflection_does_not_veto_a_legal_world_action(monkeypatch):
    observed = observation()
    sent, contexts = [], []
    choice = {"action": "DO_ACTIVITY", "message": "Search for native plants", "quantity": 15}
    review = {"intention": None, "clearIntention": False, "feeling": None,
              "lesson": "I would like another short block outdoors.", "outcomeId": "search-1"}

    def fail_review(**parameters):
        raise RuntimeError("Invalid cognition update")

    client = SimpleNamespace(observe=lambda cursor: observed, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(parameters) or {"status": "accepted"},
                             review_intention=fail_review, call=lambda *args: {}, close=lambda: None)

    def decide(instructions, context):
        contexts.append(context)
        return ([("review_intention", review), ("act", choice)] if len(contexts) == 1 else []), 10

    configure_runtime(monkeypatch, client, decide)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=2, max_tokens=100, interval=1))
    assert sent == [choice]
    assert contexts[1]["previous_results"][0] == {"tool": "review_intention", "error": "Invalid cognition update"}
    assert contexts[1]["previous_results"][1]["result"]["status"] == "accepted"


def test_world_rejection_is_evidence_for_the_next_model_choice_without_an_invented_next_step(monkeypatch):
    observed = observation()
    sent, contexts = [], []
    repeated = {"action": "DO_ACTIVITY", "message": "Search for native plants", "quantity": 15}
    alternative = {"action": "REST", "quantity": 10}
    rejection = {"status": "rejected", "result": "The destination is closed"}
    client = SimpleNamespace(observe=lambda cursor: observed, recall=lambda *args, **kwargs: [],
                             action=lambda **parameters: sent.append(parameters) or (rejection if len(sent) == 1 else {"status": "accepted"}),
                             call=lambda *args: {}, close=lambda: None)

    def decide(instructions, context):
        contexts.append(context)
        if len(contexts) == 2:
            assert context["previous_results"] == [{"tool": "act", "result": rejection}]
        return [("act", repeated if len(contexts) == 1 else alternative)], 10

    configure_runtime(monkeypatch, client, decide)
    run(SimpleNamespace(provider="openai", model="test-model", max_calls=2, max_tokens=100, interval=1))
    assert sent == [repeated, alternative]
