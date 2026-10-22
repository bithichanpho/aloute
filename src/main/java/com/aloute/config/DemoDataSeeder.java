package com.aloute.config;

import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.Comment;
import com.aloute.model.document.Like;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.model.entity.Friend;
import com.aloute.model.entity.GroupMember;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatGroupRepository;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(2)
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String PASSWORD = "123456";
    private static final String DEMO_PREFIX = "demo.";

    private final IUserRepository userRepository;
    private final IRoleRepository roleRepository;
    private final IFriendRepository friendRepository;
    private final ICommunityRepository communityRepository;
    private final ICommunityMemberRepository communityMemberRepository;
    private final ICommentRepository commentRepository;
    private final IChatGroupRepository chatGroupRepository;
    private final IGroupMemberRepository groupMemberRepository;
    private final IGroupReadStatusRepository groupReadStatusRepository;
    private final IChatMessageRepository chatMessageRepository;
    private final IPostRepository postRepository;
    private final ILikeRepository likeRepository;
    private final INotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DemoDataSeeder(
            IUserRepository userRepository,
            IRoleRepository roleRepository,
            IFriendRepository friendRepository,
            ICommunityRepository communityRepository,
            ICommunityMemberRepository communityMemberRepository,
            ICommentRepository commentRepository,
            IChatGroupRepository chatGroupRepository,
            IGroupMemberRepository groupMemberRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            IChatMessageRepository chatMessageRepository,
            IPostRepository postRepository,
            ILikeRepository likeRepository,
            INotificationRepository notificationRepository,
            PasswordEncoder passwordEncoder,
            SeedProperties seedProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.friendRepository = friendRepository;
        this.communityRepository = communityRepository;
        this.communityMemberRepository = communityMemberRepository;
        this.commentRepository = commentRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.notificationRepository = notificationRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = seedProperties.isDemoData();
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        if (userRepository.existsByUsername("demo.minh")) {
            log.info("Demo seed already exists; skipping demo data initialization.");
            return;
        }

        Role userRole =
                roleRepository
                        .findByName("ROLE_USER")
                        .orElseGet(() -> roleRepository.save(new Role("ROLE_USER")));

        List<User> users = seedUsers(userRole);
        seedFollows(users);
        List<Community> communities = seedCommunities(users);
        seedCommunityMembers(users, communities);
        List<ChatGroup> demoGroups = seedGroups(users);
        List<Post> posts = seedPosts(users, communities);
        seedComments(users, posts);
        seedLikes(users, posts);
        seedNotifications(users, posts);
        seedChats(users, demoGroups);

        log.info(
                "Demo seed complete: {} users, {} communities, {} posts. "
                        + "Demo credentials are development-only and are not logged.",
                users.size(), communities.size(), posts.size());
    }

    private List<User> seedUsers(Role role) {
        String[][] data = {
            {"minh", "Nguyễn Minh", "minh"},
            {"thao", "Trần Thảo", "thao"},
            {"quang", "Lê Quang", "quang"},
            {"linh", "Phạm Linh", "linh"},
            {"nam", "Đỗ Nam", "nam"},
            {"vy", "Nguyễn Vy", "vy"},
            {"khoa", "Bùi Khoa", "khoa"},
            {"anh", "Hoàng Anh", "anh"},
            {"my", "Trương Mỹ", "my"},
            {"phuc", "Võ Phúc", "phuc"},
            {"han", "Mai Hân", "han"},
            {"tuan", "Phan Tuấn", "tuan"},
            {"ngoc", "Lý Ngọc", "ngoc"},
            {"duy", "Ngô Duy", "duy"},
            {"trang", "Đinh Trang", "trang"},
            {"bao", "Huỳnh Bảo", "bao"},
            {"khang", "Trần Khang", "khang"},
            {"yen", "Nguyễn Yến", "yen"},
            {"son", "Đặng Sơn", "son"},
            {"mai", "Nguyễn Mai", "mai"},
            {"huy", "Phạm Huy", "huy"},
            {"chi", "Lê Chi", "chi"},
            {"long", "Vũ Long", "long"},
            {"thu", "Đỗ Thu", "thu"},
            {"kiet", "Cao Kiệt", "kiet"}
        };

        List<User> users = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            String key = data[i][0];
            User u = new User();
            u.setUsername(DEMO_PREFIX + key);
            u.setEmail("demo." + key + "@example.test");
            u.setPassword(passwordEncoder.encode(PASSWORD));
            u.setFullName(data[i][1]);
            u.setAvatarUrl("/images/demo/demo-user-" + (i + 1) + ".svg");
            u.setCoverUrl("/images/demo/demo-cover-" + ((i % 6) + 1) + ".svg");
            u.setBio(
                    "Tài khoản demo ALOUTE · "
                            + data[i][1]
                            + " · dùng để kiểm thử realtime, feed và cộng đồng.");
            u.setPrivateAccount(i != 0 && i % 7 == 0);
            u.setRole(role);
            u.setActive(true);

            u.setEmailVerified(true);
            users.add(u);
        }
        return userRepository.saveAll(users);
    }

    private void seedFollows(List<User> users) {
        List<Friend> follows = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < users.size(); i++) {

            for (int step = 1; step <= 5; step++) {
                int j = (i + step) % users.size();
                addFollow(follows, seen, users.get(i).getId(), users.get(j).getId());
            }
        }

        for (int i = 0; i < users.size(); i += 2) {
            int j = (i + 3) % users.size();
            addFollow(follows, seen, users.get(j).getId(), users.get(i).getId());
        }

        friendRepository.saveAll(follows);
    }

    private static void addFollow(List<Friend> follows, Set<String> seen, Long from, Long to) {
        if (from.equals(to)) return;
        String key = from + ":" + to;
        if (seen.add(key)) {
            follows.add(new Friend(from, to));
        }
    }

    private List<Community> seedCommunities(List<User> users) {
        String[] names = {
            "ALOUTE UTE · Công nghệ",
            "ALOUTE UTE · Lập trình Java",
            "ALOUTE UTE · UI/UX Design",
            "ALOUTE UTE · Nhiếp ảnh",
            "ALOUTE UTE · Phim & Âm nhạc",
            "ALOUTE UTE · Khoa Cơ khí",
            "ALOUTE UTE · Kinh nghiệm thực tập",
            "ALOUTE UTE · Đồ án tốt nghiệp",
            "ALOUTE UTE · Thể thao",
            "ALOUTE UTE · Chuyện sinh viên"
        };
        String[] descriptions = {
            "Chia sẻ công nghệ, tài liệu và kinh nghiệm học tập tại UTE.",
            "Java, Spring Boot, JPA, SQL, PostgreSQL và các bài tập web.",
            "Thiết kế giao diện, UX research, Figma và front-end.",
            "Ảnh campus, street photo, mobile photography và hậu kỳ.",
            "Review phim, playlist và những cuộc trò chuyện sau giờ học.",
            "Trao đổi môn học, đồ án và hoạt động của sinh viên cơ khí.",
            "CV, phỏng vấn, internship và kinh nghiệm làm việc thực tế.",
            "Kho ý tưởng, tài liệu và đồng đội cho đồ án cuối khóa.",
            "Bóng đá, cầu lông, chạy bộ và các hoạt động thể thao.",
            "Hỏi đáp, tâm sự và mọi chuyện quanh đời sống sinh viên."
        };

        List<Community> communities = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Community c = new Community();
            c.setName(names[i]);
            c.setDescription(descriptions[i]);
            c.setAvatarUrl("/images/demo/demo-community-" + (i + 1) + ".svg");
            c.setCoverUrl("/images/demo/demo-community-cover-" + ((i % 5) + 1) + ".svg");
            c.setPrivacy(i % 4 == 3 ? "PRIVATE" : "PUBLIC");
            c.setCreatedBy(users.get(i % users.size()).getId());
            c.setMemberCount(0);
            communities.add(c);
        }
        return communityRepository.saveAll(communities);
    }

    private void seedCommunityMembers(List<User> users, List<Community> communities) {
        List<CommunityMember> memberships = new ArrayList<>();
        Map<Long, Integer> counts = new HashMap<>();

        for (int c = 0; c < communities.size(); c++) {
            Community community = communities.get(c);
            int size = 8 + (c % 8);
            Set<Long> picked = new HashSet<>();
            Long adminId = users.get(c % users.size()).getId();
            picked.add(adminId);
            memberships.add(new CommunityMember(community.getId(), adminId, "ADMIN"));

            for (int offset = 1; picked.size() < size; offset++) {
                Long uid = users.get((c * 2 + offset) % users.size()).getId();
                if (picked.add(uid)) {
                    memberships.add(new CommunityMember(community.getId(), uid, "MEMBER"));
                }
            }
            counts.put(community.getId(), picked.size());
        }

        communityMemberRepository.saveAll(memberships);
        for (Community community : communities) {
            community.setMemberCount(counts.getOrDefault(community.getId(), 0));
        }
        communityRepository.saveAll(communities);
    }

    private List<ChatGroup> seedGroups(List<User> users) {
        String[] names = {"ALOUTE · Team Web", "ALOUTE · Team Đồ án", "ALOUTE · Bạn thân"};
        List<ChatGroup> groups = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            ChatGroup g = new ChatGroup();
            g.setName(names[i]);
            g.setCreatedBy(users.get(0).getId());
            g.setAvatarUrl("/images/demo/demo-group-" + (i + 1) + ".svg");
            groups.add(g);
        }
        groups = chatGroupRepository.saveAll(groups);

        List<GroupMember> members = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            ChatGroup g = groups.get(i);
            int count = 5 + i * 2;
            members.add(new GroupMember(g.getId(), users.get(0).getId(), "ADMIN"));
            for (int j = 1; j < count; j++) {
                members.add(
                        new GroupMember(
                                g.getId(),
                                users.get((i * 3 + j) % users.size()).getId(),
                                "MEMBER"));
            }
        }
        groupMemberRepository.saveAll(members);
        return groups;
    }

    private List<Post> seedPosts(List<User> users, List<Community> communities) {
        String[][] phrases = {
            {
                "Hôm nay test realtime ALOUTE: gửi tin nhắn ở tab này thì tab khác phải nhận ngay.",
                "#ALOUTE #realtime #WebSocket"
            },
            {
                "Một góc campus hôm nay khá đẹp. Có ai đang ở thư viện không?",
                "#UTE #campus #studentlife"
            },
            {
                "Mình vừa hoàn thành một màn hình Spring Boot + Thymeleaf, cảm giác thật đã.",
                "#SpringBoot #Thymeleaf #Java"
            },
            {"Có ai đang làm đồ án và cần đồng đội frontend không?", "#DoAn #Frontend #Teamwork"},
            {
                "UI càng đơn giản càng khó làm. Đang chỉnh khoảng trắng cho feed.",
                "#UIDesign #UX #ALOUTE"
            },
            {"Chia sẻ một playlist nhỏ để code buổi tối.", "#Music #Coding"},
            {
                "Test tìm kiếm ignore case và LIKE %keyword% với nhiều dữ liệu.",
                "#Search #Database #SQL"
            },
            {"Cuối tuần này chạy bộ không mọi người?", "#Sports #UTE"},
            {
                "Một bug nhỏ nhưng debug mất cả buổi. Cuối cùng chỉ là một query N+1.",
                "#Spring #Performance"
            },
            {
                "Hôm nay mình thử một layout sidebar thu gọn giống Instagram, nhìn gọn hơn hẳn.",
                "#Frontend #UI #InstagramStyle"
            }
        };

        List<Post> posts = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 50; i++) {
            int author = i % users.size();
            String[] p = phrases[i % phrases.length];
            Post post = new Post();
            post.setUserId(String.valueOf(users.get(author).getId()));
            post.setContent(
                    p[0]
                            + "\n"
                            + p[1]
                            + "\n\nBài demo số "
                            + (i + 1)
                            + " để kiểm tra cuộn, like và comment.");
            post.setImageUrl(
                    i % 4 == 0 ? "/images/demo/demo-post-" + ((i % 6) + 1) + ".svg" : null);
            post.setLikeCount(0);
            post.setCommentCount(0);
            post.setRepostCount(0);
            post.setReactionCounts(new HashMap<>());
            post.setModerationStatus(ModerationStatus.APPROVED);
            post.setToxicityScore(0.0);
            post.setCreatedAt(now.minusMinutes(i * 17L + 5));
            posts.add(post);
        }

        for (int i = 0; i < 30; i++) {
            int author = (i * 3 + 2) % users.size();
            Community community = communities.get(i % communities.size());
            String[] p = phrases[(i + 3) % phrases.length];
            Post post = new Post();
            post.setUserId(String.valueOf(users.get(author).getId()));
            post.setCommunityId(String.valueOf(community.getId()));
            post.setContent("[" + community.getName() + "] " + p[0] + "\n" + p[1]);
            post.setImageUrl(
                    i % 5 == 0 ? "/images/demo/demo-post-" + ((i % 6) + 1) + ".svg" : null);
            post.setLikeCount(0);
            post.setCommentCount(0);
            post.setRepostCount(0);
            post.setReactionCounts(new HashMap<>());
            post.setModerationStatus(ModerationStatus.APPROVED);
            post.setToxicityScore(0.0);
            post.setCreatedAt(now.minusHours(i + 1L));
            posts.add(post);
        }

        return postRepository.saveAll(posts);
    }

    private void seedComments(List<User> users, List<Post> posts) {
        List<Comment> comments = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < Math.min(24, posts.size()); i++) {
            Post post = posts.get(i);
            int topLevel = 1 + (i % 3);
            List<Comment> parentsForPost = new ArrayList<>();
            for (int j = 0; j < topLevel; j++) {
                Comment comment = new Comment();
                comment.setPostId(post.getId());
                comment.setUserId(String.valueOf(users.get((i + j + 2) % users.size()).getId()));
                comment.setContent(
                        "Bình luận demo " + (j + 1) + " trên bài viết này · test reply realtime.");
                comment.setModerationStatus(ModerationStatus.APPROVED);
                comment.setCreatedAt(now.minusMinutes(i * 11L + j + 1));
                parentsForPost.add(comment);
                comments.add(comment);
            }

            if (!parentsForPost.isEmpty() && i % 2 == 0) {
                Comment reply = new Comment();
                reply.setPostId(post.getId());
                reply.setUserId(String.valueOf(users.get((i + 7) % users.size()).getId()));
                reply.setContent("Reply demo · mình cũng đang test tính năng này.");
                reply.setParentCommentId(null);
                reply.setModerationStatus(ModerationStatus.APPROVED);
                reply.setCreatedAt(now.minusMinutes(i * 11L));

                comments.add(reply);
            }
        }

        List<Comment> saved = commentRepository.saveAll(comments);
        for (int i = 0; i < Math.min(24, posts.size()); i++) {
            Post post = posts.get(i);

            List<Comment> forPost =
                    saved.stream().filter(c -> post.getId().equals(c.getPostId())).toList();
            if (i % 2 == 0 && !forPost.isEmpty()) {
                Comment reply = forPost.get(forPost.size() - 1);
                Comment parent = forPost.get(0);
                reply.setParentCommentId(parent.getId());
                parent.setReplyCount(1);
                commentRepository.save(reply);
                commentRepository.save(parent);
            }
            post.setCommentCount(forPost.size());
        }
        postRepository.saveAll(posts);
    }

    private void seedLikes(List<User> users, List<Post> posts) {
        List<Like> likes = new ArrayList<>();
        Map<String, Post> postById = new HashMap<>();
        for (Post post : posts) postById.put(post.getId(), post);

        for (int i = 0; i < Math.min(28, posts.size()); i++) {
            Post post = posts.get(i);
            int amount = 2 + (i % 4);
            for (int j = 0; j < amount; j++) {
                User user = users.get((i + j + 1) % users.size());
                String type =
                        switch (j % 5) {
                            case 1 -> "LOVE";
                            case 2 -> "HAHA";
                            case 3 -> "WOW";
                            case 4 -> "SAD";
                            default -> "LIKE";
                        };
                likes.add(new Like(post.getId(), String.valueOf(user.getId()), type));
                post.getReactionCounts().merge(type, 1, Integer::sum);
            }
            post.setLikeCount(
                    post.getReactionCounts().values().stream().mapToInt(Integer::intValue).sum());
        }
        likeRepository.saveAll(likes);
        postRepository.saveAll(posts);
    }

    private void seedNotifications(List<User> users, List<Post> posts) {
        if (posts.isEmpty()) return;
        Long receiver = users.get(0).getId();
        List<Notification> notifications = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Notification n = new Notification();
            n.setReceiverId(receiver);
            n.setSenderId(users.get((i % (users.size() - 1)) + 1).getId());
            n.setType(i % 3 == 0 ? "FOLLOW" : (i % 3 == 1 ? "LIKE" : "COMMENT"));
            n.setReferenceId(posts.get(i % posts.size()).getId());
            n.setContent(
                    n.getType().equals("FOLLOW")
                            ? "đã theo dõi bạn."
                            : (n.getType().equals("LIKE")
                                    ? "đã thích bài viết của bạn."
                                    : "đã bình luận về bài viết của bạn."));
            n.setRead(i >= 7);
            notifications.add(n);
        }
        notificationRepository.saveAll(notifications);
    }

    private void seedChats(List<User> users, List<ChatGroup> groups) {
        Long me = users.get(0).getId();
        List<ChatMessage> messages = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 1; i <= 8; i++) {
            User other = users.get(i);
            String conversationId =
                    ChatMessage.buildConversationId(
                            String.valueOf(me), String.valueOf(other.getId()));
            int count = 5 + (i % 3);
            for (int m = 0; m < count; m++) {
                boolean fromOther = m % 2 == 0;
                Long sender = fromOther ? other.getId() : me;
                Long receiver = fromOther ? me : other.getId();
                ChatMessage message = new ChatMessage();
                message.setConversationId(conversationId);
                message.setSenderId(String.valueOf(sender));
                message.setReceiverId(String.valueOf(receiver));
                message.setContent(
                        fromOther
                                ? "Tin demo từ " + other.getFullName() + " · message " + (m + 1)
                                : "Phản hồi demo của Minh · message " + (m + 1));
                message.setMessageType("TEXT");
                message.setRead(!(fromOther && (m >= count - (i % 3 == 0 ? 3 : 1))));
                message.setSentAt(now.minusMinutes((long) (i * 13 + (count - m) * 4)));
                messages.add(message);
            }

            if (i == 3) {
                ChatMessage call = new ChatMessage();
                call.setConversationId(conversationId);
                call.setSenderId(String.valueOf(other.getId()));
                call.setReceiverId(String.valueOf(me));
                call.setContent("");
                call.setMessageType("CALL");
                call.setCallId("demo-call-" + i);
                call.setCallType("video");
                call.setCallStatus("MISSED");
                call.setRead(false);
                call.setSentAt(now.minusMinutes(3));
                messages.add(call);
            }
        }

        for (int g = 0; g < groups.size(); g++) {
            ChatGroup group = groups.get(g);
            List<Long> groupMemberIds =
                    groupMemberRepository.findByGroupId(group.getId()).stream()
                            .map(GroupMember::getUserId)
                            .toList();
            Long senderId =
                    groupMemberIds.stream().filter(id -> !id.equals(me)).findFirst().orElse(me);
            for (int m = 0; m < 7; m++) {
                ChatMessage message = new ChatMessage();
                message.setConversationId(ChatMessage.buildGroupConversationId(group.getId()));
                message.setGroupId(group.getId());
                message.setSenderId(String.valueOf(senderId));
                message.setContent("[" + group.getName() + "] Tin nhóm demo số " + (m + 1));
                message.setMessageType("TEXT");
                message.setRead(false);
                message.setSentAt(now.minusMinutes(50L - m * 5L));
                messages.add(message);
            }

            GroupReadStatus status = new GroupReadStatus(group.getId(), me);
            status.setLastReadAt(now.minusMinutes(g == 0 ? 42 : 46));
            groupReadStatusRepository.save(status);
        }

        chatMessageRepository.saveAll(messages);
    }
}
