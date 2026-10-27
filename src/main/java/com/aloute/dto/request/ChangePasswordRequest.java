package com.aloute.dto.request;

/** Request to change a local ALOUTE password after proving the current password.
 * Validation is performed in PasswordManagementService in the user-requested order. */
public class ChangePasswordRequest {
    private String currentPassword;
    private String newPassword;
    private String confirmNewPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    public String getConfirmNewPassword() { return confirmNewPassword; }
    public void setConfirmNewPassword(String confirmNewPassword) { this.confirmNewPassword = confirmNewPassword; }
}
