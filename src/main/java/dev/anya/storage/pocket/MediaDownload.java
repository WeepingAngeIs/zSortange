package dev.anya.storage.pocket;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** A single explicit client request. TLS connects to the address already checked, even if DNS changes. */
final class MediaDownload {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int TIMEOUT_MS = 4000;
    private static final long REQUEST_NANOS = 20_000_000_000L;

    static byte[] fetch(String input) throws IOException {
        URI uri = directUri(input);
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        String path=uri.getRawPath();
        if(path==null || path.isEmpty()) path="/";
        if(uri.getRawQuery()!=null) path+="?"+uri.getRawQuery();
        return download(host,path);
    }

    static URI directUri(String input) throws Failure {
        if (input == null || input.length() > 2048) throw invalid();
        URI uri;
        try { uri = URI.create(input); } catch (IllegalArgumentException e) { throw invalid(); }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !host.matches("(?i)[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}") || host.length() > 253) throw invalid();
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".arpa")) throw invalid();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com"))
            throw new Failure("YouTube needs a supported player. Use a direct media file here.");
        if ((host.equals("discord.com") || host.endsWith(".discord.com") || host.equals("discordapp.com")
                || host.endsWith(".discordapp.com")) && !path.startsWith("/attachments/"))
            throw new Failure("That is a Discord message. Use the attachment link instead.");
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        if (!path.startsWith("/") || !path.chars().allMatch(c -> c >= 33 && c < 127)) throw invalid();
        return uri;
    }

    private static byte[] download(String host, String path) throws IOException {
        long deadline = System.nanoTime() + REQUEST_NANOS;
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw invalid();
        for (InetAddress address : addresses) if (!publicAddress(address)) throw invalid();
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(addresses[0], 443), TIMEOUT_MS);
            connection.setSoTimeout(TIMEOUT_MS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(connection, host, 443, true)) {
                var params = tls.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                params.setServerNames(List.of(new SNIHostName(host)));
                params.setApplicationProtocols(new String[] {"http/1.1"});
                tls.setSSLParameters(params);
                tls.startHandshake();
                tls.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                        + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return body(new BufferedInputStream(tls.getInputStream()), deadline);
            }
        }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        if (bytes.length == 16) {
            // Globally routed unicast only; exclude transition, benchmarking and documentation ranges.
            return (a & 224) == 32 && !(a == 32 && b == 2)
                    && !(a == 32 && b == 1 && (c <= 1 || c == 13 && (bytes[3] & 255) == 184))
                    && !(a == 63 && b == 255);
        }
        return a > 0 && a < 224 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127) && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && (b == 0 || b == 168 || b == 88 && c == 99))
                && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                && !(a == 203 && b == 0 && c == 113);
    }

    private static byte[] body(InputStream in, long deadline) throws IOException {
        String status = line(in, deadline);
        if (status.matches("HTTP/1\\.[01] (401|403|404|410)(?: .*)?"))
            throw new Failure("This link expired, needs sign-in, or is unavailable. Choose a fresh direct link.");
        if (status.matches("HTTP/1\\.[01] 3[0-9]{2}(?: .*)?"))
            throw new Failure("This address redirects. Choose the direct media file.");
        if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw invalid();
        var headers = new HashMap<String, String>();
        for (int i = 0; ; i++) {
            String line = line(in, deadline);
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (i >= 64 || colon <= 0) throw invalid();
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9-]+")) throw invalid();
            if (List.of("content-encoding", "content-length", "transfer-encoding").contains(key)
                    && headers.putIfAbsent(key, line.substring(colon + 1).trim()) != null) throw invalid();
        }
        if (!headers.getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity")) throw invalid();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        String encoding = headers.get("transfer-encoding");
        if (encoding != null) {
            if (!encoding.equalsIgnoreCase("chunked") || headers.containsKey("content-length")) throw invalid();
            for (int chunks = 0; chunks < 8192; chunks++) {
                String count = line(in, deadline);
                if (!count.matches("[a-fA-F0-9]{1,7}")) throw invalid();
                int length = Integer.parseInt(count, 16);
                if (length == 0) return out.toByteArray();
                transfer(in, out, buffer, length, deadline);
                if (!line(in, deadline).isEmpty()) throw invalid();
            }
            throw invalid();
        }
        String length = headers.get("content-length");
        if (length != null && !length.matches("[0-9]{1,8}")) throw invalid();
        transfer(in, out, buffer, length == null ? -1 : Integer.parseInt(length), deadline);
        return out.toByteArray();
    }

    private static void transfer(InputStream in, ByteArrayOutputStream out, byte[] buffer, int remaining, long deadline)
            throws IOException {
        if (remaining > MAX_BYTES - out.size()) throw invalid();
        while (remaining != 0) {
            check(deadline);
            int n = in.read(buffer, 0, remaining < 0 ? buffer.length : Math.min(remaining, buffer.length));
            if (n < 0) { if (remaining < 0) break; throw new EOFException(); }
            if (out.size() + n > MAX_BYTES) throw invalid();
            out.write(buffer, 0, n);
            if (remaining > 0) remaining -= n;
        }
    }

    private static String line(InputStream in, long deadline) throws IOException {
        StringBuilder text = new StringBuilder();
        while (text.length() < 4096) {
            check(deadline);
            int b = in.read();
            if (b == '\r' && in.read() == '\n') return text.toString();
            if (b < 32 || b > 126) throw invalid();
            text.append((char)b);
        }
        throw invalid();
    }

    private static void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw invalid();
    }

    static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }

    private static Failure invalid() {
        return new Failure("Use a direct public HTTPS media file up to 16 MiB.");
    }
}
