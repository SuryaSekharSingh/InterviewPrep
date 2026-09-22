CREATE TABLE local_account (
 user_id VARCHAR(128) PRIMARY KEY REFERENCES edge_user(id) ON DELETE CASCADE,
 username VARCHAR(40) NOT NULL UNIQUE,password_hash VARCHAR(100) NOT NULL
);
CREATE TABLE login_session (
 token_hash VARCHAR(64) PRIMARY KEY,user_id VARCHAR(128) NOT NULL REFERENCES edge_user(id) ON DELETE CASCADE,
 authenticated_at TIMESTAMP WITH TIME ZONE NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX login_session_user_idx ON login_session(user_id);
CREATE TABLE recovery_code (
 code_hash VARCHAR(64) PRIMARY KEY,user_id VARCHAR(128) NOT NULL REFERENCES edge_user(id) ON DELETE CASCADE
);
