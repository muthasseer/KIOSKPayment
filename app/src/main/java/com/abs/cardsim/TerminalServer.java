package com.abs.cardsim;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny HTTP server that behaves like a card terminal for the ABS Smart Parking kiosk.
 * Pure Java (no Android classes) so it can also be tested on a PC.
 *
 *   GET  /status                    -> terminal state (kiosk connectivity check)
 *   POST /sale   {amount,reference,timeout_seconds}  -> waits until the operator taps a result
 *   POST /cancel                    -> cancels the sale that is waiting
 *   GET  /result?reference=XYZ      -> result of an earlier sale (used when the kiosk lost the reply)
 */
public class TerminalServer {

    public interface Listener {
        /** A sale arrived and is waiting for the tester to choose a result. (server thread) */
        void onSale(Sale sale);
        /** The waiting sale finished (answered, cancelled or timed out). (server thread) */
        void onSaleEnded();
        void onLog(String line);
    }

    public static final class Sale {
        public final double amount;
        public final String reference;
        public final int timeoutSeconds;

        Sale(double amount, String reference, int timeoutSeconds) {
            this.amount = amount;
            this.reference = reference;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static final class Result {
        public static final int SUCCESS = 0, REJECTED = 1, EXPIRED = 2, INSUFFICIENT = 3, CANCELLED = 4, TIMEOUT = 5;

        public final boolean approved;
        public final String code;
        public final String message;
        public final String ref;

        Result(boolean approved, String code, String message, String ref) {
            this.approved = approved;
            this.code = code;
            this.message = message;
            this.ref = ref;
        }

        public static Result of(int kind) {
            switch (kind) {
                case SUCCESS:      return new Result(true, "00", "Payment successful", "SIM-" + System.currentTimeMillis());
                case REJECTED:     return new Result(false, "05", "Card rejected", "");
                case EXPIRED:      return new Result(false, "54", "Card expired", "");
                case INSUFFICIENT: return new Result(false, "51", "Insufficient balance", "");
                case CANCELLED:    return new Result(false, "CX", "Cancelled", "");
                default:           return new Result(false, "TO", "Timed out waiting for card", "");
            }
        }
    }

    private static final class Pending {
        final Sale sale;
        final ArrayBlockingQueue<Result> answer = new ArrayBlockingQueue<Result>(1);
        Pending(Sale sale) { this.sale = sale; }
    }

    private static final Pattern AMOUNT = Pattern.compile("\"amount\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?");

    private final int port;
    private final Listener listener;
    private final Object lock = new Object();
    private final Map<String, Result> history = Collections.synchronizedMap(new LinkedHashMap<String, Result>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) { return size() > 50; }
    });

    private volatile Pending pending;
    private volatile boolean running;
    private volatile boolean simulateOffline;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    public TerminalServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public int getPort() { return port; }

    public void setSimulateOffline(boolean offline) {
        this.simulateOffline = offline;
        log(offline ? "Terminal set to OFFLINE (simulated)" : "Terminal back ONLINE");
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        serverSocket = s;
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { acceptLoop(); }
        }, "terminal-accept");
        t.setDaemon(true);
        t.start();
        log("Listening on port " + port);
    }

    public synchronized void stop() {
        running = false;
        respond(Result.CANCELLED);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    /** Operator chose a result for the sale that is waiting. Returns false when no sale is waiting. */
    public boolean respond(int kind) {
        Pending p = pending;
        if (p == null) return false;
        return p.answer.offer(Result.of(kind));
    }

    public boolean isSaleWaiting() { return pending != null; }

    // ------------------------------------------------------------------ internals

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() { handle(c); }
                });
            } catch (IOException e) {
                if (running) log("Accept error: " + e.getMessage());
            } catch (RejectedExecutionException e) {
                return;
            }
        }
    }

    private void log(String line) {
        try { listener.onLog(line); } catch (RuntimeException ignored) { }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) { send(out, 400, "{\"ok\":false,\"error\":\"Bad request\"}"); return; }
            String method = requestLine[0].toUpperCase(Locale.US);
            String target = requestLine[1];

            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    try { contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            if (contentLength < 0 || contentLength > 65536) { send(out, 413, "{\"ok\":false,\"error\":\"Body too large\"}"); return; }
            byte[] bodyBytes = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(bodyBytes, off, contentLength - off);
                if (n < 0) break;
                off += n;
            }
            String body = new String(bodyBytes, 0, off, "UTF-8");

            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

            if (method.equals("GET") && (path.equals("/") || path.equals("/status"))) {
                if (simulateOffline) { send(out, 503, "{\"ok\":false,\"error\":\"Terminal offline (simulated)\"}"); return; }
                send(out, 200, "{\"ok\":true,\"terminal\":\"ABS-CARD-SIM\",\"version\":\"1.0\",\"state\":\"" + (pending != null ? "WAITING_CARD" : "IDLE") + "\"}");
            } else if (method.equals("POST") && path.equals("/sale")) {
                sale(out, body);
            } else if (method.equals("POST") && path.equals("/cancel")) {
                boolean cancelled = respond(Result.CANCELLED);
                send(out, 200, "{\"ok\":true,\"cancelled\":" + cancelled + "}");
            } else if (method.equals("GET") && path.equals("/result")) {
                String ref = queryParam(query, "reference");
                Result r = ref == null ? null : history.get(ref);
                if (r == null) send(out, 200, "{\"ok\":true,\"found\":false}");
                else send(out, 200, resultJson(r, null, ref, true));
            } else {
                send(out, 404, "{\"ok\":false,\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            log("Request error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void sale(OutputStream out, String body) throws IOException {
        if (simulateOffline) { send(out, 503, "{\"ok\":false,\"error\":\"Terminal offline (simulated)\"}"); return; }

        Matcher m = AMOUNT.matcher(body);
        double amount = -1;
        if (m.find()) { try { amount = Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { } }
        if (!(amount > 0)) { send(out, 400, "{\"ok\":false,\"error\":\"Invalid amount\"}"); return; }

        String reference = jsonString(body, "reference");
        if (reference == null || reference.length() == 0) reference = "REF-" + System.currentTimeMillis();
        int timeout = 90;
        Matcher tm = Pattern.compile("\"timeout_seconds\"\\s*:\\s*\"?([0-9]+)\"?").matcher(body);
        if (tm.find()) { try { timeout = Integer.parseInt(tm.group(1)); } catch (NumberFormatException ignored) { } }
        timeout = Math.max(10, Math.min(300, timeout));

        Pending p = new Pending(new Sale(amount, reference, timeout));
        boolean busy;
        synchronized (lock) {
            busy = pending != null;
            if (!busy) pending = p;
        }
        if (busy) { send(out, 409, "{\"ok\":false,\"error\":\"Terminal busy with another sale\"}"); return; }

        log(String.format(Locale.US, "SALE Rs. %.2f  ref %s  - waiting for tester", amount, reference));
        try { listener.onSale(p.sale); } catch (RuntimeException ignored) { }

        Result r = null;
        try { r = p.answer.poll(timeout, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (r == null) r = Result.of(Result.TIMEOUT);
        synchronized (lock) { pending = null; }
        history.put(reference, r);
        log(String.format(Locale.US, "RESULT %s (%s)  Rs. %.2f", r.message, r.code, amount));
        try { listener.onSaleEnded(); } catch (RuntimeException ignored) { }
        send(out, 200, resultJson(r, p.sale, reference, false));
    }

    private static String resultJson(Result r, Sale s, String reference, boolean found) {
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        if (found) sb.append(",\"found\":true");
        sb.append(",\"approved\":").append(r.approved)
          .append(",\"code\":\"").append(esc(r.code)).append("\"")
          .append(",\"message\":\"").append(esc(r.message)).append("\"")
          .append(",\"ref\":\"").append(esc(r.ref)).append("\"")
          .append(",\"reference\":\"").append(esc(reference)).append("\"");
        if (s != null) sb.append(",\"amount\":").append(String.format(Locale.US, "%.2f", s.amount));
        return sb.append("}").toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String jsonString(String body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String queryParam(String query, String key) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                try { return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8"); } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int state = 0;
        while (sb.length() < 16384) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            sb.append((char) b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) return sb.substring(0, sb.length() - 4);
        }
        return null;
    }

    private static void send(OutputStream out, int status, String json) throws IOException {
        String text;
        switch (status) {
            case 200: text = "OK"; break;
            case 400: text = "Bad Request"; break;
            case 404: text = "Not Found"; break;
            case 409: text = "Conflict"; break;
            case 413: text = "Payload Too Large"; break;
            case 503: text = "Service Unavailable"; break;
            default:  text = "Error";
        }
        byte[] payload = json.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + " " + text + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(payload);
        out.flush();
    }
}
