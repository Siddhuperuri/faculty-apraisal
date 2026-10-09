package edu.svec.fams.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of a request body. The servlet container limits form posts and file uploads but not a JSON body, and a
 * JSON body is read into memory whole, so without this anyone (sign-in needs no account) could exhaust memory by
 * sending very large ones. The application accepts no file uploads, so the limit applies to every request.
 *
 * <p>A declared length over the limit is refused at once. A body with no declared length (chunked) is cut off when it
 * passes the limit; see {@link TooLargeException}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // straight after the request ID, before anything reads the body
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final String MESSAGE = "The request is too large.";

    /** Thrown while reading a body that turns out to be longer than allowed; reported as 413. */
    public static class TooLargeException extends IOException {
        public TooLargeException(long max) { super("Request body exceeds " + max + " bytes"); }
    }

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${fams.max-request-bytes}") long maxBytes) { this.maxBytes = maxBytes; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            ErrorResponses.write(response, HttpStatus.PAYLOAD_TOO_LARGE, MESSAGE);
            return;
        }
        chain.doFilter(new Capped(request, maxBytes), response);
    }

    private static final class Capped extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;
        private BufferedReader reader;

        Capped(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new CappedStream(super.getInputStream(), max);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(getInputStream(),
                        encoding == null ? StandardCharsets.UTF_8.name() : encoding));
            }
            return reader;
        }
    }

    private static final class CappedStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long max;
        private long count;

        CappedStream(ServletInputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        private void counted(long n) throws IOException {
            if (n > 0 && (count += n) > max) throw new TooLargeException(max);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) counted(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            counted(n);
            return n;
        }

        @Override public boolean isFinished() { return in.isFinished(); }
        @Override public boolean isReady() { return in.isReady(); }
        @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
        @Override public int available() throws IOException { return in.available(); }
        @Override public void close() throws IOException { in.close(); }
    }
}
