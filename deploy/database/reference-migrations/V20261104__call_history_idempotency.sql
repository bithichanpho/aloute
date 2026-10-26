-- No historical message is deleted, rewritten or silently selected as the winner.
-- Abort safely if old duplicates exist. See docs/quality/backend-bugfix-report.md preflight/repair procedure.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app_document_records
        WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
            AND NULLIF(payload->>'callId','') IS NOT NULL
        GROUP BY payload->>'callId' HAVING count(*)>1
    ) THEN
        RAISE EXCEPTION 'Duplicate historical CALL callId values require reviewed, non-destructive repair before V20261104';
    END IF;
END $$;
CREATE UNIQUE INDEX ux_doc_chat_call_id ON app_document_records ((payload->>'callId'))
    WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
        AND NULLIF(payload->>'callId','') IS NOT NULL;
