ALTER TABLE attractions.points_of_interest
    ADD COLUMN IF NOT EXISTS province varchar(150) NULL,
    ADD COLUMN IF NOT EXISTS ward varchar(150) NULL;

CREATE INDEX IF NOT EXISTS "IX_points_of_interest_admin_area_category_status"
    ON attractions.points_of_interest (province, ward, category, status);
