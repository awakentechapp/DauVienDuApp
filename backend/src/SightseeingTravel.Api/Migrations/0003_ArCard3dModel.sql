ALTER TABLE IF EXISTS ar.ar_info_cards
    ADD COLUMN IF NOT EXISTS model_object_key character varying(512),
    ADD COLUMN IF NOT EXISTS model_status character varying(20) NOT NULL DEFAULT 'None',
    ADD COLUMN IF NOT EXISTS model_error character varying(500),
    ADD COLUMN IF NOT EXISTS node_odm_task_id character varying(128);
