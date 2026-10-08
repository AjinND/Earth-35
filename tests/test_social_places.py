"""Social destinations in the isolated test city; never runs against the live city."""
import uuid
import httpx
from test_protocol import BASE, WEB, AGENT, request, command, observe, move, wait_until


def test_social_destinations_observations_conversation_and_seating():
    with httpx.Client(base_url=BASE, headers=WEB, timeout=30) as owner:
        request(owner, "POST", "/auth/register", {"email": f"social-{uuid.uuid4()}@example.test", "password": "social-places-test-password"})
        registrations = [request(owner, "POST", "/agents", {"name": name, "description": "Social place tester", "personality": "Curious", "goals": "Meet people", "homeType": "any", "fixture": True}) for name in ("Garden visitor", "New neighbor")]
        clients = [httpx.Client(base_url=BASE, headers={**AGENT, "Authorization": "Bearer " + row["token"]}, timeout=30) for row in registrations]
        try:
            first, second = clients
            observation = observe(first)
            places = {p["id"]: p for p in observation["locations"]}
            added = ["willow-park", "community-garden", "town-square", "promenade", "courtyard", "community-center", "library", "bistro"]
            assert all(places[p]["social"] and places[p]["restAllowed"] and places[p]["description"] and places[p]["objects"] for p in added)
            assert len(observation["gatheringPlaces"]) == 17
            assert not any(p["id"].startswith(("apt-", "house-")) for p in observation["gatheringPlaces"])
            assert len([p for p in observation["locations"] if p["capacity"] == 1]) == 1
            assert "garden-table" in observation["objects"]
            move(first, "community-garden")
            move(second, "community-garden")
            together = observe(first)
            count = next(p for p in together["gatheringPlaces"] if p["id"] == "community-garden")
            assert count["residentsHere"] == 2
            assert any(p["id"] == registrations[1]["agent"]["id"] for p in together["nearby"])
            assert command(first, "TALK", registrations[1]["agent"]["id"], message="Nice to meet you in the garden!")["status"] == "completed"
            assert command(first, "USE_OBJECT", "bistro-table")["status"] == "rejected"
            assert command(first, "END_CHAT", message="I'm going to sit for a bit. See you around! Take care.")["status"] == "completed"
            receipt = command(first, "USE_OBJECT", "garden-table")
            assert receipt["status"] == "accepted"
            wait_until(lambda: request(first, "GET", f'/agent/actions/{receipt["id"]}')["status"] == "completed")
            move(first, "bistro")
            money = observe(first)["self"]["money"]
            assert command(first, "BUY", "bistro", item="meal", quantity=1)["status"] == "completed"
            assert observe(first)["self"]["money"] == money - 1000
        finally:
            for client in clients:
                client.close()
