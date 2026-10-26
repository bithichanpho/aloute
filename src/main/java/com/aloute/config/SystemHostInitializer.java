package com.aloute.config;

import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(11)
public class SystemHostInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SystemHostInitializer.class);
    private final IUserRepository users;
    private final IRoleRepository roles;
    private final String username;
    private final boolean bootstrapEnabled;

    public SystemHostInitializer(
            IUserRepository users,
            IRoleRepository roles,
            @Value("${app.host.username:}") String username,
            @Value("${app.host.bootstrap-enabled:false}") boolean bootstrapEnabled) {
        this.users = users;
        this.roles = roles;
        this.username = username == null ? "" : username.trim();
        this.bootstrapEnabled = bootstrapEnabled;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (username.isBlank()) return;
        var user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            log.warn("Configured HOST account does not exist: {}", username);
            return;
        }
        if (!user.isActive() || !user.isEmailVerified()) {
            log.warn("Refusing HOST bootstrap for an inactive or unverified account: {}", username);
            return;
        }
        if (user.getRole() != null && "ROLE_HOST".equals(user.getRole().getName())) return;
        if (!bootstrapEnabled) {
            log.warn("HOST bootstrap is disabled; set APP_HOST_BOOTSTRAP_ENABLED=true once to promote {}", username);
            return;
        }
        var hostRole = roles.findByName("ROLE_HOST")
                .orElseThrow(() -> new IllegalStateException("ROLE_HOST was not seeded"));
        user.setRole(hostRole);
        users.save(user);
        log.info("Promoted configured account to ROLE_HOST: {}. Disable bootstrap after startup.", username);
    }
}
