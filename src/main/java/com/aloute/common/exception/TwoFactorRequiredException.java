package com.aloute.common.exception;

public class TwoFactorRequiredException extends UnauthorizedException {

    public TwoFactorRequiredException() {
        super("Vui lòng nhập mã xác thực hai lớp hoặc mã khôi phục");
    }
}
