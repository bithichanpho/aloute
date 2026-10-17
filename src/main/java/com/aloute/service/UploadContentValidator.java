package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Checks upload signatures independently of the client-provided Content-Type.
 * This is deliberately an allow-list: unknown binary formats are rejected.
 */
public final class UploadContentValidator {
    private static final int SNIFF_BYTES = 8192;

    public enum DetectedType { IMAGE, VIDEO, FILE }
    private record Signature(DetectedType type, Set<String> acceptedMimeTypes) {}

    public DetectedType validateImage(MultipartFile file) {
        Signature signature = detect(file);
        String declared = contentType(file);
        if (signature.type() != DetectedType.IMAGE || !signature.acceptedMimeTypes().contains(declared)) {
            throw new BadRequestException("Tệp không phải ảnh hợp lệ hoặc định dạng không khớp");
        }
        return signature.type();
    }

    public DetectedType validateMedia(MultipartFile file) {
        Signature signature = detect(file);
        String declared = contentType(file);
        if (!signature.acceptedMimeTypes().contains(declared)) {
            throw new BadRequestException("Định dạng tệp không hợp lệ hoặc không khớp nội dung thực tế");
        }
        return signature.type();
    }

    private Signature detect(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Tệp rỗng");
        final byte[] head;
        try (InputStream input = file.getInputStream()) {
            head = input.readNBytes(SNIFF_BYTES);
        } catch (IOException ex) {
            throw new BadRequestException("Không thể đọc tệp tải lên");
        }
        if (head.length == 0) throw new BadRequestException("Tệp rỗng");

        if (starts(head, 0xFF, 0xD8, 0xFF)) {
            return image("image/jpeg", "image/jpg", "image/pjpeg");
        }
        if (starts(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return image("image/png");
        }
        if (startsAscii(head, "GIF87a") || startsAscii(head, "GIF89a")) {
            return image("image/gif");
        }
        if (startsAscii(head, "RIFF") && containsAscii(head, 8, "WEBP")) {
            return image("image/webp");
        }
        if (startsAscii(head, "BM")) return image("image/bmp", "image/x-ms-bmp");

        String declared = contentType(file);
        if (head.length >= 12 && containsAscii(head, 4, "ftyp")) {
            String brand = ascii(head, 8, 4);
            if (Set.of("avif", "avis").contains(brand)) return image("image/avif");
            if (Set.of("heic", "heix", "hevc", "hevx").contains(brand)) return image("image/heic", "image/heif");
            if (Set.of("mif1", "msf1").contains(brand) && declared.startsWith("image/")) return image("image/heic", "image/heif");
            if (declared.startsWith("video/")) {
                return video("video/mp4", "video/quicktime", "video/x-m4v", "video/3gpp", "video/3gpp2");
            }
            if (declared.startsWith("audio/")) return file("audio/mp4");
            throw new BadRequestException("Container ISO-BMFF không khớp Content-Type ảnh/video");
        }
        if (starts(head, 0x1A, 0x45, 0xDF, 0xA3)) {
            return declared.equals("video/x-matroska")
                    ? video("video/x-matroska", "video/webm") : video("video/webm", "video/x-matroska");
        }
        if (startsAscii(head, "RIFF") && containsAscii(head, 8, "AVI ")) {
            return video("video/x-msvideo", "video/avi");
        }

        if (startsAscii(head, "%PDF-")) return file("application/pdf", "application/octet-stream");
        if (starts(head, 0x50, 0x4B, 0x03, 0x04)
                || starts(head, 0x50, 0x4B, 0x05, 0x06)
                || starts(head, 0x50, 0x4B, 0x07, 0x08)) {
            // ZIP signature is required for archives and common OpenXML Office formats.
            return file(
                    "application/zip", "application/x-zip-compressed", "application/octet-stream",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        }
        if (starts(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return file("application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "application/octet-stream");
        }
        if (startsAscii(head, "ID3") || isMp3Frame(head)) {
            return file("audio/mpeg", "audio/mp3");
        }
        if (startsAscii(head, "OggS")) {
            return declared.startsWith("video/") ? video("video/ogg") : file("audio/ogg");
        }
        if (startsAscii(head, "RIFF") && containsAscii(head, 8, "WAVE")) {
            return file("audio/wav", "audio/x-wav");
        }
        if (isAllowedTextMime(declared) && isPlausibleUtf8Text(head)) return file(declared);
        throw new BadRequestException("Loại tệp này chưa được hỗ trợ hoặc chữ ký tệp không hợp lệ");
    }

    private static Signature image(String... mimes) { return new Signature(DetectedType.IMAGE, Set.of(mimes)); }
    private static Signature video(String... mimes) { return new Signature(DetectedType.VIDEO, Set.of(mimes)); }
    private static Signature file(String... mimes) { return new Signature(DetectedType.FILE, Set.of(mimes)); }

    private static boolean isAllowedTextMime(String mime) {
        return mime.equals("text/plain") || mime.equals("text/csv") || mime.equals("application/json");
    }

    private String contentType(MultipartFile file) {
        String value = file == null ? null : file.getContentType();
        return value == null ? "" : value.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private static boolean starts(byte[] data, int... signature) {
        if (data.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if ((data[i] & 0xFF) != signature[i]) return false;
        return true;
    }

    private static boolean startsAscii(byte[] data, String signature) { return containsAscii(data, 0, signature); }

    private static boolean containsAscii(byte[] data, int offset, String signature) {
        if (offset < 0 || data.length < offset + signature.length()) return false;
        for (int i = 0; i < signature.length(); i++) if (data[offset + i] != (byte) signature.charAt(i)) return false;
        return true;
    }

    private static String ascii(byte[] data, int offset, int length) {
        if (data.length < offset + length) return "";
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static boolean isMp3Frame(byte[] data) {
        return data.length > 1 && (data[0] & 0xFF) == 0xFF && ((data[1] & 0xE0) == 0xE0);
    }

    private static boolean isPlausibleUtf8Text(byte[] data) {
        for (byte value : data) {
            int c = value & 0xFF;
            if (c == 0 || c == 0x7F || (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f')) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }
}
