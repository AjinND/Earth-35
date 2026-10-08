ALTER TABLE hosted_agents DROP CONSTRAINT hosted_agents_provider_check;
ALTER TABLE hosted_agents ADD CONSTRAINT hosted_agents_provider_check
    CHECK (provider IN ('nvidia', 'openrouter', 'openai', 'anthropic'));
