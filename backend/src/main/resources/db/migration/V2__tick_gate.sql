ALTER TABLE world_state ADD COLUMN last_tick_at timestamptz NOT NULL DEFAULT now();
