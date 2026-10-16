package com.aloute.config;

import com.aloute.repository.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class SystemAdminInitializer implements CommandLineRunner {
    private final IUserRepository users;
    private final IRoleRepository roles;
    private final String username;

    public SystemAdminInitializer(
            IUserRepository users,
            IRoleRepository roles,
            @Value("${app.admin.username:}") String username) {
        this.users = users;
        this.roles = roles;
        this.username = username;
    }

    @Override
    public void run(String... args) {
        if (username.isBlank()) return;
        users.findByUsername(username)
                .filter(u -> u.isActive() && u.isEmailVerified())
                .ifPresent(
                        user -> {
                            user.setRole(roles.findByName("ROLE_ADMIN").orElseThrow());
                            users.save(user);
                        });
    }
}
