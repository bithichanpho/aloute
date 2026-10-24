-- PostgreSQL-backed storage for aggregates previously stored as MongoDB documents.
-- Relational entities continue to use JPA tables; document-shaped aggregates use JDBC + JSONB.
CREATE TABLE IF NOT EXISTS app_document_records (
    collection_name varchar(80) NOT NULL,
    document_id varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_document_records PRIMARY KEY (collection_name, document_id),
    CONSTRAINT ck_app_document_records_object CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX IF NOT EXISTS ix_app_document_records_collection
    ON app_document_records (collection_name);
CREATE INDEX IF NOT EXISTS ix_app_document_records_payload_gin
    ON app_document_records USING gin (payload jsonb_path_ops);

-- Post feed, community feed and timeline scans.
-- Timestamp fields use text expressions in these indexes. PostgreSQL does not mark
-- casts from JSON text to timestamp/timestamptz as IMMUTABLE, so those casts cannot
-- be used in expression indexes. Queries can still apply typed timestamp comparisons.
CREATE INDEX IF NOT EXISTS ix_doc_posts_created
    ON app_document_records ((payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_user_created
    ON app_document_records ((payload ->> 'userId'), (payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_community_status_created
    ON app_document_records ((payload ->> 'communityId'), (payload ->> 'communityPostStatus'), (payload ->> 'createdAt') DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_moderation_lease
    ON app_document_records ((payload ->> 'moderationStatus'), (payload ->> 'moderationLeaseUntil'))
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_repost_of
    ON app_document_records ((payload ->> 'repostOfId'))
    WHERE collection_name = 'posts' AND payload ? 'repostOfId';
CREATE INDEX IF NOT EXISTS ix_doc_posts_content_fts
    ON app_document_records USING gin (to_tsvector('simple', coalesce(payload ->> 'content', '')))
    WHERE collection_name = 'posts';

-- Comments and reactions.
CREATE INDEX IF NOT EXISTS ix_doc_comments_post_created
    ON app_document_records ((payload ->> 'postId'), (payload ->> 'createdAt') ASC, document_id ASC)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_parent_created
    ON app_document_records ((payload ->> 'parentCommentId'), (payload ->> 'createdAt') ASC)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_moderation_lease
    ON app_document_records ((payload ->> 'moderationStatus'), (payload ->> 'moderationLeaseUntil'))
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ux_doc_likes_post_user
    ON app_document_records ((payload ->> 'postId'), (payload ->> 'userId'))
    WHERE collection_name = 'likes';
CREATE INDEX IF NOT EXISTS ix_doc_likes_post_reaction
    ON app_document_records ((payload ->> 'postId'), (payload ->> 'reactionType'))
    WHERE collection_name = 'likes';
CREATE INDEX IF NOT EXISTS ux_doc_reposts_user_post
    ON app_document_records ((payload ->> 'userId'), (payload ->> 'postId'))
    WHERE collection_name = 'reposts';
CREATE INDEX IF NOT EXISTS ix_doc_post_revisions_post_created
    ON app_document_records ((payload ->> 'postId'), (payload ->> 'createdAt') DESC)
    WHERE collection_name = 'post_revisions';
CREATE INDEX IF NOT EXISTS ux_doc_comment_reactions_comment_user
    ON app_document_records ((payload ->> 'commentId'), (NULLIF(payload ->> 'userId', '')::numeric))
    WHERE collection_name = 'comment_reactions';

-- Messaging, idempotency and unread counters.
CREATE INDEX IF NOT EXISTS ix_doc_chat_conversation_sent
    ON app_document_records ((payload ->> 'conversationId'), (payload ->> 'sentAt') DESC, document_id DESC)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_receiver_unread_sent
    ON app_document_records ((payload ->> 'receiverId'), (NULLIF(payload ->> 'read', '')::boolean), (payload ->> 'sentAt') DESC)
    WHERE collection_name = 'chat_messages';
CREATE UNIQUE INDEX IF NOT EXISTS ux_doc_chat_sender_client
    ON app_document_records ((payload ->> 'senderId'), (payload ->> 'clientId'))
    WHERE collection_name = 'chat_messages' AND payload ? 'clientId' AND payload ->> 'clientId' IS NOT NULL;

-- Social relationship document aggregates.
CREATE UNIQUE INDEX IF NOT EXISTS ux_doc_user_blocks_owner_target
    ON app_document_records ((NULLIF(payload ->> 'ownerId', '')::numeric), (NULLIF(payload ->> 'targetId', '')::numeric))
    WHERE collection_name = 'user_blocks';
CREATE INDEX IF NOT EXISTS ix_doc_user_blocks_target
    ON app_document_records ((NULLIF(payload ->> 'targetId', '')::numeric))
    WHERE collection_name = 'user_blocks';
CREATE INDEX IF NOT EXISTS ix_doc_follow_requests_target_created
    ON app_document_records ((NULLIF(payload ->> 'targetId', '')::numeric), (payload ->> 'createdAt') DESC)
    WHERE collection_name = 'follow_requests';
CREATE UNIQUE INDEX IF NOT EXISTS ux_doc_saved_posts_user_post
    ON app_document_records ((NULLIF(payload ->> 'userId', '')::numeric), (payload ->> 'postId'))
    WHERE collection_name = 'saved_posts';

-- Moderation, security and durable cleanup records.
CREATE INDEX IF NOT EXISTS ix_doc_content_reports_status_created
    ON app_document_records ((payload ->> 'status'), (payload ->> 'createdAt') ASC)
    WHERE collection_name = 'content_reports';
CREATE UNIQUE INDEX IF NOT EXISTS ux_doc_account_security_id
    ON app_document_records (document_id)
    WHERE collection_name = 'account_security';
CREATE INDEX IF NOT EXISTS ix_doc_account_cleanup_next_attempt
    ON app_document_records ((payload ->> 'nextAttemptAt') ASC)
    WHERE collection_name = 'account_cleanup_jobs';
CREATE INDEX IF NOT EXISTS ix_doc_media_status_updated
    ON app_document_records ((payload ->> 'status'), (payload ->> 'updatedAt') ASC)
    WHERE collection_name = 'post_media_assets';
CREATE INDEX IF NOT EXISTS ux_doc_media_public_id
    ON app_document_records ((payload ->> 'resourceType'), (payload ->> 'publicId'))
    WHERE collection_name = 'post_media_assets' AND payload ->> 'publicId' IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_doc_posts_hashtags_gin
    ON app_document_records USING gin ((payload -> 'hashtags') jsonb_path_ops)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_chat_hidden_for_gin
    ON app_document_records USING gin ((payload -> 'hiddenForUserIds') jsonb_path_ops)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_read_by_gin
    ON app_document_records USING gin ((payload -> 'readByUserIds') jsonb_path_ops)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_delivered_to_gin
    ON app_document_records USING gin ((payload -> 'deliveredToUserIds') jsonb_path_ops)
    WHERE collection_name = 'chat_messages';
