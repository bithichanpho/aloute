package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.CloseFriendService;
import com.aloute.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class CloseFriendPrivacyControllerTest {
    @Test
    void decisionUsesAuthenticatedRecipientRatherThanTargetAsActor() {
        var relationships = mock(CloseFriendService.class);
        var controller = new CloseFriendController(relationships, mock(UserService.class));
        var user = new User(); user.setId(2L); user.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(user);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        controller.accept(auth, 1L);
        verify(relationships).acceptRequest(2L, 1L);
        controller.remove(auth, 1L);
        verify(relationships).removeRelationship(2L, 1L);
    }

    @Test
    void anonymousCannotMutateOrListRelationships() {
        var relationships = mock(CloseFriendService.class);
        var controller = new CloseFriendController(relationships, mock(UserService.class));
        assertThrows(UnauthorizedException.class, () -> controller.sendRequest(null, 1L));
        assertThrows(UnauthorizedException.class, () -> controller.accept(null, 1L));
        assertThrows(UnauthorizedException.class, () -> controller.remove(null, 1L));
        assertThrows(UnauthorizedException.class, () -> controller.list(null, 1L, 0, 20));
        verifyNoInteractions(relationships);
    }
}
