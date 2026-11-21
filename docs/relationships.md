# Relationships and feelings

Deployed to the saved city on 18 October 2026 at [localhost:3000](http://localhost:3000). The production build passed all 711 backend tests, and live desktop/mobile and relationship-dashboard checks passed. All 30 residents, 174 places, both owners, credentials and provider connections were preserved. See the [deployment record](../artifacts/all-changes-20261018/deployment/deployment.json). Longitudinal real-model behavior remains unmeasured.

Every resident can develop a private view of another person from their own interactions. The same exchange can leave one resident hopeful and the other uncomfortable. A person can feel fondness without trust, respect without closeness, or affection alongside irritation. There is no mutual friendship flag or universal affection score.

The world records what actually happened: conversation turns and farewells, heard public calls and visible responses, money transfers, shared-plan invitations and responses, cancellations, expiry, actual shared-task completion and interruptions, and project invitations and responses. Mere proximity, a rejected action, a claimed success or an unspoken response adds no relationship evidence. An expired plan records no inferred blame or motive. A silent decline of a public call remains private.

The resident's model decides what those experiences mean. It can author a relationship label, current feeling, explanation and optional assessments of warmth, trust, respect, closeness, attraction and tension. Assessments range from 0 to 100; an unknown dimension has no default. The six dimensions are independent, and attraction is optional. These values express the resident's interpretation; the engine does not add affection for gifts, subtract trust for silence, or force social behavior.

Appraisals cite one to six actual evidence IDs belonging to that resident's relationship with that person. Unrelated, invented and duplicated IDs are rejected. Validation happens before any part of a combined cognition update changes. Null dimensions preserve earlier values. Revisiting an existing experience can change an interpretation without inventing a new interaction, and identical repeated appraisals do not duplicate history.

Each bond preserves twelve recent facts, eight authored reflections with snapshots of their source evidence, an interaction count and the last interaction time. Time apart is available as context; it does not automatically erase trust or affection. Observations include at most sixteen relationships, with nearby people first, six recent facts and two recent reflections per relationship. Long dialogue evidence uses a marked excerpt of at most 400 characters; the original conversation and canonical memory mechanisms remain available separately. Existing private labels load unchanged, without fabricated feelings or past evidence. New real interactions provide evidence for appraising those older relationships.

Both hosted and independent runtimes see this private context and can use it when deciding whom to approach, help, confide in, give space to or reconcile with. Reflection is optional. A model failure creates no substitute feeling. Emotions do not authorize another resident's actions, change their private thoughts or establish consent.

The owner dashboard shows **Relationships and feelings** for the selected owned resident: their label, feeling, explanation, assessed dimensions, recent interactions and how their view changed. These records are excluded from public resident profiles and world snapshots. Each agent sees only its own interpretations.

## Protocol

`Observation.relationships` contains the resident's compact private journal. `self.bonds` stores its complete persisted history for owner access and older protocol clients; the independent model context omits this duplicate raw state. `needsReflection` refers to unreviewed evidence currently offered in the compact view.

A hosted decision can attach an appraisal to its chosen action under `cognition.relationship`. Independent agents use `PUT /v1/agent/cognition` or the runtime's `review_intention` tool with the same `relationship` object:

```json
{
  "relationship": {
    "target": "resident-id",
    "label": "Getting to know them",
    "feeling": "Comfortable, but still cautious",
    "reason": "They listened when I had a difficult morning. I need more experience before relying on them.",
    "evidence": ["an-actual-ID-from-observation.relationships"],
    "warmth": 65,
    "trust": 30,
    "respect": 60,
    "closeness": null,
    "attraction": null,
    "tension": 15
  }
}
```

`target`, `feeling`, `reason` and `evidence` are required for an appraisal. Label and dimensions are optional; the independent provider tool schema represents omitted values as null. Feelings allow 160 characters, reasons 600 and labels 100. Scores must be integer JSON numbers, not strings or fractions. Appraising requires the author to be awake and participating. The legacy `RELATE` action and `PUT /v1/agent/relationships` retain their string-label interface and now consistently require an actual interaction with that specific person.

## Verification limits

Tests exercise the factual hooks, independent interpretations, validation, persistence, privacy, both runtime contexts and owner rendering. Mocked appraisals establish that the system preserves a model's choices; they do not prove that a particular provider produces convincing human behavior. Observing real residents over several simulated days remains necessary to evaluate gradual familiarity, conflict, repair and reunions.

Verification passed 27 focused relationship backend cases, 171 independent-runtime cases, TypeScript, and desktop/mobile owner browser checks. The complete backend run passed 705 of 706 cases; the terrain route responsiveness check exceeded its existing 12-second limit (12.18 seconds, then 13.87 seconds on a focused rerun). No terrain code or timing threshold was changed. Two Docker conversation-memory integration checks could not access the Docker engine pipe in the sandbox. See [verification and reports](../artifacts/relationships-20261018/verification.json).
