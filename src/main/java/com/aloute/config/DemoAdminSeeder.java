package com.aloute.config;

import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Explicitly opt-in local demo administrator. Never enabled by default, so production startup
 * cannot silently provision a known-password privileged account.
 */
@Component
@Order(3)
public class DemoAdminSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAdminSeeder.class);
    private static final String USERNAME = "demo.admin";
    private static final String EMAIL = "demo.admin@example.test";
    private static final String DEMO_PASSWORD = "123456";

    private final IUserRepository userRepository;
    private final IRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DemoAdminSeeder(
            IUserRepository userRepository,
            IRoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.demo-admin:false}") boolean enabled) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;

        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_ADMIN")));
        User admin = userRepository.findByUsername(USERNAME).orElse(null);
        boolean create = admin == null;
        if (create) {
            admin = new User();
            admin.setUsername(USERNAME);
            admin.setEmail(EMAIL);
            admin.setFullName("ALOUTE Demo Admin");
            admin.setAvatarUrl("/images/demo/demo-user-1.svg");
            admin.setBio("Tài khoản quản trị demo ALOUTE · dùng để kiểm thử báo cáo tài khoản, bài viết và moderation.");
        }
        // This predictable password is only for a local demo account and only when explicitly enabled.
        admin.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
        admin.setPrivateAccount(false);
        admin.setRole(adminRole);
        admin.setActive(true);
        admin.setEmailVerified(true);
        admin.setSelfDeactivated(false);
        admin.setTokensRevokedAt(null);
        userRepository.save(admin);
        log.warn("Local demo admin seeded as username={} with ROLE_ADMIN. Disable app.seed.demo-admin after first use; password is intentionally not logged.", USERNAME);
    }
}
