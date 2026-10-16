package com.aloute.service;

import com.aloute.common.exception.EmailDeliveryException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

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

    public void sendVerificationEmail(String toEmail, String fullName, String verificationToken) {
        String verifyLink = frontendBaseUrl + "/verify-email?token=" + verificationToken;

        String safeFullName = escapeHtml(fullName);

        String html =
                """
<div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto;">
  <h2>Chào %s,</h2>
  <p>Cảm ơn bạn đã đăng ký tài khoản ALOUTE. Bấm nút bên dưới để xác thực email:</p>
  <p style="color:#666;font-size:14px;">Nếu bạn từng yêu cầu gửi lại email, chỉ liên kết trong email mới nhất còn hiệu lực.</p>
  <p style="margin: 24px 0;">
    <a href="%s" style="background:#101010;color:#fff;padding:12px 24px;
       border-radius:8px;text-decoration:none;font-weight:600;">
       Xác thực email
    </a>
  </p>
  <p>Nếu nút không hoạt động, dán link sau vào trình duyệt:<br>%s</p>
  <p style="color:#888;font-size:13px;">Nếu bạn không tạo tài khoản này, hãy bỏ qua email này.</p>
</div>
"""
                        .formatted(safeFullName, verifyLink, verifyLink);

        sendHtml(toEmail, "Xác thực tài khoản ALOUTE - liên kết mới nhất", html);
    }

    public void sendResetPasswordEmail(String toEmail, String fullName, String resetToken) {
        String resetLink = frontendBaseUrl + "/reset-password?token=" + resetToken;
        String safeFullName = escapeHtml(fullName);

        String html =
                """
<div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto;">
  <h2>Chào %s,</h2>
  <p>Bạn (hoặc ai đó) vừa yêu cầu đặt lại mật khẩu cho tài khoản ALOUTE này.
     Bấm nút bên dưới để đặt mật khẩu mới:</p>
  <p style="margin: 24px 0;">
    <a href="%s" style="background:#101010;color:#fff;padding:12px 24px;
       border-radius:8px;text-decoration:none;font-weight:600;">
       Đặt lại mật khẩu
    </a>
  </p>
  <p>Nếu nút không hoạt động, dán link sau vào trình duyệt:<br>%s</p>
  <p style="color:#888;font-size:13px;">Link có hiệu lực trong 30 phút.
     Nếu bạn không yêu cầu điều này, hãy bỏ qua email này — mật khẩu của bạn vẫn an toàn.</p>
</div>
"""
                        .formatted(safeFullName, resetLink, resetLink);

        sendHtml(toEmail, "Yêu cầu đặt lại mật khẩu ALOUTE", html);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
        } catch (MessagingException | MailException e) {
            org.slf4j.LoggerFactory.getLogger(EmailService.class)
                    .error(
                            "Gửi email ALOUTE thất bại tới {} ({})",
                            to,
                            e.getClass().getSimpleName(),
                            e);

            throw new EmailDeliveryException(
                    "Không thể gửi email lúc này. Vui lòng kiểm tra lại email và thử đăng ký lại"
                            + " sau.",
                    e);
        }
    }
}
