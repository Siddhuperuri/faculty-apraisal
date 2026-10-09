package edu.svec.fams.documents;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where file bytes live. The database keeps only the opaque key, so the store can move (local disk today,
 * institution-approved object storage later) without touching any data.
 */
public interface DocumentStorage {

    /** What was written: the opaque key, the size actually stored and the SHA-256 of the content (lower-case hex). */
    record Stored(String key, long size, String sha256) {}

    /** Thrown when the content is longer than allowed; nothing is kept. */
    class TooLargeException extends IOException {
        public TooLargeException(long max) { super("File exceeds " + max + " bytes"); }
    }

    /** Streams {@code in} into storage, never keeping more than {@code maxBytes}. */
    Stored store(InputStream in, long maxBytes) throws IOException;

    /** Opens a stored file for reading. @throws IOException if it no longer exists */
    InputStream open(String key) throws IOException;

    /** Removes a stored file; a missing file is not an error. */
    void delete(String key);
}
