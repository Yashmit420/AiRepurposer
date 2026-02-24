package com.ai.repurposer;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Passwords {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null || stored.isBlank()) {
            return false;
        }
        if (isHashed(stored)) {
            return encoder.matches(raw, stored);
        }
        return raw.equals(stored);
    }

    public boolean isHashed(String value) {
        return value != null && value.startsWith("$2");
    }
}
