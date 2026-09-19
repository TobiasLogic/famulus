package dev.famulus.jev;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public final class CredentialStore {
    public static final String FILE_NAME = "credentials.properties";
    private static final String KEY_PROPERTY = "openrouter.api.key";
    private static final String HEADER =
            " Famulus credentials. Keep this file private and out of version control.\n"
            + "# The " + JevConfig.API_KEY_VARIABLE + " environment variable overrides what is stored here.";

    private final Path file;

    public CredentialStore(Path directory) {
        this.file = Objects.requireNonNull(directory, "directory").resolve(FILE_NAME);
    }

    public Optional<String> resolve() {
        String fromEnvironment = System.getenv(JevConfig.API_KEY_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Optional.of(fromEnvironment.trim());
        }
        return stored();
    }

    public Optional<String> stored() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            values.load(reader);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        String stored = values.getProperty(KEY_PROPERTY, "").trim();
        return stored.isEmpty() ? Optional.empty() : Optional.of(stored);
    }

    public boolean isOverriddenByEnvironment() {
        String fromEnvironment = System.getenv(JevConfig.API_KEY_VARIABLE);
        return fromEnvironment != null && !fromEnvironment.isBlank();
    }

    public void save(String apiKey) throws IOException {
        Objects.requireNonNull(apiKey, "apiKey");
        String trimmed = apiKey.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("Refusing to save a blank key. Use clear() to remove it.");
        }
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty(KEY_PROPERTY, trimmed);

        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        restrictPermissions();
        try (Writer writer = Files.newBufferedWriter(file)) {
            values.store(writer, HEADER);
        }
        restrictPermissions();
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    public Path file() {
        return file;
    }

    private void restrictPermissions() {
        try {
            Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (UnsupportedOperationException | IOException notPosix) {
        }
    }

    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "not set";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "*".repeat(trimmed.length());
        }
        return trimmed.substring(0, 6) + "..." + trimmed.substring(trimmed.length() - 4);
    }
}
