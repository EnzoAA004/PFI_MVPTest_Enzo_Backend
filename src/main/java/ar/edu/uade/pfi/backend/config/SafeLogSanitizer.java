package ar.edu.uade.pfi.backend.config;

import java.util.regex.Pattern;

/**
 * Stateless redaction utility shared by logging, auditing, and error-response code. Two
 * different granularities are exposed on purpose:
 *
 * <ul>
 *   <li>{@link #redactValue(String)} — whole-value semantics: if the value as a whole
 *       looks sensitive (a bearer token, a JWT, a password-looking string, a path, an
 *       email, ...), the entire value becomes {@code "[redacted]"}. Use this for a single
 *       discrete field — a header value, an audit metadata value, a claim.</li>
 *   <li>{@link #sanitizeMessage(String)} — substring-level redaction inside a longer
 *       free-text message (an exception message, a log line): known-sensitive substrings
 *       are individually replaced with {@code "[redacted]"}, the rest of the message is
 *       preserved. Use this for log lines where losing the whole message to a single
 *       matched token would destroy diagnostic value.</li>
 * </ul>
 *
 * Both always cap the result length. Neither is a substitute for not logging sensitive
 * data in the first place — this is defense in depth, not the primary control.
 */
public final class SafeLogSanitizer {
    public static final String REDACTED = "[redacted]";
    private static final int MAX_LENGTH = 200;

    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\b");
    private static final Pattern JDBC_URL_WITH_CREDENTIALS = Pattern.compile(
        "(?i)jdbc:[a-z0-9]+://[^\\s\"'/]*[:@][^\\s\"']*"
    );
    private static final Pattern URL_WITH_USERINFO = Pattern.compile(
        "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'/@]+:[^\\s\"'/@]+@[^\\s\"']*"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s\"']*");
    private static final Pattern TMP_OR_APP_PATH = Pattern.compile("(?<![\\w./])(?:/tmp|/app)(?:/[^\\s\"']*)?");
    private static final Pattern QUERY_STRING = Pattern.compile("\\?[A-Za-z0-9_.\\-]+=[^\\s\"']*(?:&[A-Za-z0-9_.\\-]+=[^\\s\"']*)*");
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
        "(?i)\\b(password|passwd|secret|token|credential|authorization|apikey|api_key)\\b\\s*[:=]\\s*[^\\s,;\"'&]+"
    );
    private static final Pattern MEDICAL_FILENAME = Pattern.compile(
        "(?i)\\b[\\w.\\-]+\\.(?:dcm|dicom|nii|nii\\.gz|png|jpe?g|tiff?)\\b"
    );

    private static final Pattern[] WHOLE_VALUE_SENSITIVE_PATTERNS = {
        BEARER_TOKEN, JWT, JDBC_URL_WITH_CREDENTIALS, URL_WITH_USERINFO,
        WINDOWS_PATH, TMP_OR_APP_PATH, KEY_VALUE_SECRET, EMAIL, MEDICAL_FILENAME
    };

    private SafeLogSanitizer() {
    }

    /** True if the raw value, taken as a whole, matches any known-sensitive pattern. */
    public static boolean isSensitive(String value) {
        if (value == null || value.isBlank()) return false;
        for (Pattern pattern : WHOLE_VALUE_SENSITIVE_PATTERNS) {
            if (pattern.matcher(value).find()) return true;
        }
        return looksLikeBinary(value);
    }

    /** Whole-value redaction: sensitive → "[redacted]"; otherwise the (length-capped) original. */
    public static String redactValue(String value) {
        if (value == null) return null;
        if (isSensitive(value)) return REDACTED;
        return cap(value);
    }

    /** Substring-level redaction for longer free-text messages/log lines. */
    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) return message;
        String result = message;
        result = BEARER_TOKEN.matcher(result).replaceAll(REDACTED);
        result = JWT.matcher(result).replaceAll(REDACTED);
        result = JDBC_URL_WITH_CREDENTIALS.matcher(result).replaceAll(REDACTED);
        result = URL_WITH_USERINFO.matcher(result).replaceAll(REDACTED);
        result = KEY_VALUE_SECRET.matcher(result).replaceAll(REDACTED);
        result = WINDOWS_PATH.matcher(result).replaceAll(REDACTED);
        result = TMP_OR_APP_PATH.matcher(result).replaceAll(REDACTED);
        result = QUERY_STRING.matcher(result).replaceAll("");
        result = EMAIL.matcher(result).replaceAll(REDACTED);
        result = MEDICAL_FILENAME.matcher(result).replaceAll(REDACTED);
        return cap(result);
    }

    /** Best-effort heuristic: a long run of non-printable/high-entropy characters is treated as binary content. */
    private static boolean looksLikeBinary(String value) {
        if (value.length() < 24) return false;
        int nonPrintable = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') nonPrintable++;
        }
        return nonPrintable > 0 && nonPrintable >= value.length() / 8;
    }

    private static String cap(String value) {
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) + "..." : value;
    }
}
