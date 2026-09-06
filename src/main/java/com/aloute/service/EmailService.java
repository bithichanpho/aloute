package com.aloute.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String frontendBaseUrl;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.mail.frontend-base-url}") String frontendBaseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * Gửi email chứa link xác thực tài khoản dạng:
     * {frontendBaseUrl}/verify-email?token=xxx
     */
    public void sendVerificationEmail(String toEmail, String fullName, String verificationToken) {
        String verifyLink = frontendBaseUrl + "/verify-email?token=" + verificationToken;

        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto;">
                  <h2>Chào %s,</h2>
                  <p>Cảm ơn bạn đã đăng ký tài khoản ALOUTE. Bấm nút bên dưới để xác thực email:</p>
                  <p style="margin: 24px 0;">
                    <a href="%s" style="background:#101010;color:#fff;padding:12px 24px;
                       border-radius:8px;text-decoration:none;font-weight:600;">
                       Xác thực email
                    </a>
                  </p>
                  <p>Nếu nút không hoạt động, dán link sau vào trình duyệt:<br>%s</p>
                  <p style="color:#888;font-size:13px;">Nếu bạn không tạo tài khoản này, hãy bỏ qua email này.</p>
                </div>
                """.formatted(fullName, verifyLink, verifyLink);

        sendHtml(toEmail, "Xác thực tài khoản ALOUTE của bạn", html);
    }

    private void sendHtml(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            // Không nên chặn luồng đăng ký chỉ vì gửi mail lỗi (vd: sai cấu
            // hình SMTP lúc dev) — ghi log để biết, nhưng không throw tiếp.
            System.err.println("Gửi email thất bại: " + e.getMessage());
        }
    }
}
