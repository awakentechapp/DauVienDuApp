ALTER TABLE ar.ar_info_cards
    ADD COLUMN IF NOT EXISTS model_rotation_degrees double precision NOT NULL DEFAULT 0;
