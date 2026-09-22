CREATE TABLE progress_activity (
 activity_id VARCHAR(36) PRIMARY KEY REFERENCES activity(id) ON DELETE CASCADE,
 user_id VARCHAR(128) NOT NULL REFERENCES edge_user(id) ON DELETE CASCADE,
 module VARCHAR(20) NOT NULL,score DOUBLE PRECISION NOT NULL,
 retry_group VARCHAR(36) NOT NULL,context TEXT NOT NULL,completed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX progress_user ON progress_activity(user_id,completed_at);

