ALTER TABLE attractions.points_of_interest
    ADD COLUMN IF NOT EXISTS category VARCHAR(40) NOT NULL DEFAULT 'historical_site';

ALTER TABLE media.community_photos
    ADD COLUMN IF NOT EXISTS pitch_degrees DOUBLE PRECISION NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ar.ar_info_cards'::regclass
          AND conname = 'FK_ar_info_cards_poi_id_points_of_interest'
    ) THEN
        ALTER TABLE ar.ar_info_cards
            ADD CONSTRAINT "FK_ar_info_cards_poi_id_points_of_interest"
            FOREIGN KEY (poi_id)
            REFERENCES attractions.points_of_interest(id)
            ON DELETE CASCADE
            NOT VALID;
    END IF;
END;
$$;
