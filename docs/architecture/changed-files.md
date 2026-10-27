# Danh sách file thay đổi

Đối chiếu SHA-256 với snapshot đầu phiên; workspace không có `.git`. Đây là diff theo checkpoint, không phải upstream Git diff.

Cộng dồn: 56 production Java files mới, 30 test classes mới, 7 architecture documents mới và 36 file cũ sửa; 0 file bị xóa.

| Loại | File |
|---|---|
| New | `docs/architecture/adr-0001-incremental-module-apis.md` |
| New | `docs/architecture/backend-audit.md` |
| New | `docs/architecture/backend-refactor-report.md` |
| New | `docs/architecture/cache-and-calls-report.md` |
| New | `docs/architecture/changed-files.md` |
| New | `docs/architecture/continuation-report.md` |
| New | `docs/architecture/module-boundaries.md` |
| Modified | `pom.xml` |
| New | `src/main/java/com/aloute/administration/api/AccountModeration.java` |
| New | `src/main/java/com/aloute/administration/application/AccountModerationService.java` |
| New | `src/main/java/com/aloute/communities/api/CommunityReviewAccess.java` |
| New | `src/main/java/com/aloute/communities/domain/CommunityRoles.java` |
| New | `src/main/java/com/aloute/communities/infrastructure/JpaCommunityReviewAccess.java` |
| Modified | `src/main/java/com/aloute/controller/ChatController.java` |
| Modified | `src/main/java/com/aloute/controller/NotificationController.java` |
| Modified | `src/main/java/com/aloute/controller/PostController.java` |
| Modified | `src/main/java/com/aloute/controller/ViewController.java` |
| New | `src/main/java/com/aloute/feed/api/FeedInvalidation.java` |
| New | `src/main/java/com/aloute/feed/api/FeedQuery.java` |
| New | `src/main/java/com/aloute/feed/application/FeedPageQuery.java` |
| New | `src/main/java/com/aloute/feed/application/FeedQueryService.java` |
| New | `src/main/java/com/aloute/feed/application/PostFeedInvalidationListener.java` |
| New | `src/main/java/com/aloute/feed/infrastructure/FeedPageCacheInvalidator.java` |
| New | `src/main/java/com/aloute/identity/api/ActionRateLimit.java` |
| New | `src/main/java/com/aloute/identity/api/SessionInvalidation.java` |
| New | `src/main/java/com/aloute/identity/infrastructure/LegacySessionInvalidation.java` |
| New | `src/main/java/com/aloute/media/api/PostMediaAttachments.java` |
| New | `src/main/java/com/aloute/media/domain/MediaDeletionPolicy.java` |
| New | `src/main/java/com/aloute/messaging/api/CallSignaling.java` |
| New | `src/main/java/com/aloute/messaging/api/MissedCallHistory.java` |
| New | `src/main/java/com/aloute/messaging/calls/application/CallRegistryService.java` |
| New | `src/main/java/com/aloute/moderation/api/ModerationPolicy.java` |
| New | `src/main/java/com/aloute/notifications/api/NotificationAccountCleanup.java` |
| New | `src/main/java/com/aloute/notifications/api/NotificationCleanup.java` |
| New | `src/main/java/com/aloute/notifications/api/NotificationIntents.java` |
| New | `src/main/java/com/aloute/notifications/api/Notifications.java` |
| New | `src/main/java/com/aloute/notifications/api/ReliableNotificationPush.java` |
| New | `src/main/java/com/aloute/notifications/application/NotificationAccountCleanupService.java` |
| New | `src/main/java/com/aloute/notifications/application/NotificationApplicationService.java` |
| New | `src/main/java/com/aloute/notifications/application/NotificationCleanupService.java` |
| New | `src/main/java/com/aloute/notifications/application/NotificationDeliveryWorker.java` |
| New | `src/main/java/com/aloute/notifications/application/NotificationOutboxApplicationService.java` |
| New | `src/main/java/com/aloute/posts/api/FeedPostReader.java` |
| New | `src/main/java/com/aloute/posts/api/PostCards.java` |
| New | `src/main/java/com/aloute/posts/api/PostChanged.java` |
| New | `src/main/java/com/aloute/posts/api/PostEditCommand.java` |
| New | `src/main/java/com/aloute/posts/api/PostEditing.java` |
| New | `src/main/java/com/aloute/posts/api/PostNotificationLookup.java` |
| New | `src/main/java/com/aloute/posts/api/PostReviewDispatch.java` |
| New | `src/main/java/com/aloute/posts/api/PostRevisionView.java` |
| New | `src/main/java/com/aloute/posts/api/SavedPosts.java` |
| New | `src/main/java/com/aloute/posts/application/PostEditingService.java` |
| New | `src/main/java/com/aloute/posts/application/SavedPostsService.java` |
| New | `src/main/java/com/aloute/posts/domain/ContentTags.java` |
| New | `src/main/java/com/aloute/posts/domain/PostAccess.java` |
| New | `src/main/java/com/aloute/posts/infrastructure/LegacyPostAccess.java` |
| New | `src/main/java/com/aloute/posts/infrastructure/NotificationPostReader.java` |
| New | `src/main/java/com/aloute/posts/infrastructure/PostFeedReader.java` |
| Modified | `src/main/java/com/aloute/repository/INotificationOutboxRepository.java` |
| Modified | `src/main/java/com/aloute/repository/INotificationRepository.java` |
| Modified | `src/main/java/com/aloute/repository/IUserRepository.java` |
| Modified | `src/main/java/com/aloute/service/AccountSecurityService.java` |
| Modified | `src/main/java/com/aloute/service/AuthRateLimitService.java` |
| Modified | `src/main/java/com/aloute/service/CallTimeoutService.java` |
| Modified | `src/main/java/com/aloute/service/ChatService.java` |
| Modified | `src/main/java/com/aloute/service/CloseFriendService.java` |
| Modified | `src/main/java/com/aloute/service/CommentModerationWorker.java` |
| Modified | `src/main/java/com/aloute/service/CommentPersistenceService.java` |
| Modified | `src/main/java/com/aloute/service/CommunityDataCleanupService.java` |
| Modified | `src/main/java/com/aloute/service/CommunityService.java` |
| Modified | `src/main/java/com/aloute/service/ContentTagService.java` |
| Modified | `src/main/java/com/aloute/service/FeedCacheInvalidator.java` |
| Modified | `src/main/java/com/aloute/service/FriendService.java` |
| Modified | `src/main/java/com/aloute/service/ImageStorageService.java` |
| Modified | `src/main/java/com/aloute/service/LikeService.java` |
| Modified | `src/main/java/com/aloute/service/MediaAssetLifecycleService.java` |
| Modified | `src/main/java/com/aloute/service/ModerationService.java` |
| Modified | `src/main/java/com/aloute/service/NotificationOutboxService.java` |
| Modified | `src/main/java/com/aloute/service/NotificationOutboxWorker.java` |
| Modified | `src/main/java/com/aloute/service/NotificationService.java` |
| Modified | `src/main/java/com/aloute/service/PostModerationCoordinator.java` |
| Modified | `src/main/java/com/aloute/service/PostModerationWorker.java` |
| Modified | `src/main/java/com/aloute/service/PostService.java` |
| Modified | `src/main/java/com/aloute/service/PushNotificationService.java` |
| Modified | `src/main/java/com/aloute/service/SocialFeatureService.java` |
| New | `src/main/java/com/aloute/social/api/FollowedAccounts.java` |
| New | `src/main/java/com/aloute/social/api/ProfileVisibility.java` |
| New | `src/main/java/com/aloute/social/infrastructure/JpaFollowedAccounts.java` |
| New | `src/main/java/com/aloute/social/infrastructure/LegacyProfileVisibility.java` |
| New | `src/main/java/com/aloute/users/api/CallParticipants.java` |
| New | `src/main/java/com/aloute/users/api/UserAccountAdministration.java` |
| New | `src/main/java/com/aloute/users/api/UserDirectory.java` |
| New | `src/main/java/com/aloute/users/infrastructure/JpaCallParticipants.java` |
| New | `src/main/java/com/aloute/users/infrastructure/JpaUserAccountAdministration.java` |
| New | `src/main/java/com/aloute/users/infrastructure/JpaUserDirectory.java` |
| New | `src/test/java/com/aloute/administration/AccountModerationServiceTest.java` |
| New | `src/test/java/com/aloute/architecture/ModuleBoundaryTest.java` |
| New | `src/test/java/com/aloute/communities/CommunityReviewAccessTest.java` |
| New | `src/test/java/com/aloute/controller/CallSignalingContractTest.java` |
| New | `src/test/java/com/aloute/controller/NotificationControllerWebTest.java` |
| New | `src/test/java/com/aloute/feed/FeedCacheBoundaryTest.java` |
| New | `src/test/java/com/aloute/feed/FeedCachedVisibilityTest.java` |
| New | `src/test/java/com/aloute/feed/FeedQueryServiceTest.java` |
| New | `src/test/java/com/aloute/feed/FeedWiringTest.java` |
| New | `src/test/java/com/aloute/identity/SessionInvalidationTest.java` |
| New | `src/test/java/com/aloute/media/MediaDeletionPolicyTest.java` |
| New | `src/test/java/com/aloute/messaging/CallRegistryTest.java` |
| New | `src/test/java/com/aloute/messaging/CallRegistryWiringTest.java` |
| New | `src/test/java/com/aloute/notifications/NotificationAccountCleanupServiceTest.java` |
| New | `src/test/java/com/aloute/notifications/NotificationApplicationServiceTest.java` |
| New | `src/test/java/com/aloute/notifications/NotificationBulkCleanupContractTest.java` |
| New | `src/test/java/com/aloute/notifications/NotificationCleanupServiceTest.java` |
| New | `src/test/java/com/aloute/notifications/NotificationWiringTest.java` |
| New | `src/test/java/com/aloute/posts/NotificationPostReaderTest.java` |
| New | `src/test/java/com/aloute/posts/PostEditingServiceTest.java` |
| New | `src/test/java/com/aloute/posts/PostFeedRefreshTest.java` |
| New | `src/test/java/com/aloute/posts/PostUseCaseWiringTest.java` |
| New | `src/test/java/com/aloute/posts/SavedPostsServiceTest.java` |
| New | `src/test/java/com/aloute/schema/NotificationBulkCleanupIT.java` |
| New | `src/test/java/com/aloute/service/CallTimeoutDeliveryTest.java` |
| Modified | `src/test/java/com/aloute/service/MediaAssetLifecycleServiceTest.java` |
| Modified | `src/test/java/com/aloute/service/NotificationOutboxWorkerTest.java` |
| Modified | `src/test/java/com/aloute/service/NotificationServiceDeliveryTest.java` |
| Modified | `src/test/java/com/aloute/service/PostDeletionCleanupTest.java` |
| New | `src/test/java/com/aloute/service/PostVisibilityCacheRegressionTest.java` |
| New | `src/test/java/com/aloute/service/SocialFeatureAccountModerationTest.java` |
| New | `src/test/java/com/aloute/users/CallParticipantsTest.java` |
| New | `src/test/java/com/aloute/users/UserAccountAdministrationTest.java` |
| New | `src/test/java/com/aloute/users/UserDirectoryTest.java` |

## Review và rollback

- `target/architecture-refactor.patch`: cộng dồn từ snapshot đầu phiên.
- `target/architecture-continuation.patch`: từ đầu lượt 2, gồm lượt 3.
- `target/architecture-cache-calls.patch`: riêng lượt 3.
- Originals trong architecture-original, architecture-continuation-original, architecture-cache-read-original; mỗi baseline SHA được kiểm trước tạo patch.
- Không có database migration, không xóa source/resources, không tự động rollback/deploy. Lưu patch ngoài target trước Maven clean.
