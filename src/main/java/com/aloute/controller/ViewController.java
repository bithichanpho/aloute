package com.aloute.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ViewController — trả về tên template Thymeleaf tương ứng cho từng URL.
 * Hiện tại chỉ để XEM TRƯỚC giao diện, chưa truyền dữ liệu thật (model)
 * vì các Service/Repository liên quan chưa code xong. Các trang vẫn hiện
 * đúng vì đã có sẵn dữ liệu mẫu tĩnh trong file .html.
 */
@Controller
public class ViewController {

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String register() {
        return "auth/register";
    }

    @GetMapping("/feed")
    public String feed() {
        return "feed/index";
    }

    @GetMapping("/chat")
    public String chat() {
        return "chat/index";
    }

    @GetMapping("/profile/me")
    public String profileView() {
        return "profile/view";
    }

    @GetMapping("/profile/edit")
    public String profileEdit() {
        return "profile/edit";
    }
}