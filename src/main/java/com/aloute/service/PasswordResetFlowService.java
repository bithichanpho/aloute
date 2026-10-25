package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Short-lived encrypted browser proof, bound to the current single-use reset state in PostgreSQL. */
@Service
public class PasswordResetFlowService {
    public static final long LIFETIME_SECONDS=300;
    public static final String INVALID_SESSION="Phiên đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng xác thực lại mã OTP.";
    private final PasswordManagementService passwords;
    private final SecretEncryptionService encryption;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordResetFlowService(PasswordManagementService passwords,SecretEncryptionService encryption) {
        this(passwords,encryption,Clock.systemUTC());
    }
    PasswordResetFlowService(PasswordManagementService passwords,SecretEncryptionService encryption,Clock clock) {
        this.passwords=passwords;this.encryption=encryption;this.clock=clock;
    }
    public String verify(String email,String code) {
        var proof=passwords.verifyResetCode(email,code);
        // The low-entropy OTP hash is encrypted, never exposed in a readable JWT or URL.
        return encryption.encrypt("password-reset-v1|"+proof.userId()+"|"+proof.tokenHash()+"|"
                +Instant.now(clock).plusSeconds(LIFETIME_SECONDS).getEpochSecond()+"|"+proof.resetExpiry());
    }
    public Optional<String> verifiedEmail(String token) {
        return decode(token).flatMap(passwords::verifiedResetEmail);
    }
    public void complete(String token,String newPassword) {
        var proof=decode(token).orElseThrow(()->new BadRequestException(INVALID_SESSION));
        if(passwords.verifiedResetEmail(proof).isEmpty())throw new BadRequestException(INVALID_SESSION);
        passwords.resetPasswordVerified(proof,newPassword);
    }
    private Optional<PasswordManagementService.ResetProof> decode(String token) {
        if(token==null||token.isBlank()||token.length()>1024)return Optional.empty();
        try {
            String[] fields=encryption.decrypt(token).split("\\|",-1);
            if(fields.length!=5||!fields[0].equals("password-reset-v1")||!fields[2].matches("[a-f0-9]{64}"))return Optional.empty();
            long id=Long.parseLong(fields[1]);
            if(id<=0||!Instant.ofEpochSecond(Long.parseLong(fields[3])).isAfter(Instant.now(clock)))return Optional.empty();
            return Optional.of(new PasswordManagementService.ResetProof(id,fields[2],java.time.LocalDateTime.parse(fields[4])));
        } catch(IllegalArgumentException|IllegalStateException|java.time.DateTimeException ex) {return Optional.empty();}
    }
}
