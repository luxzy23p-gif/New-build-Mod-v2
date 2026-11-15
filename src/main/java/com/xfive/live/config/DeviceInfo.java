package com.xfive.live.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Membaca spesifikasi perangkat yang sedang dipakai (HP Android lewat getprop, /proc/meminfo, dan
 * penyimpanan; atau PC). Logika penyusunan teks murni dan bisa diuji; pembacaan sistem ada di detectAsync().
 * Bagian yang tidak terbaca ditulis "Unknown" (tidak pernah ditebak).
 */
public final class DeviceInfo {
    public record Result(String title, String[][] rows, boolean android) {
    }

    private static volatile Result detected;
    private static volatile Map<String, String> props = Map.of();
    private static volatile long memKb;
    private static volatile long storageBytes;
    private static volatile String glRenderer = "";
    private static volatile boolean started;
    private static volatile boolean loaded;

    private DeviceInfo() {
    }

    /** Hasil terakhir, atau null jika pembacaan belum selesai. */
    public static Result detected() {
        return detected;
    }

    /** Memulai pembacaan di thread latar (sekali saja). storagePath = folder di penyimpanan utama. */
    public static synchronized void detectAsync(Path storagePath) {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            try {
                props = readProps();
                memKb = readMemKb();
                storageBytes = readStorage(storagePath);
                loaded = true;
                rebuild();
            } catch (Throwable ignored) {
                // gagal membaca: halaman Specs tetap memakai preset
            }
        }, "xfive-device");
        t.setDaemon(true);
        t.start();
    }

    /** Teks GPU dari OpenGL (dipanggil dari thread render). Menyusun ulang hasilnya. */
    public static void setGlRenderer(String renderer) {
        glRenderer = renderer == null ? "" : renderer;
        if (loaded) {
            try {
                rebuild();
            } catch (RuntimeException ignored) {
                // abaikan
            }
        }
    }

    private static void rebuild() {
        detected = build(props, memKb, storageBytes, glRenderer, System.getProperty("os.name", ""),
                System.getProperty("os.version", ""), System.getProperty("os.arch", ""),
                Runtime.getRuntime().availableProcessors());
    }

    // ------------------------------------------------------------------ pembacaan sistem

    private static Map<String, String> readProps() {
        Map<String, String> m = new HashMap<>();
        try {
            Process p = new ProcessBuilder("getprop").redirectErrorStream(false).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 400_000) {
                        break;
                    }
                }
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            m.putAll(parseGetprop(sb.toString()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            // getprop tidak ada (bukan Android) atau tidak boleh dijalankan
        }
        if (m.isEmpty()) {
            for (String f : new String[] {"/system/build.prop", "/vendor/build.prop", "/product/build.prop"}) {
                try {
                    m.putAll(parseBuildProp(Files.readString(Path.of(f), StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException ignored) {
                    // tidak bisa dibaca
                }
            }
        }
        return m;
    }

    private static long readMemKb() {
        try {
            long kb = parseMemTotalKb(Files.readString(Path.of("/proc/meminfo"), StandardCharsets.UTF_8));
            if (kb > 0) {
                return kb;
            }
        } catch (IOException | RuntimeException ignored) {
            // lanjut ke cara lain
        }
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean ext) {
                return ext.getTotalMemorySize() / 1024L;
            }
        } catch (Throwable ignored) {
            // tidak tersedia
        }
        return 0L;
    }

    private static long readStorage(Path p) {
        try {
            return Files.getFileStore(p).getTotalSpace();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ penyusunan teks (murni)

    private static final Pattern PROP_LINE = Pattern.compile("^\\[(.+?)]: \\[(.*)]$");

    public static Map<String, String> parseGetprop(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher mt = PROP_LINE.matcher(line.strip());
            if (mt.matches()) {
                m.put(mt.group(1), mt.group(2).strip());
            }
        }
        return m;
    }

    public static Map<String, String> parseBuildProp(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            String l = line.strip();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                continue;
            }
            m.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
        }
        return m;
    }

    public static long parseMemTotalKb(String meminfo) {
        Matcher m = Pattern.compile("(?m)^MemTotal:\\s+(\\d+)\\s*kB").matcher(meminfo);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static final int[] RAM_GB = {1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32, 48, 64, 96, 128};
    private static final int[] ROM_GB = {16, 32, 64, 128, 256, 512, 1024, 2048};

    /** MemTotal selalu sedikit di bawah RAM yang dijual (8 GB tampil ~7,4); dibulatkan ke ukuran umum terdekat di atasnya. */
    public static String ramLabel(long memKb) {
        if (memKb <= 0) {
            return "?";
        }
        double gb = memKb / 1048576.0;
        for (int s : RAM_GB) {
            if (s >= gb - 0.02) {
                return s + " GB";
            }
        }
        return Math.round(gb) + " GB";
    }

    /** Partisi data lebih kecil dari yang dijual (256 GB tampil ~230); dibulatkan ke ukuran umum terdekat di atasnya. */
    public static String romLabel(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        double gb = bytes / 1e9;
        for (int s : ROM_GB) {
            if (s >= gb) {
                return s >= 1024 ? (s / 1024) + " TB" : s + " GB";
            }
        }
        return Math.round(gb) + " GB";
    }

    private static final Pattern GPU = Pattern.compile(
            "(Mali-[A-Za-z0-9]+(?: MC\\d+)?|Immortalis-[A-Za-z0-9]+(?: MC\\d+)?|Adreno(?: ?\\(TM\\))? ?\\d+|"
                    + "PowerVR [\\w-]+(?: [\\w-]+)?|Xclipse \\d+|Apple M\\d+|(?:GeForce|Radeon|Intel)[^/()]{3,40})");

    /** Nama GPU dari teks renderer OpenGL, atau null jika tidak dikenali. */
    public static String gpuFromRenderer(String r) {
        if (r == null) {
            return null;
        }
        Matcher m = GPU.matcher(r);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : null;
    }

    private static String first(Map<String, String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Hanya kode Qualcomm yang pasti; kode lain ditampilkan apa adanya. */
    static String chipName(Map<String, String> p) {
        String soc = first(p, "ro.soc.model", "ro.hardware.chipname", "ro.board.platform");
        String mfr = first(p, "ro.soc.manufacturer");
        if (soc.isEmpty()) {
            return "Unknown";
        }
        String up = soc.toUpperCase(Locale.ROOT);
        switch (up) {
            case "SM8750": return "Snapdragon 8 Elite";
            case "SM8650": return "Snapdragon 8 Gen 3";
            case "SM8550": return "Snapdragon 8 Gen 2";
            case "SM8475": return "Snapdragon 8+ Gen 1";
            case "SM8450": return "Snapdragon 8 Gen 1";
            default:
                break;
        }
        if (up.startsWith("MT")) {
            return "MediaTek " + up;
        }
        return mfr.isEmpty() || soc.toLowerCase(Locale.ROOT).startsWith(mfr.toLowerCase(Locale.ROOT)) ? soc : cap(mfr) + " " + soc;
    }

    static String osLabel(Map<String, String> p) {
        String rel = first(p, "ro.build.version.release_or_codename", "ro.build.version.release");
        String brand = first(p, "ro.product.brand", "ro.product.manufacturer").toLowerCase(Locale.ROOT);
        if (rel.isEmpty()) {
            return "Unknown";
        }
        String major = rel.split("\\.")[0];
        // XOS (Infinix/Tecno/itel) memakai nomor yang sama dengan versi Android dasarnya.
        if (brand.equals("infinix") || brand.equals("tecno") || brand.equals("itel")) {
            return "XOS " + major;
        }
        return "Android " + rel;
    }

    static String deviceName(Map<String, String> p) {
        String name = first(p, "ro.product.marketing_name", "ro.vendor.product.display", "ro.product.vendor.marketing_name",
                "ro.product.model", "ro.product.device");
        String brand = first(p, "ro.product.brand", "ro.product.manufacturer");
        if (name.isEmpty()) {
            return brand.isEmpty() ? "Android device" : cap(brand) + " device";
        }
        if (!brand.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))) {
            return cap(brand) + " " + name;
        }
        return name;
    }

    /** Menyusun judul dan 4 baris spesifikasi. Android jika properti sistem Android terbaca, selain itu PC. */
    public static Result build(Map<String, String> p, long memKb, long storageBytes, String glRenderer,
                               String osName, String osVersion, String osArch, int cores) {
        String gpuGl = gpuFromRenderer(glRenderer);
        String memRom = ramLabel(memKb) + " / " + romLabel(storageBytes);
        boolean android = !first(p, "ro.build.version.release", "ro.product.model").isEmpty();
        if (android) {
            String name = deviceName(p);
            String chip = chipName(p);
            // Model ini dikenal pemiliknya: nama chip resmi dipakai karena kode MT6897 belum dipetakan.
            String compact = name.toLowerCase(Locale.ROOT).replace(" ", "");
            if (compact.contains("infinix") && compact.contains("gt30pro")) {
                chip = "Dimensity 8350 Ultimate";
            }
            String gpu = gpuGl;
            if (gpu == null) {
                String egl = first(p, "ro.hardware.egl").toLowerCase(Locale.ROOT);
                gpu = egl.contains("mali") ? "Mali" : egl.contains("adreno") ? "Adreno" : egl.contains("powervr") ? "PowerVR" : "Unknown";
            }
            return new Result(name, new String[][] {
                    {"Chipset", chip},
                    {"RAM / ROM", memRom},
                    {"GPU", gpu},
                    {"OS", osLabel(p)}
            }, true);
        }
        String os = (osName.isEmpty() ? "Unknown" : osName) + (osVersion.isEmpty() ? "" : " " + osVersion);
        String cpu = (osArch.isEmpty() ? "Unknown" : osArch) + " / " + cores + " cores";
        return new Result((osName.isEmpty() ? "This device" : osName + " device"), new String[][] {
                {"CPU", cpu},
                {"RAM / ROM", memRom},
                {"GPU", gpuGl == null ? (glRenderer == null || glRenderer.isBlank() ? "Unknown" : glRenderer.strip()) : gpuGl},
                {"OS", os}
        }, false);
    }
}
