-- The transcription worker and upload endpoints no longer exist.
-- Keep historical rows for audit and account export, but do not leave jobs queued forever.
UPDATE job
SET state = 'FAILED', error_code = 'AUDIO_FEATURE_REMOVED', lease_until = NULL
WHERE kind = 'TRANSCRIBE' AND state IN ('QUEUED', 'RUNNING');
