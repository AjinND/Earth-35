ALTER TABLE agent_memories ADD COLUMN request jsonb;
CREATE INDEX agent_memories_entities ON agent_memories USING gin(entities);
CREATE INDEX agent_memories_pending_embedding ON agent_memories(world_minute,id) WHERE embedding IS NULL;
