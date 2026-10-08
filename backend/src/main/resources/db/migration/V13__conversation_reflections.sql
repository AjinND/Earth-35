-- Optional conversation memories are proposals until the participant's model
-- checks that they add a distinct insight beyond the canonical encounter summary.
-- Retain rejected proposals for retry/audit purposes, outside recall and counts.
CREATE TABLE conversation_reflections (
    memory_id text NOT NULL REFERENCES agent_memories(id),
    episode bigint NOT NULL,
    reviewed boolean NOT NULL DEFAULT false,
    accepted boolean NOT NULL DEFAULT false,
    PRIMARY KEY(memory_id,episode)
);
CREATE INDEX conversation_reflections_pending ON conversation_reflections(episode) WHERE NOT reviewed;

INSERT INTO conversation_reflections(memory_id,episode)
SELECT DISTINCT m.id,e.conversation_episode FROM agent_memories m
JOIN world_events e ON m.source_events @> jsonb_build_array(e.id)
WHERE e.conversation_episode IS NOT NULL AND m.request_id NOT LIKE 'conversation-summary:%'
  AND (e.actor_id=m.agent_id OR e.target_id=m.agent_id);

INSERT INTO conversation_reflections(memory_id,episode)
SELECT DISTINCT ON (m.id,q.other_id) m.id,q.episode FROM agent_memories m
CROSS JOIN LATERAL jsonb_array_elements_text(m.entities) entity(person)
JOIN conversation_memory_queue q ON q.agent_id=m.agent_id AND q.other_id=entity.person
WHERE m.request_id NOT LIKE 'conversation-summary:%'
  AND NOT EXISTS (SELECT 1 FROM conversation_reflections r WHERE r.memory_id=m.id)
ORDER BY m.id,q.other_id,q.episode DESC
ON CONFLICT DO NOTHING;

CREATE VIEW active_agent_memories AS
SELECT m.* FROM agent_memories m WHERE NOT EXISTS (
    SELECT 1 FROM conversation_reflections r WHERE r.memory_id=m.id AND NOT r.accepted
);
