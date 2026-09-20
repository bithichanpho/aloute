package com.aloute.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.aloute.model.entity.Role;
import com.aloute.repository.IRoleRepository;

@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final IRoleRepository roleRepository;

    public DataSeeder(IRoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(String... args) {
        roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_USER")));

        roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_ADMIN")));
    }
}
