package com.ai.repurposer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class UserStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path usersFile;

    public UserStore(@Value("${app.users-file:data/users.json}") String usersFilePath) {
        this.usersFile = Paths.get(usersFilePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() throws IOException {
        ensureStorageReady();
    }

    public List<User> loadUsers() {
        lock.readLock().lock();
        try {
            if (!Files.exists(usersFile)) {
                return new ArrayList<>();
            }
            byte[] bytes = Files.readAllBytes(usersFile);
            String raw = new String(bytes, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return new ArrayList<>();
            }
            List<User> users = mapper.readValue(raw, new TypeReference<List<User>>() {});
            return new ArrayList<>(users);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read users store", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveUsers(List<User> users) {
        lock.writeLock().lock();
        try {
            ensureStorageReady();
            Path temp = usersFile.resolveSibling(usersFile.getFileName() + ".tmp");
            mapper.writeValue(temp.toFile(), users);
            try {
                Files.move(temp, usersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, usersFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist users store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String target = email.trim().toLowerCase();
        return loadUsers().stream()
            .filter(u -> u.email != null && u.email.equalsIgnoreCase(target))
            .findFirst();
    }

    public boolean existsByEmail(String email) {
        return findByEmail(email).isPresent();
    }

    private void ensureStorageReady() throws IOException {
        Path parent = usersFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (Files.exists(usersFile)) {
            return;
        }

        Path legacy = Paths.get("users.json").toAbsolutePath().normalize();
        if (!usersFile.equals(legacy) && Files.exists(legacy)) {
            Files.copy(legacy, usersFile, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        try (InputStream bundled = UserStore.class.getResourceAsStream("/com/ai/repurposer/users.json")) {
            if (bundled != null) {
                Files.copy(bundled, usersFile, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }

        Files.writeString(usersFile, "[]", StandardCharsets.UTF_8);
    }
}
