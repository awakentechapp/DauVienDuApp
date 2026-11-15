ALTER TABLE ar.ar_info_cards
    ADD COLUMN IF NOT EXISTS scale_factor double precision NOT NULL DEFAULT 1.0;

ALTER TABLE media.community_photos
    ADD COLUMN IF NOT EXISTS elevation_meters double precision NOT NULL DEFAULT 1.5;

ALTER TABLE media.community_photos
    ADD COLUMN IF NOT EXISTS scale_factor double precision NOT NULL DEFAULT 1.0;
