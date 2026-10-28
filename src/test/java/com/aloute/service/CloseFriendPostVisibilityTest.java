package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.model.document.*;
import com.aloute.model.entity.*;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import com.aloute.social.api.CloseFriendships;
import com.aloute.social.infrastructure.JpaCloseFriendships;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CloseFriendPostVisibilityTest {
    final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    final IUserRepository users = mock(IUserRepository.class);
    final IFriendRepository follows = mock(IFriendRepository.class);
    final ICloseFriendRepository relationships = mock(ICloseFriendRepository.class);
    final ContentAccessService access = new ContentAccessService(store,users,follows,
            mock(ICommunityRepository.class),mock(ICommunityMemberRepository.class),new JpaCloseFriendships(relationships));
    final User author = author(1L);
    final Post post = post(1L);

    CloseFriendPostVisibilityTest() {
        when(users.findById(1L)).thenReturn(Optional.of(author));
        when(users.findAllById(any())).thenReturn(List.of(author));
        when(store.findById("post1",Post.class)).thenReturn(post);
        mutual(true);
    }
    void mutual(boolean accepted) {
        when(follows.existsByFollowerIdAndFollowedId(2L,1L)).thenReturn(accepted);
        when(follows.existsByFollowerIdAndFollowedId(1L,2L)).thenReturn(accepted);
        when(follows.findByFollowerIdAndFollowedIdIn(eq(2L),any())).thenReturn(accepted ? List.of(new Friend(2L,1L)) : List.of());
        when(follows.findByFollowedIdAndFollowerIdIn(eq(2L),any())).thenReturn(accepted ? List.of(new Friend(1L,2L)) : List.of());
    }
    void relationship(String status,boolean reverse) {
        var close = new CloseFriend(reverse?2L:1L,reverse?1L:2L); close.setStatus(status);
        when(relationships.findRelationships(eq(2L),any())).thenReturn(List.of(close));
    }
    void assertViewer(boolean allowed) {
        assertEquals(allowed,access.canRead(post,2L));
        assertEquals(allowed,access.forPosts(List.of(post),2L).canRead(post));
        if (!allowed) assertThrows(com.aloute.common.exception.ResourceNotFoundException.class, () -> access.requirePost("post1",2L));
    }

    @Test void onlyAcceptedCloseFriendsWithCurrentMutualFollowsCanRead() {
        assertViewer(false);
        relationship(CloseFriend.PENDING,false); assertViewer(false);
        for (boolean reverse : List.of(false,true)) { relationship(CloseFriend.ACCEPTED,reverse); assertViewer(true); }
        assertFalse(access.canRead(post,null));
        assertFalse(access.forPosts(List.of(post),null).canRead(post));
        assertTrue(access.canRead(post,1L));
        assertTrue(access.forPosts(List.of(post),1L).canRead(post));
        mutual(false); assertViewer(false);
        mutual(true); when(relationships.findRelationships(eq(2L),any())).thenReturn(List.of()); assertViewer(false);
    }

    @Test void privateBlockDeactivationAndModerationStillApply() {
        relationship(CloseFriend.ACCEPTED,false); author.setPrivateAccount(true); assertViewer(true);
        post.setModerationStatus(ModerationStatus.PENDING); assertViewer(false);
        post.setModerationStatus(ModerationStatus.HIDDEN); assertViewer(false);
        post.setModerationStatus(ModerationStatus.FLAGGED); post.setModerationFailureReason(null); assertViewer(true);
        post.setModerationFailureReason("MODERATION_UNAVAILABLE"); assertViewer(false);
        post.setModerationStatus(ModerationStatus.APPROVED); post.setModerationFailureReason(null); author.setActive(false); assertViewer(false);
        author.setActive(true);
        when(store.exists(any(),eq(UserBlock.class))).thenReturn(true);
        var block=new UserBlock(); block.setOwnerId(1L); block.setTargetId(2L);
        when(store.find(any(),eq(UserBlock.class))).thenReturn(List.of(block)); assertViewer(false);
    }

    @Test void repostOfCloseFriendsPostCannotExposeOriginalToNonMembersEvenForRepostAuthor() {
        var repost = post(2L); repost.setId("repost"); repost.setRepostOfId("post1"); repost.setVisibility(PostVisibility.PUBLIC);
        when(users.findById(2L)).thenReturn(Optional.of(author(2L)));
        when(users.findAllById(any())).thenReturn(List.of(author,author(2L)));
        when(store.find(any(),eq(Post.class))).thenReturn(List.of(post));
        assertFalse(access.canRead(repost,2L));
        assertFalse(access.forPosts(List.of(repost),2L).canRead(repost));
        relationship(CloseFriend.ACCEPTED,false);
        assertTrue(access.canRead(repost,2L));
        assertTrue(access.forPosts(List.of(repost),2L).canRead(repost));
    }

    @Test void batchReadsCloseFriendsOnceRegardlessOfCardCountAndNeverForPublicCards() {
        var close = mock(CloseFriendships.class);
        var policy = new ContentAccessService(store,users,follows,mock(ICommunityRepository.class),mock(ICommunityMemberRepository.class),close);
        var authors = LongStream.rangeClosed(10,129).mapToObj(CloseFriendPostVisibilityTest::author).toList();
        var posts = LongStream.rangeClosed(10,129).mapToObj(CloseFriendPostVisibilityTest::post).toList();
        var ids = authors.stream().map(User::getId).toList();
        when(users.findAllById(any())).thenReturn(authors);
        when(follows.findByFollowerIdAndFollowedIdIn(eq(2L),any())).thenReturn(ids.stream().map(id -> new Friend(2L,id)).toList());
        when(follows.findByFollowedIdAndFollowerIdIn(eq(2L),any())).thenReturn(ids.stream().map(id -> new Friend(id,2L)).toList());
        when(close.acceptedAmong(eq(2L),any())).thenReturn(Set.copyOf(ids));
        var batch = policy.forPosts(posts,2L);
        assertEquals(120,batch.readable(posts).size()); assertEquals(120,batch.readable(posts).size());
        verify(close,times(1)).acceptedAmong(eq(2L),any());
        clearInvocations(close); posts.forEach(p -> p.setVisibility(PostVisibility.PUBLIC));
        policy.forPosts(posts,2L); verifyNoInteractions(close);
    }

    static User author(long id) { var user=new User(); user.setId(id); user.setActive(true); return user; }
    static Post post(long author) { var post=new Post(); post.setId("post"+author); post.setUserId(""+author); post.setVisibility(PostVisibility.CLOSE_FRIENDS); return post; }
}
