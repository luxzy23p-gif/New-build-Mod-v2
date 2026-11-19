package com.xfive.live.spotify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Login Spotify lewat alamat lokal (http://127.0.0.1:PORT/callback). Browser di HP yang sama membuka halaman
 * login Spotify, lalu Spotify mengarahkan balik ke server kecil ini. Tidak ada rahasia yang disimpan di mod.
 */
public final class SpotifyAuth {
    /** Port tetap. Alamat http://127.0.0.1:8898/callback harus didaftarkan di dashboard Spotify. */
    public static final int PORT = 8898;
    private static final long WAIT_MS = 5 * 60_000L;

    private final String clientId;
    private final String verifier = Pkce.verifier();
    private final String state = Pkce.state();
    private final ServerSocket server;
    private volatile boolean cancelled;

    private SpotifyAuth(String clientId, ServerSocket server) {
        this.clientId = clientId;
        this.server = server;
    }

    /** Membuka server lokal. port 0 = bebas (untuk tes). Melempar IOException jika port sudah dipakai. */
    public static SpotifyAuth open(String clientId, int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try {
            ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        } catch (IOException e) {
            ss.close();
            throw new IOException("Port " + port + " is busy. Close other apps using it and try again.", e);
        }
        return new SpotifyAuth(clientId, ss);
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/callback";
    }

    public String authorizeUrl() {
        return SpotifyApi.authorizeUrl(clientId, redirectUri(), Pkce.challenge(verifier), state);
    }

    public void cancel() {
        cancelled = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // sudah tertutup
        }
    }

    /**
     * Menunggu jawaban Spotify di thread latar. onDone menerima token, atau onError menerima pesan.
     * Server selalu ditutup di akhir.
     */
    public void awaitAsync(Consumer<SpotifyApi.Tokens> onDone, Consumer<String> onError) {
        Thread t = new Thread(() -> {
            try {
                String code = waitForCode();
                SpotifyApi.Tokens tk = SpotifyApi.exchange(clientId, code, redirectUri(), verifier);
                onDone.accept(tk);
            } catch (IOException e) {
                if (!cancelled) {
                    onError.accept(e.getMessage() == null ? "Login failed." : e.getMessage());
                }
            } catch (RuntimeException e) {
                if (!cancelled) {
                    onError.accept("Login failed.");
                }
            } finally {
                cancel();
            }
        }, "xfive-spotify-login");
        t.setDaemon(true);
        t.start();
    }

    private String waitForCode() throws IOException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < end && !cancelled) {
            server.setSoTimeout((int) Math.max(1000L, Math.min(5000L, end - System.currentTimeMillis())));
            Socket s;
            try {
                s = server.accept();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (cancelled) {
                    throw new IOException("Login cancelled.");
                }
                throw e;
            }
            try (s) {
                s.setSoTimeout(5000);
                String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
                        .readLine();
                Map<String, String> q = query(line);
                if (q == null) {
                    reply(s, 404, "Not here.");
                    continue;
                }
                if (!state.equals(q.get("state"))) {
                    reply(s, 400, "Wrong login code. Go back to Minecraft and press Connect again.");
                    throw new SpotifyException(0, "Login answer did not match. Try again.");
                }
                if (q.containsKey("error")) {
                    reply(s, 200, "Spotify login was cancelled. You can close this page.");
                    throw new SpotifyException(0, "Spotify login was cancelled.");
                }
                String code = q.get("code");
                if (code == null || code.isBlank()) {
                    reply(s, 400, "No login code received.");
                    throw new SpotifyException(0, "Spotify sent no login code.");
                }
                reply(s, 200, "XFiveLive is connected to Spotify. You can close this page and go back to Minecraft.");
                return code;
            }
        }
        throw new SpotifyException(0, "Login timed out. Press Connect again.");
    }

    /** Membaca "GET /callback?code=..&state=.. HTTP/1.1". Null jika bukan /callback. */
    static Map<String, String> query(String requestLine) {
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[0].equals("GET") || !parts[1].startsWith("/callback")) {
            return null;
        }
        int qm = parts[1].indexOf('?');
        Map<String, String> out = new HashMap<>();
        if (qm < 0) {
            return out;
        }
        for (String kv : parts[1].substring(qm + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // pasangan rusak dilewati
            }
        }
        return out;
    }

    private static void reply(Socket s, int code, String text) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                + "<body style='font-family:sans-serif;padding:24px'><h3>XFiveLive</h3><p>" + text + "</p>")
                .getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.ISO_8859_1));
        o.write(body);
        o.flush();
    }
}
