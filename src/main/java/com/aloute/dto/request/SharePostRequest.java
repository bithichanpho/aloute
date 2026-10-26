package com.aloute.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request to send a post directly to one or more mutual friends. */
public record SharePostRequest(
        @NotEmpty(message = "Chọn ít nhất một người bạn để chia sẻ")
        @Size(max = 20, message = "Mỗi lần chỉ có thể chia sẻ với tối đa 20 người")
        List<@NotNull(message = "Thiếu mã người nhận") @Positive(message = "Mã người nhận không hợp lệ") Long> recipientIds,
        @Size(max = 500, message = "Lời nhắn tối đa 500 ký tự") String message) {}
