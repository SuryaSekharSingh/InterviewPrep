ALTER TABLE job ADD COLUMN activity_id VARCHAR(36) REFERENCES activity(id) ON DELETE CASCADE;
CREATE INDEX job_activity_owner ON job(user_id,activity_id);
