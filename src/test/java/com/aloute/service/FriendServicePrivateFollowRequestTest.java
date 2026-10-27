package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aloute.model.document.FollowRequest;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.Notifications;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FriendServicePrivateFollowRequestTest {

    @Test
    void savesPrivateFollowRequestWithoutCreatingActivityNotification() {
        IFriendRepository friends = mock(IFriendRepository.class);
        Notifications notifications = mock(Notifications.class);
        ContentAccessService access = mock(ContentAccessService.class);
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        IUserRepository users = mock(IUserRepository.class);

        User privateTarget = new User();
        privateTarget.setId(20L);
        privateTarget.setUsername("private-user");
        privateTarget.setPrivateAccount(true);
        when(users.findById(20L)).thenReturn(Optional.of(privateTarget));
        when(friends.existsByFollowerIdAndFollowedId(10L, 20L)).thenReturn(false);
        when(documentStore.findById("10:20", FollowRequest.class)).thenReturn(null);

        FriendService service = new FriendService(friends, notifications, access, documentStore, users, null);
        assertFalse(service.toggleFollow(10L, 20L));

        verifyNoInteractions(notifications);
    }
}
