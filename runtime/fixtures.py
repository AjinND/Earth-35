"""Explicitly scripted EXTERNAL agents for protocol/load testing, never live-AI validation."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import time
import uuid
try:
    from .client import Earth35
except ImportError:  # Direct execution: python runtime/fixtures.py
    from client import Earth35

NAMES = ["Alex", "Maya", "Oliver", "Luna", "Theo", "Iris", "Jasper", "Sage", "Felix", "Nova", "Arlo", "Wren", "Hugo", "Cleo", "Leo", "Ada", "Finn", "Ruby", "Ezra", "Ivy"]


def provision(path, count):
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = json.loads(path.read_text()) if path.exists() else []
    if len(rows) >= count:
        return rows[:count]
    email = os.environ.get("E35_FIXTURE_EMAIL", "fixture-owner@earth35.local")
    password = os.environ.get("E35_FIXTURE_PASSWORD")
    if not password:
        raise SystemExit("Set E35_FIXTURE_PASSWORD (12+ characters) for the dedicated fixture owner.")
    owner = Earth35()
    try:
        try:
            owner.call("POST", "/auth/register", {"email": email, "password": password})
        except Exception:
            owner.call("POST", "/auth/login", {"email": email, "password": password})
        existing = owner.call("GET", "/owner/agents")
        for index in range(len(rows), count):
            if index < len(existing):
                agent = existing[index]
                token = owner.call("POST", f"/owner/agents/{agent['id']}/credentials")["token"]
            else:
                result = owner.call("POST", "/agents", {
                    "name": NAMES[index % len(NAMES)] + (f" {index + 1}" if index >= 20 else ""),
                    "description": "A scripted test resident, run by an external development fixture.",
                    "personality": "Deterministic protocol fixture; not an AI personality.",
                    "goals": "Exercise movement, employment, conversations, and daily needs.", "fixture": True,
                })
                agent, token = result["agent"], result["token"]
            rows.append({"id": agent["id"], "name": agent["name"], "token": token})
            # Persist incrementally so interruption does not lose issued credentials.
            path.write_text(json.dumps(rows, indent=2))
        return rows
    finally:
        owner.close()


def step(client, index, iteration):
    observation = client.observe()
    a = observation["self"]
    if a["paused"] or a.get("pending"):
        return
    if a["hunger"] > 55:
        if a["inventory"].get("meal", 0):
            client.action("EAT", item="meal")
        elif a["location"] == "grocery":
            client.action("BUY", target="grocery", item="meal", quantity=1)
        else:
            client.action("MOVE", target="grocery")
    elif a["energy"] < 30:
        client.action("SLEEP") if a["location"] == a["home"] else client.action("MOVE", target=a["home"])
    elif not a["job"]:
        job = observation["jobs"][index % len(observation["jobs"])]
        if a["location"] != job["building"]:
            client.action("MOVE", target=job["building"])
        elif a["offer"]:
            client.action("ACCEPT_JOB", target=a["offer"])
        else:
            client.action("APPLY_JOB", target=job["id"])
    elif iteration % 4 == 0 and observation["nearby"]:
        other = observation["nearby"][0]
        if ((a["x"] - other["x"]) ** 2 + (a["z"] - other["z"]) ** 2) ** .5 <= 10:
            result = client.action("TALK", target=other["id"], message=f"Hello {other['name']}. How is life in New Eden?")
            if result["status"] == "completed":
                client.remember(kind="episodic", content=f"I greeted {other['name']}. This is a scripted fixture memory.", entities=[other["id"], other["name"]], sourceEvents=[])
                client.call("PUT", "/agent/relationships", {"target": other["id"], "label": "Acquaintance (scripted fixture)"})
    else:
        job = next(j for j in observation["jobs"] if j["id"] == a["job"])
        destination = "park" if iteration % 8 < 3 else job["building"]
        if a["location"] != destination:
            client.action("MOVE", target=destination)
        else:
            client.action("REST" if destination == "park" else "WORK")


def main(args):
    rows = provision(Path(args.credentials), args.count)
    clients = [Earth35(row["token"]) for row in rows]
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.count) as pool:
            for iteration in range(args.steps):
                futures = [pool.submit(step, client, index, iteration) for index, client in enumerate(clients)]
                for future in futures:
                    try:
                        future.result()
                    except Exception as exc:
                        print(f"Fixture request failed: {type(exc).__name__}", flush=True)
                if iteration % 10 == 0:
                    print(f"Scripted external fixture: {len(clients)} agents, step {iteration}/{args.steps}", flush=True)
                time.sleep(args.interval)
    finally:
        for client in clients:
            client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--steps", type=int, default=1000)
    parser.add_argument("--interval", type=float, default=3)
    parser.add_argument("--credentials", default=str(Path(__file__).with_name("agents.json")))
    args = parser.parse_args()
    if not 1 <= args.count <= 50 or args.steps < 1 or args.interval < 1:
        parser.error("Count must be 1–50; steps and interval must be positive")
    main(args)
