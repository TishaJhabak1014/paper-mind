-- Run once on existing PostgreSQL databases before deploying the wider entity mapping.
ALTER TABLE token_response
    ALTER COLUMN processed_prompt TYPE TEXT
    USING processed_prompt::text;
