package com.github.anicmv.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description SHA-256 工具：计算字节内容哈希与带长度前缀的拼接键。
 */
public final class ContentHash {
    private ContentHash() {}

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 流式读取，避免整份文件载入内存。 */
    public static String sha256(Path file) throws IOException {
        try (var stream = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            stream.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(stream.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 使用长度前缀，避免字符串拼接的边界歧义。 */
    public static String key(String... values) {
        var value = new StringBuilder();
        for (var part : values) value.append(part.length()).append(':').append(part);
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
