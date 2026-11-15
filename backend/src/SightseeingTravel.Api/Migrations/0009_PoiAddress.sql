ALTER TABLE attractions.points_of_interest
    ADD COLUMN IF NOT EXISTS address varchar(500) NULL;
