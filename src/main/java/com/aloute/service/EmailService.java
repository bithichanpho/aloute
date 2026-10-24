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

    /** Validity of the emailed OTPs; keep in sync with AuthService and PasswordManagementService. */
    private static final int OTP_VALID_MINUTES = 10;

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

    public void sendVerificationEmail(String toEmail, String fullName, String verificationOtp) {
        String safeFullName = escapeHtml(fullName);

        String html =
                """
<div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto;">
  <h2>Chào %s,</h2>
  <p><strong>Bạn vừa nhận được yêu cầu đăng ký tài khoản ALOUTE bằng địa chỉ email này.</strong></p>
  <p>Nhập mã OTP dưới đây vào trang xác thực để kích hoạt tài khoản:</p>
  <p style="margin: 24px 0; text-align: center;">
    <span style="display:inline-block;background:#101010;color:#fff;padding:14px 28px;
       border-radius:8px;font-size:30px;font-weight:700;letter-spacing:8px;">%s</span>
  </p>
  <p style="color:#666;font-size:14px;">Mã có hiệu lực trong %d phút. Nếu bạn yêu cầu gửi lại mã, chỉ mã trong email mới nhất còn hiệu lực.</p>
  <p style="color:#9a3412;background:#fff7ed;border:1px solid #fed7aa;padding:10px;border-radius:6px;font-size:14px;"><strong>Nếu bạn không tự đăng ký:</strong> hãy bỏ qua email này và không chia sẻ mã cho bất kỳ ai. Tài khoản sẽ không được kích hoạt nếu không có mã.</p>
</div>
"""
                        .formatted(safeFullName, verificationOtp, OTP_VALID_MINUTES);

        sendHtml(toEmail, "ALOUTE - Mã OTP xác thực email", html);
    }

    public void sendResetPasswordEmail(String toEmail, String fullName, String resetOtp) {
        String safeFullName = escapeHtml(fullName);

        String html =
                """
<div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto;">
  <h2>Chào %s,</h2>
  <p>Bạn (hoặc ai đó) vừa yêu cầu đặt lại mật khẩu cho tài khoản ALOUTE này.
     Nhập mã OTP dưới đây vào trang đặt lại mật khẩu:</p>
  <p style="margin: 24px 0; text-align: center;">
    <span style="display:inline-block;background:#101010;color:#fff;padding:14px 28px;
       border-radius:8px;font-size:30px;font-weight:700;letter-spacing:8px;">%s</span>
  </p>
  <p style="color:#888;font-size:13px;">Mã có hiệu lực trong %d phút và chỉ dùng được một lần.
     Không chia sẻ mã này cho bất kỳ ai. Nếu bạn không yêu cầu điều này, hãy bỏ qua email này — mật khẩu của bạn vẫn an toàn.</p>
</div>
"""
                        .formatted(safeFullName, resetOtp, OTP_VALID_MINUTES);

        sendHtml(toEmail, "ALOUTE - Mã OTP đặt lại mật khẩu", html);
    }

    /**
     * Sent when someone tries to register an email that already has an account. The registration
     * response is identical either way, so this mail is how the owner finds out.
     */
    public void sendAccountAlreadyExistsEmail(String toEmail, String fullName) {
        String loginLink = frontendBaseUrl + "/login";
        String forgotLink = frontendBaseUrl + "/forgot-password";
        String safeFullName = escapeHtml(fullName);

        String html =
                """
<div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto;">
  <h2>Chào %s,</h2>
  <p>Có người vừa dùng địa chỉ email này để đăng ký tài khoản ALOUTE, nhưng email này <strong>đã có tài khoản</strong> nên không có tài khoản mới nào được tạo.</p>
  <p>Nếu là bạn (ví dụ bạn quên mình đã đăng ký), hãy đăng nhập hoặc đặt lại mật khẩu:</p>
  <p style="margin: 24px 0;">
    <a href="%s" style="background:#101010;color:#fff;padding:12px 24px;
       border-radius:8px;text-decoration:none;font-weight:600;">
       Đăng nhập
    </a>
    &nbsp; <a href="%s">Quên mật khẩu?</a>
  </p>
  <p style="color:#888;font-size:13px;">Nếu không phải bạn, hãy bỏ qua email này. Tài khoản và mật khẩu của bạn không bị thay đổi.</p>
</div>
"""
                        .formatted(safeFullName, loginLink, forgotLink);

        sendHtml(toEmail, "Email này đã có tài khoản ALOUTE", html);
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
