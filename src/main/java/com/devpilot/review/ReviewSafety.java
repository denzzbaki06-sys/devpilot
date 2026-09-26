package com.devpilot.review;
import java.util.regex.Pattern;
/** Defense in depth for literal credentials in untrusted PR/source text, not a complete secret scanner. */
public final class ReviewSafety {
    private ReviewSafety(){}
    private static final Pattern SECRET=Pattern.compile("(?is)(?:gh[pousr]_[a-z0-9_]{16,}|github_pat_[a-z0-9_]{16,}|sk-[a-z0-9_-]{20,}|eyJ[a-z0-9_-]{10,}\\.eyJ[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,}|-----BEGIN [^-]*PRIVATE KEY-----|(?:password|api[_-]?key|client[_-]?secret|jwt[_-]?secret|encryption[_-]?key)\\s*[=:]\\s*[\"']?[^\\s\"'$}{;]{4,})");
    public static boolean sensitive(String text){return text!=null && SECRET.matcher(text).find();}
    public static String metadata(String text,int limit){if(text==null)return ""; if(sensitive(text))return "[Sensitive PR text omitted]";return text.length()>limit?"[PR text omitted: exceeds metadata budget]":text;}
}
