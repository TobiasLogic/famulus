package dev.famulus.jev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreTest {
    @Test
    void savesAndReadsBackAKey(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        assertTrue(store.stored().isEmpty());
        store.save("sk-or-v1-abcdef0123456789");
        assertEquals("sk-or-v1-abcdef0123456789", store.stored().orElseThrow());
    }

    @Test
    void savingReplacesThePreviousKeyRatherThanAppending(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        store.save("first-key-value-here");
        store.save("second-key-value-here");
        assertEquals("second-key-value-here", store.stored().orElseThrow());
        assertEquals(1, Files.readAllLines(store.file()).stream()
                .filter(line -> line.startsWith("openrouter.api.key")).count());
    }

    @Test
    void surroundingWhitespaceFromAPasteIsTrimmed(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        store.save("  sk-or-v1-pasted-with-spaces  \n");
        assertEquals("sk-or-v1-pasted-with-spaces", store.stored().orElseThrow());
    }

    @Test
    void aBlankKeyIsRefusedSoNobodyThinksItSaved(@TempDir Path directory) {
        CredentialStore store = new CredentialStore(directory);
        assertThrows(IOException.class, () -> store.save("   "));
        assertTrue(store.stored().isEmpty());
    }

    @Test
    void clearRemovesTheKeyAndIsSafeToRepeat(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        store.save("sk-or-v1-to-be-removed");
        store.clear();
        assertTrue(store.stored().isEmpty());
        assertDoesNotThrow(store::clear);
    }

    @Test
    void theFileIsOwnerOnlyWhereThePlatformSupportsIt(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        store.save("sk-or-v1-secret-value");
        if (!Files.getFileStore(store.file()).supportsFileAttributeView("posix")) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(store.file());
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions, "A credentials file must not be readable by other users");
    }

    @Test
    void aMissingOrUnreadableFileIsEmptyRatherThanAnError(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory.resolve("does-not-exist"));
        assertTrue(store.stored().isEmpty());
        assertDoesNotThrow(store::stored);

        CredentialStore directoryInTheWay = new CredentialStore(directory);
        Files.createDirectories(directoryInTheWay.file());
        assertTrue(directoryInTheWay.stored().isEmpty());
    }

    @Test
    void anEmptyValueInTheFileCountsAsUnset(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(CredentialStore.FILE_NAME), "openrouter.api.key=\n");
        assertTrue(new CredentialStore(directory).stored().isEmpty());
    }

    @Test
    void maskingShowsEnoughToRecogniseAndNotEnoughToUse() {
        assertEquals("sk-or-...6789", CredentialStore.mask("sk-or-v1-abcdef0123456789"));
        assertEquals("not set", CredentialStore.mask(null));
        assertEquals("not set", CredentialStore.mask("   "));
        assertEquals("********", CredentialStore.mask("12345678"));
        String masked = CredentialStore.mask("sk-or-v1-00000000000000000000000000000000");
        assertFalse(masked.contains("00000000"), "The middle of a key must never be shown");
        assertTrue(masked.length() < 20);
    }

    @Test
    void theStoredKeyIsNeverWrittenIntoTheFileHeader(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        store.save("sk-or-v1-unique-marker-value");
        long occurrences = Files.readAllLines(store.file()).stream()
                .filter(line -> line.contains("sk-or-v1-unique-marker-value"))
                .count();
        assertEquals(1, occurrences, "The key should appear once, as a value, not in comments");
    }

    @Test
    void theEnvironmentOverridesTheStoredKey(@TempDir Path directory) throws IOException {
        CredentialStore store = new CredentialStore(directory);
        store.save("stored-key-value-here");
        String fromEnvironment = System.getenv(JevConfig.API_KEY_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            assertEquals(fromEnvironment.trim(), store.resolve().orElseThrow());
            assertTrue(store.isOverriddenByEnvironment());
            assertEquals("stored-key-value-here", store.stored().orElseThrow(),
                    "An environment override must not disturb what is on disk");
        } else {
            assertEquals("stored-key-value-here", store.resolve().orElseThrow());
            assertFalse(store.isOverriddenByEnvironment());
        }
    }
}
