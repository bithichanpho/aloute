package com.aloute.config;

import com.aloute.model.entity.Role;
import com.aloute.repository.IRoleRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final IRoleRepository roleRepository;

    public DataSeeder(IRoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(String... args) {
        roleRepository
                .findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_USER")));

        roleRepository
                .findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_ADMIN")));

        roleRepository
                .findByName("ROLE_HOST")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_HOST")));
    }
}
