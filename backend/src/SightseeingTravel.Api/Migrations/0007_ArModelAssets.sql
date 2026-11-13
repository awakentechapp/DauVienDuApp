CREATE TABLE IF NOT EXISTS ar.ar_model_assets
(
    id uuid PRIMARY KEY,
    name character varying(200) NOT NULL,
    category character varying(20) NOT NULL DEFAULT 'Object',
    model_object_key character varying(512),
    model_status character varying(20) NOT NULL DEFAULT 'Processing',
    model_error character varying(500),
    node_odm_task_id character varying(128),
    model_rotation_degrees double precision NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS "IX_ar_model_assets_status"
    ON ar.ar_model_assets (model_status);

CREATE INDEX IF NOT EXISTS "IX_ar_model_assets_object_key"
    ON ar.ar_model_assets (model_object_key);

-- Preserve existing ready models in the new independent catalog. Their model key remains shared
-- with the original AR card, so placement continues to reuse the same private object.
INSERT INTO ar.ar_model_assets
    (id, name, category, model_object_key, model_status, model_rotation_degrees, created_at, updated_at)
SELECT DISTINCT ON (card.model_object_key)
    card.id,
    COALESCE(NULLIF(card.model_name, ''), localization.title, 'Vật thể 3D'),
    card.model_category,
    card.model_object_key,
    'Ready',
    card.model_rotation_degrees,
    card.created_at,
    card.updated_at
FROM ar.ar_info_cards AS card
LEFT JOIN LATERAL
(
    SELECT title
    FROM ar.ar_info_card_localizations AS localization
    WHERE localization.ar_info_card_id = card.id
    ORDER BY CASE WHEN lower(localization.language_code) = 'vi' THEN 0 ELSE 1 END,
             localization.language_code
    LIMIT 1
) AS localization ON TRUE
WHERE card.model_status = 'Ready'
  AND card.model_object_key IS NOT NULL
  AND NOT EXISTS
  (
      SELECT 1
      FROM ar.ar_model_assets AS existing
      WHERE existing.model_object_key = card.model_object_key
  )
ORDER BY card.model_object_key, card.created_at DESC;
