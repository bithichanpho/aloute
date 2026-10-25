-- Reserve already-used business IDs without rewriting/deleting any historical message.
-- V20261104 first rejects duplicates. Invalid legacy participants also require explicit review.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app_document_records
        WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
            AND NULLIF(payload->>'callId','') IS NOT NULL AND (
                length(payload->>'callId')>100 OR
                CASE WHEN payload->>'senderId' ~ '^[0-9]+$'
                    THEN (payload->>'senderId')::numeric NOT BETWEEN 1 AND 9223372036854775807 ELSE true END OR
                CASE WHEN payload->>'receiverId' ~ '^[0-9]+$'
                    THEN (payload->>'receiverId')::numeric NOT BETWEEN 1 AND 9223372036854775807 ELSE true END OR
                payload->>'senderId'=payload->>'receiverId')
    ) THEN
        RAISE EXCEPTION 'Historical CALL identities/participants require reviewed, non-destructive repair before V20261105';
    END IF;
END $$;
INSERT INTO call_sessions(call_id,caller_id,callee_id,call_type,state,created_at,deadline_at,ended_at,processing_status)
SELECT payload->>'callId',(payload->>'senderId')::bigint,(payload->>'receiverId')::bigint,
    CASE WHEN payload->>'callType'='audio' THEN 'audio' ELSE 'video' END,
    CASE WHEN payload->>'callStatus'='MISSED' THEN 'MISSED' ELSE 'ENDED' END,
    created_at,created_at+interval '30 seconds',created_at,'COMPLETED'
FROM app_document_records
WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
    AND NULLIF(payload->>'callId','') IS NOT NULL
ON CONFLICT(call_id) DO NOTHING;
