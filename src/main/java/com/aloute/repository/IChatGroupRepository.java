package com.aloute.repository;

import com.aloute.model.entity.ChatGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IChatGroupRepository extends JpaRepository<ChatGroup, Long> {

    List<ChatGroup> findByIdIn(List<Long> ids);
}