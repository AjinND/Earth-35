-- Queue one summary per participant and conversation episode. Raw dialogue stays
-- in the existing event log, never in agent_memories. Queue changes share the
-- TALK transaction and therefore survive provider failures and action retries.
ALTER TABLE world_events ADD COLUMN conversation_episode bigint;
CREATE INDEX conversation_episode_events ON world_events(conversation_episode,id) WHERE conversation_episode IS NOT NULL;
CREATE TABLE conversation_memory_queue (
    agent_id text NOT NULL, episode bigint NOT NULL, other_id text NOT NULL,
    through_event bigint NOT NULL, saved_event bigint NOT NULL DEFAULT 0,
    PRIMARY KEY(agent_id,episode)
);
CREATE INDEX conversation_memory_pending ON conversation_memory_queue(agent_id,episode) WHERE through_event>saved_event;

-- Old event logs have no reliable session-close marker; a sixty-minute gap
-- separates historical encounters without inventing dialogue or relationships.
WITH previous AS (
    SELECT id,world_minute,least(actor_id,target_id) a,greatest(actor_id,target_id) b,
           lag(world_minute) OVER(PARTITION BY least(actor_id,target_id),greatest(actor_id,target_id) ORDER BY id) prior
    FROM world_events WHERE kind='conversation' AND actor_id<>'' AND target_id<>'' AND actor_id<>target_id
), grouped AS (
    SELECT *,sum(CASE WHEN prior IS NULL OR world_minute-prior>=60 THEN 1 ELSE 0 END)
        OVER(PARTITION BY a,b ORDER BY id) episode_group FROM previous
), episodes AS (
    SELECT id,min(id) OVER(PARTITION BY a,b,episode_group) episode FROM grouped
)
UPDATE world_events e SET conversation_episode=episodes.episode FROM episodes WHERE e.id=episodes.id;
INSERT INTO conversation_memory_queue(agent_id,episode,other_id,through_event)
SELECT participant.agent,e.conversation_episode,participant.other,max(e.id)
FROM world_events e CROSS JOIN LATERAL (VALUES(e.actor_id,e.target_id),(e.target_id,e.actor_id)) participant(agent,other)
WHERE e.conversation_episode IS NOT NULL
GROUP BY participant.agent,e.conversation_episode,participant.other;

CREATE FUNCTION assign_conversation_episode() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous world_events; ended boolean;
BEGIN
    SELECT * INTO previous FROM world_events WHERE kind='conversation'
      AND ((actor_id=NEW.actor_id AND target_id=NEW.target_id) OR (actor_id=NEW.target_id AND target_id=NEW.actor_id))
      ORDER BY id DESC LIMIT 1;
    SELECT coalesce((data #>> ARRAY['conversations',least(NEW.actor_id,NEW.target_id)||':'||greatest(NEW.actor_id,NEW.target_id),'closed'])::boolean,false)
      INTO ended FROM world_state WHERE id=1;
    NEW.conversation_episode=CASE WHEN previous.id IS NULL OR ended OR NEW.world_minute-previous.world_minute>=60
      THEN NEW.id ELSE coalesce(previous.conversation_episode,previous.id) END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER conversation_episode BEFORE INSERT ON world_events
FOR EACH ROW WHEN (NEW.kind='conversation') EXECUTE FUNCTION assign_conversation_episode();

CREATE FUNCTION queue_conversation_summary() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO conversation_memory_queue(agent_id,episode,other_id,through_event)
    VALUES(NEW.actor_id,NEW.conversation_episode,NEW.target_id,NEW.id),(NEW.target_id,NEW.conversation_episode,NEW.actor_id,NEW.id)
    ON CONFLICT(agent_id,episode) DO UPDATE SET through_event=EXCLUDED.through_event;
    RETURN NEW;
END;
$$;
CREATE TRIGGER conversation_summary AFTER INSERT ON world_events
FOR EACH ROW WHEN (NEW.kind='conversation') EXECUTE FUNCTION queue_conversation_summary();
