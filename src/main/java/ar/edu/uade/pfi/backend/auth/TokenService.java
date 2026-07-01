package ar.edu.uade.pfi.backend.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final ObjectMapper objectMapper;
    private final String signingKey;
    private final long accessTokenSeconds;

    public TokenService(
        ObjectMapper objectMapper,
        @Value("${pfi.auth.jwt-secret:pfi-demo-change-me-2026}") String signingKey,
        @Value("${pfi.auth.access-token-seconds:3600}") long accessTokenSeconds
    ) {
        this.objectMapper = objectMapper;
        this.signingKey = signingKey;
        this.accessTokenSeconds = accessTokenSeconds;
    }

    public long accessTokenSeconds() {
        return accessTokenSeconds;
    }

    public String issueAccessToken(DoctorAccount account) {
        Instant now = Instant.now();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", account.id());
        payload.put("email", account.email());
        payload.put("name", account.fullName());
        payload.put("roles", account.roles());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plusSeconds(accessTokenSeconds).getEpochSecond());
        String encodedHeader = base64Json(header);
        String encodedPayload = base64Json(payload);
        String signature = sign(encodedHeader + "." + encodedPayload);
        return encodedHeader + "." + encodedPayload + "." + signature;
    }

    public Claims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String expectedSignature = sign(parts[0] + "." + parts[1]);
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return null;
            }
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {});
            Number exp = (Number) payload.get("exp");
            if (exp == null || exp.longValue() < Instant.now().getEpochSecond()) {
                return null;
            }
            Object rolesValue = payload.get("roles");
            List<String> roles = rolesValue instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of("DOCTOR");
            return new Claims(
                payload.getOrDefault("sub", "").toString(),
                payload.getOrDefault("email", "").toString(),
                payload.getOrDefault("name", "").toString(),
                roles
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private String base64Json(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not encode token", ex);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign token", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }

    public record Claims(String subject, String email, String name, List<String> roles) {}
}
