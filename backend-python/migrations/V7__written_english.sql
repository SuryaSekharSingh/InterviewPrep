-- Keep earlier attempts readable while new English practice accepts typed answers.
-- Legacy media records remain for account export/deletion, but no new audio is accepted.
ALTER TABLE english_attempt ADD COLUMN answer TEXT;
UPDATE english_attempt SET answer = confirmed_transcript WHERE confirmed_transcript IS NOT NULL;
