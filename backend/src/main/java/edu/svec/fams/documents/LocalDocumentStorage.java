package edu.svec.fams.documents;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores files under a private directory that must outlive the application: {@code FAMS_STORAGE_DIR}, an absolute
 * path on storage that is kept and backed up (a data disk or a shared volume, never a temporary or working
 * directory). There is no built-in location on purpose; the application refuses to start without one it can write
 * to, rather than quietly keeping uploads somewhere that a redeployment would wipe.
 *
 * <p>Names are random UUIDs, so nothing a user supplies ever reaches the file system; every key is checked against a
 * strict pattern and confined to the root before use. A file is written to a temporary name, forced to disk and only
 * then moved into place, so a stored key always names complete content that survives a power failure.
 * {@code scripts/backup.ps1} copies this directory together with the database (see docs/deployment.md).
 */
@Component
public final class LocalDocumentStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path root;

    public LocalDocumentStorage(@Value("${fams.documents.storage-dir}") String dir) {
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("FAMS_STORAGE_DIR is not set. Uploaded documents and issued reports need a "
                    + "directory that is kept and backed up; give its absolute path.");
        }
        Path configured = Path.of(dir.strip());
        if (!configured.isAbsolute()) {
            throw new IllegalStateException("FAMS_STORAGE_DIR must be an absolute path (it is \"" + dir + "\"): a relative one "
                    + "depends on where the application happens to be started from.");
        }
        this.root = configured.normalize();
        try {
            Files.createDirectories(root);
            Files.delete(Files.createTempFile(root, "probe-", ".part"));
        } catch (IOException e) {
            throw new IllegalStateException("The document storage directory " + root + " cannot be written to", e);
        }
        log.info("Uploaded documents and issued reports are kept in {}. Back it up together with the database.", root);
    }

    @Override
    public Stored store(InputStream in, long maxBytes) throws IOException {
        Files.createDirectories(root);
        Path temp = Files.createTempFile(root, "upload-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE);
                 OutputStream out = new DigestOutputStream(Channels.newOutputStream(channel), digest)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new TooLargeException(maxBytes);
                    out.write(buf, 0, n);
                }
                out.flush();
                channel.force(true);        // on the disk, not just in the operating system's cache
            }
            String key = UUID.randomUUID().toString();
            Path target = resolve(key);
            Files.createDirectories(directoryOf(key));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return new Stored(key, total, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always present
        } finally {
            Files.deleteIfExists(temp); // no-op after a successful move
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }

    /** Files are spread over sub-directories named after the first two characters of the key. */
    private Path directoryOf(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid storage key");
        return root.resolve(key.substring(0, 2));
    }

    private Path resolve(String key) {
        Path p = directoryOf(key).resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }
}
