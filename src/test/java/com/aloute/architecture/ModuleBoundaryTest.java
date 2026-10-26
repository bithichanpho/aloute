package com.aloute.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Enforces migrated slices only. Legacy dependencies are explicit, never globally suppressed. */
class ModuleBoundaryTest {
    private static final JavaClasses MODULES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aloute.notifications", "com.aloute.users",
                    "com.aloute.posts", "com.aloute.social", "com.aloute.feed", "com.aloute.media", "com.aloute.messaging", "com.aloute.communities", "com.aloute.identity", "com.aloute.moderation", "com.aloute.administration");

    @Test
    void publicApisNeverExposePersistenceOrLegacyServices() {
        noClasses().that().resideInAnyPackage("com.aloute..api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.aloute.repository..", "com.aloute.model..",
                        "com.aloute.service..", "com.aloute.persistence..",
                        "jakarta.persistence..", "org.springframework.data..")
                .check(MODULES);
    }

    @Test
    void notificationApplicationOnlyUsesPublicPortsAndOwnedPersistence() {
        // Owned legacy entities/repositories retain their JPA names and table mappings.
        var ownedPersistence = new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                "public ports, framework or explicitly owned notification persistence") {
            public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                String name = type.getName();
                return !name.startsWith("com.aloute.")
                        || name.startsWith("com.aloute.notifications.")
                        || name.startsWith("com.aloute.users.api.")
                        || name.startsWith("com.aloute.posts.api.")
                        || name.startsWith("com.aloute.social.api.")
                        || name.startsWith("com.aloute.common.")
                        || name.startsWith("com.aloute.dto.response.")
                        || java.util.Set.of("com.aloute.model.entity.Notification",
                                "com.aloute.model.entity.NotificationOutboxEvent",
                                "com.aloute.repository.INotificationRepository",
                                "com.aloute.repository.INotificationOutboxRepository",
                                "com.aloute.repository.IUserPushTokenRepository").contains(name);
            }
        };
        classes().that().resideInAPackage("com.aloute.notifications.application..")
                .should().onlyDependOnClassesThat(ownedPersistence).check(MODULES);
    }

    @Test
    void crossModuleDependenciesUsePublicApis() {
        for (String module : java.util.List.of("notifications", "users", "posts", "social", "feed", "media", "messaging", "communities", "identity", "moderation", "administration")) {
            for (String other : java.util.List.of("notifications", "users", "posts", "social", "feed", "media", "messaging", "communities", "identity", "moderation", "administration")) {
                if (module.equals(other)) continue;
                noClasses().that().resideInAPackage("com.aloute." + module + "..")
                        .should().dependOnClassesThat()
                        .resideInAnyPackage("com.aloute." + other + ".application..",
                                "com.aloute." + other + ".infrastructure..",
                                "com.aloute." + other + ".domain..")
                        .check(MODULES);
            }
        }
    }

    @Test
    void feedOnlyUsesPublicPostReadContracts() {
        classes().that().resideInAnyPackage("com.aloute.feed.api..", "com.aloute.feed.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "org..", "com.aloute.feed..", "com.aloute.posts.api..",
                        "com.aloute.dto.response..", "com.aloute.common.exception..")
                .check(MODULES);
    }

    @Test
    void legacyConsumersNoLongerCallNotificationPersistenceDirectly() {
        var legacy = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importClasses(com.aloute.service.PostService.class,
                        com.aloute.service.CommunityDataCleanupService.class,
                        com.aloute.service.AccountSecurityService.class);
        for (var type : legacy) {
            for (var call : type.getMethodCallsFromSelf()) {
                String target = call.getTargetOwner().getName();
                org.junit.jupiter.api.Assertions.assertFalse(java.util.Set.of(
                        "com.aloute.repository.INotificationRepository",
                        "com.aloute.repository.INotificationOutboxRepository",
                        "com.aloute.repository.IUserPushTokenRepository").contains(target), call.getDescription());
            }
        }
    }

    @Test
    void postUseCasesOnlyDependOnPublicCollaboratorsAndOwnedPersistence() {
        var allowed = new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                "Posts types, public collaborator APIs and owned legacy persistence") {
            public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                String name = type.getName();
                return !name.startsWith("com.aloute.")
                        || name.startsWith("com.aloute.posts.")
                        || name.startsWith("com.aloute.identity.api.")
                        || name.startsWith("com.aloute.media.api.")
                        || name.startsWith("com.aloute.moderation.api.")
                        || name.startsWith("com.aloute.dto.response.")
                        || name.startsWith("com.aloute.common.exception.")
                        || name.equals("com.aloute.common.util.TransactionCommitHooks")
                        || name.startsWith("com.aloute.persistence.postgres.document.")
                        || java.util.Set.of("com.aloute.model.document.Post",
                                "com.aloute.model.document.PostRevision", "com.aloute.model.document.PostVisibility",
                                "com.aloute.model.document.ModerationStatus", "com.aloute.model.document.SavedPost").contains(name);
            }
        };
        classes().that().resideInAPackage("com.aloute.posts.application..")
                .should().onlyDependOnClassesThat(allowed).check(MODULES);
    }

    @Test
    void socialFacadeDoesNotAccessSavedPostPersistence() {
        var facade = new ClassFileImporter().importClasses(com.aloute.service.SocialFeatureService.class);
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.aloute.model.document.SavedPost")
                .check(facade);
    }

    @Test
    void feedCacheInfrastructureOnlyUsesFeedAndFrameworkDependencies() {
        classes().that().resideInAPackage("com.aloute.feed.infrastructure..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "org..", "com.github.benmanes.caffeine..", "com.aloute.feed..")
                .check(MODULES);
    }

    @Test
    void accountModerationOrchestratorOnlyCallsPublicOwnerApis() {
        classes().that().resideInAPackage("com.aloute.administration.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org..",
                        "com.aloute.administration..", "com.aloute.users.api..", "com.aloute.identity.api..")
                .check(MODULES);
    }

    @Test
    void socialFacadeDoesNotCallUserPersistenceOrPrivateIdentityServices() {
        var facade = new ClassFileImporter().importClasses(com.aloute.service.SocialFeatureService.class);
        for (var type : facade) {
            for (var call : type.getMethodCallsFromSelf()) {
                org.junit.jupiter.api.Assertions.assertFalse(java.util.Set.of(
                        "com.aloute.repository.IUserRepository", "com.aloute.service.RefreshTokenService",
                        "com.aloute.service.AuthenticationUserCache").contains(call.getTargetOwner().getName()),
                        call.getDescription());
            }
        }
    }

    @Test
    void callStateMachineOnlyUsesPublicOwnerApis() {
        classes().that().resideInAPackage("com.aloute.messaging.calls.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "org..", "com.aloute.messaging..",
                        "com.aloute.users.api..", "com.aloute.notifications.api..")
                .check(MODULES);
    }

    @Test
    void migratedModulesHaveNoCycles() {
        slices().matching("com.aloute.(*)..").should().beFreeOfCycles().check(MODULES);
    }
}
