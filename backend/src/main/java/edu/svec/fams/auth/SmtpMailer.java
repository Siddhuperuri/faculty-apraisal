package edu.svec.fams.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends a plain-text e-mail through the college's SMTP server. Configured from the environment: FAMS_MAIL_HOST,
 * FAMS_MAIL_PORT (587), FAMS_MAIL_SECURITY (STARTTLS, SSL or NONE), FAMS_MAIL_USERNAME, FAMS_MAIL_PASSWORD and
 * FAMS_MAIL_FROM. With no host set nothing is sent ({@link #configured()} is false). Nothing about the message is logged
 * but the recipient's domain and the outcome.
 */
@Component
public class SmtpMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);
    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}$");
    private static final int TIMEOUT_MS = 10_000;

    private final String host;
    private final int port;
    private final String security;
    private final String username;
    private final String password;
    private final String from;

    public SmtpMailer(@Value("${FAMS_MAIL_HOST:}") String host, @Value("${FAMS_MAIL_PORT:587}") int port,
                      @Value("${FAMS_MAIL_SECURITY:STARTTLS}") String security, @Value("${FAMS_MAIL_USERNAME:}") String username,
                      @Value("${FAMS_MAIL_PASSWORD:}") String password, @Value("${FAMS_MAIL_FROM:}") String from) {
        this.host = host.strip();
        this.port = port;
        this.security = security.strip().toUpperCase();
        this.username = username;
        this.password = password;
        this.from = from.strip();
    }

    public boolean configured() { return !host.isEmpty() && ADDRESS.matcher(from).matches(); }

    /** Sends one message; throws IOException when the server cannot be reached or refuses it. */
    public void send(String to, String subject, String body) throws IOException {
        if (!configured()) throw new IOException("Mail is not configured.");
        if (!ADDRESS.matcher(to).matches() || to.indexOf('\r') >= 0 || to.indexOf('\n') >= 0) throw new IOException("Bad recipient.");
        Socket socket = "SSL".equals(security)
                ? SSLSocketFactory.getDefault().createSocket(host, port)
                : new Socket(host, port);
        socket.setSoTimeout(TIMEOUT_MS);
        try {
            Conversation c = new Conversation(socket);
            c.expect(220);
            c.command("EHLO fams", 250);
            if ("STARTTLS".equals(security)) {
                c.command("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                tls.setSoTimeout(TIMEOUT_MS);
                socket = tls;
                c = new Conversation(socket);
                c.command("EHLO fams", 250);
            }
            if (!username.isEmpty()) {
                c.command("AUTH LOGIN", 334);
                c.command(b64(username), 334);
                c.command(b64(password), 235);
            }
            c.command("MAIL FROM:<" + from + ">", 250);
            c.command("RCPT TO:<" + to + ">", 250);
            c.command("DATA", 354);
            String message = "From: " + from + "\r\nTo: " + to + "\r\nSubject: " + subject.replaceAll("[\\r\\n]", " ")
                    + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                    + wrap(b64(body)) + "\r\n.";
            c.command(message, 250);
            c.command("QUIT", 221);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a socket that is already gone
            }
        }
        log.info("Mail sent to a {} address", to.substring(to.indexOf('@') + 1));
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private static String wrap(String base64) { return base64.replaceAll("(.{76})", "$1\r\n"); }

    private static final class Conversation {
        private final BufferedReader in;
        private final PrintWriter out;

        Conversation(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), false);
        }

        void command(String line, int expected) throws IOException {
            out.print(line + "\r\n");
            out.flush();
            expect(expected);
        }

        void expect(int code) throws IOException {
            String line;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("The mail server closed the connection.");
            } while (line.length() > 3 && line.charAt(3) == '-');
            if (!line.startsWith(String.valueOf(code))) throw new IOException("The mail server refused: " + line.substring(0, Math.min(3, line.length())));
        }
    }
}
