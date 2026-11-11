-- ============================================================================
-- SIGHTSEEING TRAVEL - POSTGRESQL MODULAR MONOLITH INITIAL MIGRATION
-- Schemas: identity, attractions, ar, media
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS attractions;
CREATE SCHEMA IF NOT EXISTS ar;
CREATE SCHEMA IF NOT EXISTS media;

-- ============================================================================
-- 1. SCHEMA: identity
-- ============================================================================
CREATE TABLE IF NOT EXISTS identity.users (
    id UUID PRIMARY KEY,
    email VARCHAR(256) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    avatar_url TEXT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('Tourist', 'Admin', 'SuperAdmin')),
    preferred_language VARCHAR(10) NOT NULL DEFAULT 'en',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS "UX_users_email"
    ON identity.users (email);

CREATE TABLE IF NOT EXISTS identity.refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS "IX_refresh_tokens_token_hash"
    ON identity.refresh_tokens (token_hash);

CREATE INDEX IF NOT EXISTS "IX_refresh_tokens_user_id"
    ON identity.refresh_tokens (user_id);

-- ============================================================================
-- 2. SCHEMA: attractions
-- ============================================================================
CREATE TABLE IF NOT EXISTS attractions.points_of_interest (
    id UUID PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    category VARCHAR(40) NOT NULL DEFAULT 'historical_site',
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    activation_radius_meters DOUBLE PRECISION NOT NULL DEFAULT 100.0,
    cover_image_url TEXT NULL,
    cover_object_key VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('Draft', 'PendingApproval', 'Published', 'Rejected', 'Hidden')),
    rejection_reason TEXT NULL,
    created_by UUID NOT NULL,
    approved_by UUID NULL,
    approved_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS "UX_points_of_interest_code"
    ON attractions.points_of_interest (code);

CREATE INDEX IF NOT EXISTS "IX_points_of_interest_status_geo"
    ON attractions.points_of_interest (status, latitude, longitude);

CREATE TABLE IF NOT EXISTS attractions.poi_localizations (
    id UUID PRIMARY KEY,
    poi_id UUID NOT NULL REFERENCES attractions.points_of_interest(id) ON DELETE CASCADE,
    language_code VARCHAR(10) NOT NULL,
    name VARCHAR(250) NOT NULL,
    short_summary VARCHAR(500) NOT NULL,
    cultural_story_markdown TEXT NOT NULL,
    historical_period VARCHAR(150) NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS "UX_poi_localizations_poi_lang"
    ON attractions.poi_localizations (poi_id, language_code);

-- ============================================================================
-- 3. SCHEMA: ar
-- ============================================================================
CREATE TABLE IF NOT EXISTS ar.ar_info_cards (
    id UUID PRIMARY KEY,
    poi_id UUID NOT NULL CONSTRAINT "FK_ar_info_cards_poi_id_points_of_interest"
        REFERENCES attractions.points_of_interest(id) ON DELETE CASCADE,
    card_type VARCHAR(40) NOT NULL CHECK (card_type IN ('StoryHighlight', 'HistoricalFact', 'ArchitectureNote', 'DirectionHint')),
    azimuth_degrees DOUBLE PRECISION NOT NULL,
    distance_meters DOUBLE PRECISION NOT NULL,
    elevation_meters DOUBLE PRECISION NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS "IX_ar_info_cards_poi_active"
    ON ar.ar_info_cards (poi_id, is_active, display_order);

CREATE TABLE IF NOT EXISTS ar.ar_info_card_localizations (
    id UUID PRIMARY KEY,
    ar_info_card_id UUID NOT NULL REFERENCES ar.ar_info_cards(id) ON DELETE CASCADE,
    language_code VARCHAR(10) NOT NULL,
    title VARCHAR(200) NOT NULL,
    subtitle VARCHAR(300) NULL,
    content TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS "UX_ar_card_localizations_card_lang"
    ON ar.ar_info_card_localizations (ar_info_card_id, language_code);

-- ============================================================================
-- 4. SCHEMA: media
-- ============================================================================
CREATE TABLE IF NOT EXISTS media.community_photos (
    id UUID PRIMARY KEY,
    poi_id UUID NOT NULL,
    user_id UUID NOT NULL,
    author_display_name VARCHAR(150) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    image_url TEXT NOT NULL,
    caption VARCHAR(500) NULL,
    captured_latitude DOUBLE PRECISION NOT NULL,
    captured_longitude DOUBLE PRECISION NOT NULL,
    distance_from_poi_meters DOUBLE PRECISION NOT NULL,
    azimuth_degrees DOUBLE PRECISION NULL,
    pitch_degrees DOUBLE PRECISION NULL,
    like_count INTEGER NOT NULL DEFAULT 0,
    report_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL CHECK (status IN ('Published', 'Flagged', 'RemovedByModerator', 'DeletedByOwner')),
    moderated_by UUID NULL,
    moderated_at TIMESTAMPTZ NULL,
    moderation_note TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS "IX_community_photos_poi_feed"
    ON media.community_photos (poi_id, status, like_count DESC, created_at DESC);

CREATE TABLE IF NOT EXISTS media.photo_likes (
    id UUID PRIMARY KEY,
    photo_id UUID NOT NULL REFERENCES media.community_photos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS "UX_photo_likes_photo_user"
    ON media.photo_likes (photo_id, user_id);

CREATE TABLE IF NOT EXISTS media.photo_reports (
    id UUID PRIMARY KEY,
    photo_id UUID NOT NULL REFERENCES media.community_photos(id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('Pending', 'ResolvedKeep', 'ResolvedRemove')),
    resolved_by UUID NULL,
    resolved_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS "UX_photo_reports_photo_reporter"
    ON media.photo_reports (photo_id, reporter_user_id);
