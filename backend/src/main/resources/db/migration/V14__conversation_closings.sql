-- The world store explicitly attaches spoken closings to the preceding episode.
-- Ordinary TALK still starts a new episode when the saved chat is closed.
CREATE OR REPLACE FUNCTION assign_conversation_episode() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous world_events; ended boolean;
BEGIN
    IF NEW.conversation_episode IS NOT NULL THEN
        RETURN NEW;
    END IF;
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
