package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.documents.DocumentStorage;
import edu.svec.fams.documents.LocalDocumentStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uploaded files must be kept somewhere that outlives the application, and the application must insist on it. */
class DocumentStorageTest {

    @TempDir Path disk;

    @Test
    void theApplicationRefusesToStartWithoutAStorageDirectory() {
        for (String missing : new String[] {null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LocalDocumentStorage(missing));
            assertTrue(e.getMessage().contains("FAMS_STORAGE_DIR is not set"), e.getMessage());
        }
    }

    @Test
    void aRelativeDirectoryIsRefusedBecauseItDependsOnWhereTheApplicationIsStarted() {
        for (String relative : new String[] {"storage/documents", "./storage/documents", "target\\files"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LocalDocumentStorage(relative));
            assertTrue(e.getMessage().contains("must be an absolute path"), e.getMessage());
        }
    }

    @Test
    void aDirectoryThatCannotBeWrittenToIsRefusedAtStartUp() throws Exception {
        Path file = Files.writeString(disk.resolve("not-a-directory"), "x");
        assertThrows(IllegalStateException.class, () -> new LocalDocumentStorage(file.toString()));
    }

    @Test
    void aStoredFileIsCompleteOnDiskAndStillThereAfterARestart() throws Exception {
        Path root = disk.resolve("documents");
        byte[] content = "%PDF-1.4 an uploaded certificate".repeat(2000).getBytes(StandardCharsets.ISO_8859_1);

        DocumentStorage.Stored stored = new LocalDocumentStorage(root.toString()).store(new ByteArrayInputStream(content), 1_000_000);
        assertEquals(content.length, stored.size());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), stored.sha256());
        // exactly one file under the root, named by its key, and nothing half-written left beside it
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(java.util.List.of(stored.key()), files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList());
        }

        // the application stops and starts again: a new instance over the same directory serves the same bytes
        try (InputStream in = new LocalDocumentStorage(root.toString()).open(stored.key())) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    void aFileOverTheLimitLeavesNothingBehind() throws Exception {
        Path root = disk.resolve("documents");
        LocalDocumentStorage storage = new LocalDocumentStorage(root.toString());
        assertThrows(DocumentStorage.TooLargeException.class, () -> storage.store(new ByteArrayInputStream(new byte[5000]), 4096));
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }
}
