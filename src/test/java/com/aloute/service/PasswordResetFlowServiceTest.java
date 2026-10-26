package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aloute.common.exception.BadRequestException;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PasswordResetFlowServiceTest {
    final PasswordManagementService passwords=mock(PasswordManagementService.class);
    final SecretEncryptionService encryption=new SecretEncryptionService("reset-flow-test-encryption-key-only-32-bytes","");
    final Clock now=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneOffset.UTC);
    final PasswordResetFlowService flow=new PasswordResetFlowService(passwords,encryption,now);
    final PasswordManagementService.ResetProof proof=new PasswordManagementService.ResetProof(7L,"a".repeat(64),LocalDateTime.of(2026,10,25,0,8));
    String token(){when(passwords.verifyResetCode("a@example.invalid","012345")).thenReturn(proof);return flow.verify("a@example.invalid","012345");}

    @Test void encryptedPurposeBoundProofOnlyCompletesAgainstActiveDatabaseState() {
        String token=token();assertFalse(token.contains(proof.tokenHash()));assertFalse(token.contains("012345"));
        when(passwords.verifiedResetEmail(proof)).thenReturn(Optional.of("a@example.invalid"));
        assertEquals("a@example.invalid",flow.verifiedEmail(token).orElseThrow());
        verify(passwords,never()).resetPasswordVerified(any(),any());
        flow.complete(token,"New-Pass1!");verify(passwords).resetPasswordVerified(proof,"New-Pass1!");
    }
    @Test void missingTamperedAndOtherPurposeTokensAreRejected() {
        String token=token();
        for(String bad:new String[]{"",token.substring(0,token.length()-8)+"AAAAAAAA",encryption.encrypt("totp|7|"+proof.tokenHash()+"|9999999999|"+proof.resetExpiry())}){
            assertTrue(flow.verifiedEmail(bad).isEmpty());assertThrows(BadRequestException.class,()->flow.complete(bad,"New-Pass1!"));
        }
        assertTrue(flow.verifiedEmail(null).isEmpty());verify(passwords,never()).resetPasswordVerified(any(),any());
    }
    @Test void expiredProofCannotOpenOrCompleteReset() {
        String token=token();var later=new PasswordResetFlowService(passwords,encryption,Clock.offset(now,Duration.ofSeconds(300)));
        assertTrue(later.verifiedEmail(token).isEmpty());assertThrows(BadRequestException.class,()->later.complete(token,"New-Pass1!"));
        verify(passwords,never()).verifiedResetEmail(any());
    }
    @Test void spentReplacedOrExpiredDatabaseResetRevokesTheBrowserProof() {
        String token=token();when(passwords.verifiedResetEmail(proof)).thenReturn(Optional.empty());
        assertTrue(flow.verifiedEmail(token).isEmpty());assertThrows(BadRequestException.class,()->flow.complete(token,"New-Pass1!"));
        verify(passwords,never()).resetPasswordVerified(any(),any());
    }
}
