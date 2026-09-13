package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CreateGroupRequest {

    @NotBlank(message = "Tên nhóm không được để trống")
    @Size(max = 100, message = "Tên nhóm tối đa 100 ký tự")
    private String name;

    @NotEmpty(message = "Cần chọn ít nhất 1 thành viên khác để tạo nhóm")
    private List<Long> memberIds;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Long> getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(List<Long> memberIds) {
        this.memberIds = memberIds;
    }
}