// Run with: mongosh "$MONGO_URI" deploy/migrations/mongo-indexes.js
// Index builds are outside application startup so deploy replicas do not all attempt DDL.
const migrations = db.getCollection('schema_migrations');
const id = '20261025-social-read-indexes-v1';
if (!migrations.findOne({ _id: id })) {
  db.user_blocks.createIndex({ ownerId: 1, targetId: 1 }, { unique: true });
  db.user_blocks.createIndex({ targetId: 1 });
  db.follow_requests.createIndex({ targetId: 1 });
  db.saved_posts.createIndex({ userId: 1, createdAt: -1 });
  db.content_reports.createIndex({ status: 1, createdAt: 1 });
  db.post_revisions.createIndex({ postId: 1, createdAt: -1 });
  db.comment_reactions.createIndex(
    { commentId: 1, userId: 1 },
    { name: 'comment_reaction_unique', unique: true },
  );
  db.posts.createIndex({ createdAt: -1, _id: -1 }, { name: 'posts_created_id_desc' });
  db.posts.createIndex(
    { userId: 1, createdAt: -1, _id: -1 },
    { name: 'posts_user_created_id_desc' },
  );
  db.posts.createIndex({ hashtags: 1 });
  db.posts.createIndex({ mentions: 1 });
  db.chat_messages.createIndex(
    { conversationId: 1, sentAt: -1, _id: -1 },
    { name: 'chat_conversation_sent_id_desc' },
  );
  db.chat_messages.createIndex(
    { conversationId: 1, content: 'text' },
    { name: 'chat_conversation_text', default_language: 'none' },
  );
  migrations.insertOne({ _id: id, appliedAt: new Date() });
}

// Keep explicit production indexes in sync with document-level index declarations. Production
// disables Spring auto-index creation so every new/old cluster receives the same query and
// idempotency guarantees. This is a separate migration because v1 may already be recorded.
const modelIndexId = '20261025-model-indexes-v2';
if (!migrations.findOne({ _id: modelIndexId })) {
  db.chat_messages.createIndex(
    { conversationId: 1, sentAt: -1, _id: -1 },
    { name: 'chat_conversation_sent_id_desc' },
  );
  db.chat_messages.createIndex(
    { receiverId: 1, read: 1, senderId: 1 },
    { name: 'chat_unread_sender' },
  );
  db.chat_messages.createIndex(
    { senderId: 1, sentAt: -1 },
    { name: 'chat_sender_sent' },
  );
  db.chat_messages.createIndex(
    { receiverId: 1, sentAt: -1 },
    { name: 'chat_receiver_sent' },
  );
  db.chat_messages.createIndex(
    { groupId: 1, sentAt: -1 },
    { name: 'chat_group_sent' },
  );
  db.chat_messages.createIndex(
    { senderId: 1, clientId: 1 },
    {
      name: 'chat_sender_client_id_unique',
      unique: true,
      partialFilterExpression: { clientId: { $type: 'string' } },
    },
  );
  db.chat_messages.createIndex(
    { conversationId: 1, content: 'text' },
    { name: 'chat_conversation_text', default_language: 'none' },
  );

  db.likes.createIndex(
    { postId: 1, userId: 1 },
    { name: 'post_user_unique', unique: true },
  );
  db.likes.createIndex(
    { userId: 1, createdAt: -1 },
    { name: 'like_user_created' },
  );
  db.reposts.createIndex(
    { userId: 1, postId: 1 },
    { name: 'repost_user_post_unique', unique: true },
  );
  db.reposts.createIndex(
    { postId: 1, createdAt: -1 },
    { name: 'repost_post_created' },
  );
  db.reposts.createIndex(
    { userId: 1, createdAt: -1 },
    { name: 'repost_user_created' },
  );

  db.posts.createIndex(
    { communityId: 1, createdAt: -1 },
    { name: 'posts_community_created' },
  );
  db.posts.createIndex(
    { repostOfId: 1, createdAt: -1 },
    { name: 'posts_repost_origin_created' },
  );
  db.posts.createIndex(
    { hashtags: 1, createdAt: -1 },
    { name: 'posts_hashtag_created' },
  );
  db.posts.createIndex(
    { mentions: 1, createdAt: -1 },
    { name: 'posts_mention_created' },
  );
  db.posts.createIndex(
    { moderationStatus: 1, moderationLeaseUntil: 1, createdAt: 1 },
    { name: 'posts_moderation_recovery' },
  );
  // MongoDB permits only one text index per collection. Older databases may already have the
  // Spring-generated text index under a different name, so create it only when none exists.
  if (!db.posts.getIndexes().some((index) => index.key && index.key._fts === 'text')) {
    db.posts.createIndex(
      { content: 'text' },
      { name: 'post_content_text', default_language: 'none' },
    );
  }

  db.comments.createIndex(
    { postId: 1, createdAt: 1 },
    { name: 'comments_post_created' },
  );
  db.comments.createIndex(
    { parentCommentId: 1, createdAt: 1 },
    { name: 'comments_parent_created' },
  );
  db.comments.createIndex(
    { userId: 1, createdAt: -1 },
    { name: 'comments_user_created' },
  );
  db.comments.createIndex(
    { moderationStatus: 1, moderationLeaseUntil: 1, createdAt: 1 },
    { name: 'comments_moderation_recovery' },
  );

  db.post_media_assets.createIndex({ url: 1 });
  db.post_media_assets.createIndex(
    { status: 1, uploadedAt: 1 },
    { name: 'post_media_status_uploaded' },
  );
  db.post_media_assets.createIndex(
    { status: 1, updatedAt: 1 },
    { name: 'post_media_status_updated' },
  );
  db.post_media_assets.createIndex(
    { purpose: 1, uploaderId: 1 },
    { name: 'post_media_purpose_uploader' },
  );
  db.post_media_assets.createIndex(
    { postId: 1, status: 1 },
    { name: 'post_media_post_status' },
  );
  db.account_cleanup_jobs.createIndex({ nextAttemptAt: 1 });

  migrations.insertOne({ _id: modelIndexId, appliedAt: new Date() });
}

const cursorIndexId = '20261028-social-cursor-indexes-v3';
if (!migrations.findOne({ _id: cursorIndexId })) {
  db.user_blocks.createIndex(
    { ownerId: 1, createdAt: -1, _id: -1 },
    { name: 'user_blocks_owner_created_id' },
  );
  db.follow_requests.createIndex(
    { targetId: 1, createdAt: -1, _id: -1 },
    { name: 'follow_requests_target_created_id' },
  );
  migrations.insertOne({ _id: cursorIndexId, appliedAt: new Date() });
}

const mediaReconciliationId = '20261031-hidden-post-media-reconciliation-v1';
if (!migrations.findOne({ _id: mediaReconciliationId })) {
  db.posts.createIndex(
    { moderationStatus: 1, mediaCleanupQueuedAt: 1, createdAt: 1, _id: 1 },
    { name: 'posts_hidden_media_cleanup' },
  );
  migrations.insertOne({ _id: mediaReconciliationId, appliedAt: new Date() });
}
