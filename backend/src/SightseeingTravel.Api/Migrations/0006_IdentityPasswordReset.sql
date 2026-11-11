ALTER TABLE IF EXISTS identity.users
    ADD COLUMN IF NOT EXISTS password_reset_code_hash character varying(64),
    ADD COLUMN IF NOT EXISTS password_reset_expires_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS password_reset_requested_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS password_reset_attempts integer NOT NULL DEFAULT 0;
