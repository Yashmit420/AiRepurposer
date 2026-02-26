package com.ai.repurposer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class AuthController {
    private final UserStore userStore;
    private final Passwords passwords;
    private final String adminUpgradeKey;

    public AuthController(
        UserStore userStore,
        Passwords passwords,
        @Value("${app.admin-upgrade-key:}") String adminUpgradeKey
    ) {
        this.userStore = userStore;
        this.passwords = passwords;
        String configuredKey = adminUpgradeKey == null ? "" : adminUpgradeKey.trim();
        this.adminUpgradeKey = configuredKey.isBlank() ? "abhi-nonu01" : configuredKey;
    }

    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody User body) {
        String firstName = body.firstName == null ? "" : body.firstName.trim();
        String lastName = body.lastName == null ? "" : body.lastName.trim();
        Integer age = body.age;
        String gender = body.gender == null ? "" : body.gender.trim().toLowerCase();
        String email = normalizeEmail(body.email);
        String password = body.password == null ? "" : body.password;

        if (!isValidEmail(email)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Valid email required");
        }
        if (firstName.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("First name required");
        }
        if (age == null || age < 13 || age > 120) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Valid age required");
        }
        if (!isValidGender(gender)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Valid gender required");
        }
        if (password.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Password required");
        }

        List<User> users = new ArrayList<>(userStore.loadUsers());
        for (User user : users) {
            if (email.equalsIgnoreCase(user.email)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body("User exists");
            }
        }

        User u = new User(email, passwords.hash(password), "free");
        u.firstName = firstName;
        u.lastName = lastName;
        u.age = age;
        u.gender = gender;
        users.add(u);
        userStore.saveUsers(users);
        return ResponseEntity.ok("Signup success");
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody User body) {
        String email = normalizeEmail(body.email);
        String password = body.password == null ? "" : body.password;
        if (email.isBlank() || password.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid");
        }

        List<User> users = new ArrayList<>(userStore.loadUsers());
        for (User user : users) {
            if (!email.equalsIgnoreCase(user.email)) {
                continue;
            }
            if (!passwords.matches(password, user.password)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid");
            }

            boolean changed = false;
            if (!passwords.isHashed(user.password)) {
                user.password = passwords.hash(password);
                changed = true;
            }
            user.email = email;
            String normalizedPlan = normalizePlan(user.plan);
            if (!normalizedPlan.equals(user.plan)) {
                user.plan = normalizedPlan;
                changed = true;
            }
            if (changed) {
                userStore.saveUsers(users);
            }
            return ResponseEntity.ok(user.email);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid");
    }

    @GetMapping("/user/exists")
    public Map<String, Boolean> userExists(@RequestParam String email) {
        String normalizedEmail = normalizeEmail(email);
        return Map.of("exists", userStore.existsByEmail(normalizedEmail));
    }

    @GetMapping("/plan")
    public ResponseEntity<String> plan(@RequestParam String email) {
        String normalizedEmail = normalizeEmail(email);
        return userStore.findByEmail(normalizedEmail)
            .map(user -> ResponseEntity.ok(normalizePlan(user.plan)))
            .orElseGet(() -> ResponseEntity.ok("free"));
    }

    @PostMapping("/upgrade")
    public ResponseEntity<String> upgrade(
        @RequestParam String email,
        @RequestParam(defaultValue = "pro") String plan,
        @RequestParam(defaultValue = "monthly") String cycle,
        HttpServletRequest request
    ) {
        if (!isAdminRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Admin key required");
        }

        String normalizedEmail = normalizeEmail(email);
        String normalizedPlan = normalizePlan(plan);
        String normalizedCycle = normalizeCycle(cycle);
        Long expiry = calculateExpiryEpochDay(normalizedPlan, normalizedCycle);

        List<User> users = new ArrayList<>(userStore.loadUsers());
        boolean updated = false;

        for (User user : users) {
            if (normalizedEmail.equalsIgnoreCase(user.email)) {
                user.plan = normalizedPlan;
                user.billingCycle = normalizedPlan.equals("free") ? "none" : normalizedCycle;
                user.planExpiresAtEpochDay = expiry;
                updated = true;
                break;
            }
        }

        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        userStore.saveUsers(users);
        return ResponseEntity.ok("Upgraded");
    }

    @GetMapping("/admin/users")
    public ResponseEntity<?> adminUsers(HttpServletRequest request) {
        if (!isAdminRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Admin key required");
        }

        List<Map<String, Object>> users = userStore.loadUsers().stream()
            .sorted(Comparator.comparing(u -> normalizeEmail(u.email)))
            .map(u -> {
                Map<String, Object> item = new HashMap<>();
                item.put("email", normalizeEmail(u.email));
                item.put("plan", normalizePlan(u.plan));
                item.put("cycle", normalizeCycle(u.billingCycle));
                item.put("remainingDays", remainingDays(u.planExpiresAtEpochDay));
                return item;
            })
            .toList();

        return ResponseEntity.ok(users);
    }

    @PostMapping("/admin/users/plan")
    public ResponseEntity<String> adminUpdateUserPlan(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!isAdminRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Admin key required");
        }

        String email = normalizeEmail(body.getOrDefault("email", ""));
        String plan = normalizePlan(body.getOrDefault("plan", "free"));
        String cycle = normalizeCycle(body.getOrDefault("cycle", "monthly"));
        if (email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing email");
        }

        List<User> users = new ArrayList<>(userStore.loadUsers());
        boolean updated = false;
        for (User user : users) {
            if (email.equalsIgnoreCase(user.email)) {
                user.plan = plan;
                user.billingCycle = plan.equals("free") ? "none" : cycle;
                user.planExpiresAtEpochDay = calculateExpiryEpochDay(plan, cycle);
                updated = true;
                break;
            }
        }

        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        userStore.saveUsers(users);
        return ResponseEntity.ok("Plan updated");
    }

    @GetMapping("/account")
    public ResponseEntity<Map<String, String>> account(@RequestParam String email) {
        String normalizedEmail = normalizeEmail(email);
        Map<String, String> out = new HashMap<>();

        userStore.findByEmail(normalizedEmail).ifPresentOrElse(user -> {
            out.put("email", normalizeEmail(user.email));
            out.put("plan", normalizePlan(user.plan));
        }, () -> {
            out.put("email", normalizedEmail);
            out.put("plan", "free");
        });

        return ResponseEntity.ok(out);
    }

    @PostMapping("/account/update")
    public ResponseEntity<String> updateAccount(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String token = AuthTokenInterceptor.extractToken(request);
        if (token.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing auth token");
        }

        String email = normalizeEmail(body.getOrDefault("email", ""));
        if (!token.equalsIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token does not match account");
        }

        String newEmail = normalizeEmail(body.getOrDefault("newEmail", email));
        String password = body.getOrDefault("password", "");

        if (newEmail.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email cannot be empty");
        }

        List<User> users = new ArrayList<>(userStore.loadUsers());
        User target = null;

        for (User user : users) {
            if (email.equalsIgnoreCase(user.email)) {
                target = user;
                break;
            }
        }

        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        if (!newEmail.equalsIgnoreCase(email)) {
            for (User user : users) {
                if (newEmail.equalsIgnoreCase(user.email)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already used");
                }
            }
            target.email = newEmail;
        }

        if (!password.isBlank()) {
            target.password = passwords.hash(password);
        }

        target.plan = normalizePlan(target.plan);
        userStore.saveUsers(users);
        return ResponseEntity.ok("Account updated");
    }

    @PostMapping("/account/delete")
    public ResponseEntity<String> deleteAccount(@RequestParam String email, HttpServletRequest request) {
        String token = AuthTokenInterceptor.extractToken(request);
        String normalizedEmail = normalizeEmail(email);
        if (token.isBlank() || !token.equalsIgnoreCase(normalizedEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token does not match account");
        }

        List<User> users = new ArrayList<>(userStore.loadUsers());
        boolean removed = users.removeIf(user -> normalizedEmail.equalsIgnoreCase(user.email));
        userStore.saveUsers(users);
        return ResponseEntity.ok(removed ? "Deleted" : "User not found");
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestParam(required = false) String email, HttpServletRequest request) {
        String token = AuthTokenInterceptor.extractToken(request);
        if (token.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing auth token");
        }
        if (email != null && !email.isBlank() && !token.equalsIgnoreCase(normalizeEmail(email))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token does not match account");
        }
        return ResponseEntity.ok("Logged out");
    }

    private static Long calculateExpiryEpochDay(String plan, String cycle) {
        if ("free".equals(plan)) {
            return null;
        }
        long days = "yearly".equals(cycle) ? 365 : 30;
        return LocalDate.now().plusDays(days).toEpochDay();
    }

    private static long remainingDays(Long expiryEpochDay) {
        if (expiryEpochDay == null) {
            return 0;
        }
        long days = expiryEpochDay - LocalDate.now().toEpochDay();
        return Math.max(days, 0);
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        String providedKey = request.getHeader("X-Admin-Key");
        return providedKey != null && adminUpgradeKey.equals(providedKey.trim());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static boolean isValidEmail(String email) {
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static boolean isValidGender(String gender) {
        return "male".equals(gender) || "female".equals(gender) || "other".equals(gender);
    }

    private static String normalizePlan(String plan) {
        String normalized = plan == null ? "free" : plan.trim().toLowerCase();
        if ("advanced".equals(normalized) || "agency".equals(normalized)) {
            return "advanced";
        }
        if ("pro".equals(normalized) || "premium".equals(normalized)) {
            return "pro";
        }
        return "free";
    }

    private static String normalizeCycle(String cycle) {
        String normalized = cycle == null ? "monthly" : cycle.trim().toLowerCase();
        if ("yearly".equals(normalized)) {
            return "yearly";
        }
        if ("none".equals(normalized)) {
            return "none";
        }
        return "monthly";
    }
}
