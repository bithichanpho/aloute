package com.aloute.service;

import com.aloute.common.exception.*;
import com.aloute.model.document.*;
import com.aloute.repository.IUserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AccountSecurityService {
    private final MongoTemplate mongo;
    private final IUserRepository users;
    private final PasswordEncoder passwords;
    private final TotpService totp;
    private final SecretEncryptionService encryption;
    private final AuthTokenService tokens;
    private final AuthRateLimitService limits;
    private final com.aloute.repository.IFriendRepository friends;
    private final com.aloute.repository.ICloseFriendRepository closeFriends;
    private final com.aloute.repository.ICommunityMemberRepository communityMembers;
    private final com.aloute.repository.IGroupMemberRepository groupMembers;
    private final com.aloute.repository.INotificationRepository notifications;

    public void checkPassword(Long user, String password) {
        limits.checkAndRecord(
                "ACCOUNT_SECURITY",
                user.toString(),
                10,
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                "Thử lại sau %d phút",
                "Quá nhiều yêu cầu");
        if (password == null
                || !passwords.matches(password, users.findById(user).orElseThrow().getPassword()))
            throw new UnauthorizedException("Mật khẩu không đúng");
    }

    public boolean enabled(Long user) {
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        return security != null && security.isEnabled();
    }

    public void verifyLogin(Long user, String code) {
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        if (security == null || !security.isEnabled()) return;
        if (code == null || code.isBlank()) throw new TwoFactorRequiredException();
        limits.checkAndRecord(
                "TOTP",
                user.toString(),
                10,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                "Thử lại sau %d phút",
                "Quá nhiều mã xác thực sai");
        long step = totp.verify(encryption.decrypt(security.getSecret()), code);
        if (step >= 0
                && mongo.updateFirst(
                                        Query.query(
                                                Criteria.where("_id")
                                                        .is(user)
                                                        .and("enabled")
                                                        .is(true)
                                                        .and("secret")
                                                        .is(security.getSecret())
                                                        .and("lastStep")
                                                        .lt(step)),
                                        new Update().set("lastStep", step),
                                        AccountSecurity.class)
                                .getModifiedCount()
                        == 1) return;
        if (code != null
                && mongo.updateFirst(
                                        Query.query(
                                                Criteria.where("_id")
                                                        .is(user)
                                                        .and("enabled")
                                                        .is(true)
                                                        .and("recoveryHashes")
                                                        .is(tokens.hashToken(code))),
                                        new Update().pull("recoveryHashes", tokens.hashToken(code)),
                                        AccountSecurity.class)
                                .getModifiedCount()
                        == 1) return;
        throw new UnauthorizedException("Cần mã 2FA hợp lệ hoặc mã khôi phục chưa sử dụng");
    }

    public Map<String, String> setup(Long user, String password) {
        checkPassword(user, password);
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        if (security == null) {
            security = new AccountSecurity();
            security.setId(user);
            try {
                mongo.insert(security);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw new BadRequestException("Thiết lập vừa thay đổi. Hãy thử lại.");
            }
        }
        if (security.isEnabled()) throw new BadRequestException("2FA đã được bật");
        String secret = totp.newSecret();
        long changed =
                mongo.updateFirst(
                                Query.query(
                                        Criteria.where("_id").is(user).and("enabled").is(false)),
                                new Update()
                                        .set("pendingSecret", encryption.encrypt(secret))
                                        .set("pendingExpiresAt", Instant.now().plusSeconds(600)),
                                AccountSecurity.class)
                        .getMatchedCount();
        if (changed == 0)
            throw new BadRequestException("2FA đã được bật hoặc thiết lập đã thay đổi");
        return Map.of(
                "secret",
                secret,
                "uri",
                "otpauth://totp/Aloute:"
                        + user
                        + "?secret="
                        + secret
                        + "&issuer=Aloute&digits=6&period=30");
    }

    public List<String> enable(Long user, String password, String code) {
        checkPassword(user, password);
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        if (security == null
                || security.isEnabled()
                || security.getPendingExpiresAt() == null
                || security.getPendingExpiresAt().isBefore(Instant.now()))
            throw new BadRequestException("Hãy tạo thiết lập 2FA mới");
        long step = totp.verify(encryption.decrypt(security.getPendingSecret()), code);
        if (step < 0) throw new BadRequestException("Mã xác thực không đúng");
        List<String> recovery = new ArrayList<>();
        for (int i = 0; i < 8; i++) recovery.add(tokens.generateToken());
        long changed =
                mongo.updateFirst(
                                Query.query(
                                        Criteria.where("_id")
                                                .is(user)
                                                .and("enabled")
                                                .is(false)
                                                .and("pendingSecret")
                                                .is(security.getPendingSecret())
                                                .and("pendingExpiresAt")
                                                .gt(Instant.now())),
                                new Update()
                                        .set("secret", security.getPendingSecret())
                                        .unset("pendingSecret")
                                        .unset("pendingExpiresAt")
                                        .set("enabled", true)
                                        .set("lastStep", step)
                                        .set(
                                                "recoveryHashes",
                                                recovery.stream().map(tokens::hashToken).toList()),
                                AccountSecurity.class)
                        .getModifiedCount();
        if (changed == 0) throw new BadRequestException("Thiết lập 2FA vừa thay đổi. Hãy thử lại.");
        return recovery;
    }

    public void disable(Long user, String password, String code) {
        checkPassword(user, password);
        verifyLogin(user, code);
        mongo.remove(Query.query(Criteria.where("_id").is(user)), AccountSecurity.class);
    }

    @org.springframework.transaction.annotation.Transactional
    public void close(Long user, String password, String code, boolean delete) {
        checkPassword(user, password);
        verifyLogin(user, code);
        var account = users.findById(user).orElseThrow();
        account.setActive(false);
        account.setSelfDeactivated(!delete);
        account.setTokensRevokedAt(Instant.now());
        if (delete) {
            account.setUsername("deleted_" + user);
            account.setEmail("deleted_" + user + "@invalid.local");
            account.setFullName("Tài khoản đã xóa");
            account.setBio(null);
            account.setAvatarUrl(null);
            account.setCoverUrl(null);
            account.setPassword(passwords.encode(UUID.randomUUID().toString()));
            account.setResetPasswordToken(null);
            account.setVerificationToken(null);
        }
        users.save(account);
        if (delete) {
            friends.deleteByFollowerIdOrFollowedId(user, user);
            closeFriends.deleteByRequesterIdOrReceiverId(user, user);
            communityMembers.deleteByUserId(user);
            groupMembers.deleteByUserId(user);
            notifications.deleteByReceiverIdOrSenderId(user, user);
            mongo.updateMulti(
                    Query.query(Criteria.where("userId").is(user.toString())),
                    new Update()
                            .set("content", "")
                            .unset("imageUrl")
                            .unset("videoUrl")
                            .unset("editHistory")
                            .unset("hashtags")
                            .unset("mentions")
                            .set("moderationStatus", ModerationStatus.HIDDEN),
                    Post.class);
            mongo.updateMulti(
                    Query.query(Criteria.where("userId").is(user.toString())),
                    new Update()
                            .set("content", "")
                            .set("moderationStatus", ModerationStatus.HIDDEN),
                    Comment.class);
            mongo.remove(Query.query(Criteria.where("editorId").is(user)), PostRevision.class);
            mongo.remove(Query.query(Criteria.where("userId").is(user)), Story.class);
            mongo.remove(Query.query(Criteria.where("userId").is(user)), SavedPost.class);
            mongo.remove(Query.query(Criteria.where("userId").is(user.toString())), Like.class);
            mongo.remove(Query.query(Criteria.where("userId").is(user.toString())), Repost.class);
            mongo.remove(
                    Query.query(
                            new Criteria().orOperator(
                                    Criteria.where("ownerId").is(user),
                                    Criteria.where("targetId").is(user))),
                    UserBlock.class);
            mongo.remove(
                    Query.query(
                            new Criteria().orOperator(
                                    Criteria.where("followerId").is(user),
                                    Criteria.where("targetId").is(user))),
                    FollowRequest.class);
            mongo.remove(Query.query(Criteria.where("reporterId").is(user)), ContentReport.class);
            mongo.updateMulti(
                    Query.query(Criteria.where("senderId").is(user.toString())),
                    new Update()
                            .set("content", "Tin nhắn từ tài khoản đã xóa")
                            .unset("mediaUrl")
                            .unset("mediaName")
                            .unset("mediaSize"),
                    ChatMessage.class);
            mongo.remove(Query.query(Criteria.where("_id").is(user)), AccountSecurity.class);
        }
    }
}
