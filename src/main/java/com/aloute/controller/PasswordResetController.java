package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import com.aloute.security.ClientIpResolver;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.PasswordResetFlowService;
import com.aloute.validation.StrongPassword;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@Controller
public class PasswordResetController {
    public static final String COOKIE="ALOUTE_PASSWORD_RESET";
    private final PasswordResetFlowService flow;
    private final AuthRateLimitService limits;
    private final ClientIpResolver clientIp;
    @Value("${app.security.cookie-secure:false}") private boolean cookieSecure;
    public PasswordResetController(PasswordResetFlowService flow,AuthRateLimitService limits,ClientIpResolver clientIp) {
        this.flow=flow;this.limits=limits;this.clientIp=clientIp;
    }
    public record VerifyCode(@NotBlank @Email @Size(max=254) String email,
            @NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record Complete(@NotBlank @StrongPassword String newPassword) {}

    @GetMapping("/verify-reset-code")
    public String verifyPage(@RequestParam(required=false) String email,Model model) {
        model.addAttribute("email",email);return "auth/verify-reset-code";
    }
    @GetMapping("/reset-password")
    public String passwordPage(@CookieValue(name=COOKIE,required=false) String token,Model model) {
        var email=flow.verifiedEmail(token);
        if(email.isEmpty())return "redirect:/verify-reset-code";
        model.addAttribute("email",email.get());return "auth/reset-password";
    }
    @PostMapping("/api/auth/verify-reset-code")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> verify(@Valid @RequestBody VerifyCode request,HttpServletRequest http) {
        String email=request.email().trim().toLowerCase(java.util.Locale.ROOT);
        // Same account/IP attempt budgets as the compatibility endpoint; switching routes cannot add attempts.
        limits.checkAndRecord("RESET_OTP_TARGET",email,5,Duration.ofMinutes(10),Duration.ofMinutes(15),
                "Bạn đã nhập mã sai quá nhiều lần. Thử lại sau khoảng %d phút.","Chức năng đặt lại mật khẩu tạm khóa 15 phút.");
        limits.checkAndRecord("RESET_OTP_IP",clientIp.resolve(http),30,Duration.ofMinutes(15),Duration.ofMinutes(30),
                "IP này đã nhập mã sai quá nhiều lần. Thử lại sau khoảng %d phút.","IP này tạm khóa chức năng đặt lại mật khẩu 30 phút.");
        String token=flow.verify(email,request.code());
        limits.reset("RESET_OTP_TARGET",email);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.SET_COOKIE,cookie(token,PasswordResetFlowService.LIFETIME_SECONDS).toString())
                .body(ApiResponse.success("Xác thực mã thành công",null));
    }
    @PostMapping("/api/auth/complete-password-reset")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> complete(@CookieValue(name=COOKIE,required=false) String token,
            @Valid @RequestBody Complete request,HttpServletRequest http) {
        limits.checkAndRecord("RESET_COMPLETE_IP",clientIp.resolve(http),30,Duration.ofMinutes(15),Duration.ofMinutes(30),
                "Bạn đã thử quá nhiều lần. Thử lại sau khoảng %d phút.","Chức năng đặt lại mật khẩu tạm khóa 30 phút.");
        flow.complete(token,request.newPassword());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.SET_COOKIE,cookie("",0).toString())
                .body(ApiResponse.success("Đặt lại mật khẩu thành công",null));
    }
    private ResponseCookie cookie(String token,long lifetime) {
        return ResponseCookie.from(COOKIE,token).httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/")
                .maxAge(lifetime).build();
    }
}
