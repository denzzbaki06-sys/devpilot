package com.devpilot.indexing;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class ContentHashes {
    private ContentHashes() {}
    public static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
