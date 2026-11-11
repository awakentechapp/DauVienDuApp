ALTER TABLE IF EXISTS ar.ar_info_cards
    ADD COLUMN IF NOT EXISTS model_name character varying(200),
    ADD COLUMN IF NOT EXISTS model_category character varying(20) NOT NULL DEFAULT 'Object';
