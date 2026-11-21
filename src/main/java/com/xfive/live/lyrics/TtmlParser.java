package com.xfive.live.lyrics;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mengubah lirik TTML (format waktu per baris/suku kata, dipakai Better Lyrics dan Apple Music) menjadi teks LRC
 * per baris. Murni Java. Terjemahan dan romanisasi (span x-translation / x-roman) dibuang.
 */
public final class TtmlParser {
    private TtmlParser() {
    }

    /** Teks LRC "[mm:ss.xx]baris" berurutan waktu, atau string kosong jika TTML tidak bisa dibaca. */
    public static String toLrc(String ttml) {
        if (ttml == null || ttml.isBlank()) {
            return "";
        }
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e; // tanpa mencetak ke konsol
                }
            });
            doc = b.parse(new ByteArrayInputStream(ttml.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception | Error e) {
            return "";
        }
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        NodeList ps = doc.getElementsByTagName("p");
        for (int i = 0; i < ps.getLength(); i++) {
            Element p = (Element) ps.item(i);
            long begin = timeOf(p);
            String text = collapse(textOf(p));
            if (begin < 0 || text.isEmpty()) {
                continue;
            }
            times.add(new long[] {begin, times.size()});
            texts.add(text);
        }
        times.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        StringBuilder out = new StringBuilder();
        for (long[] t : times) {
            long ms = t[0];
            out.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]%s%n", ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10,
                    texts.get((int) t[1])));
        }
        return out.toString();
    }

    /** Waktu mulai p: atribut begin, atau begin span pertama jika p tidak punya. -1 jika tidak ada. */
    private static long timeOf(Element p) {
        long t = parseTime(p.getAttribute("begin"));
        if (t >= 0) {
            return t;
        }
        NodeList spans = p.getElementsByTagName("span");
        for (int i = 0; i < spans.getLength(); i++) {
            t = parseTime(((Element) spans.item(i)).getAttribute("begin"));
            if (t >= 0) {
                return t;
            }
        }
        return -1;
    }

    private static String textOf(Node n) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue());
            } else if (k.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) k;
                String role = e.getAttribute("ttm:role") + e.getAttribute("role");
                if (role.contains("translation") || role.contains("roman")) {
                    continue;
                }
                if (e.getTagName().equals("br")) {
                    sb.append(' ');
                    continue;
                }
                sb.append(textOf(e));
            }
        }
        return sb.toString();
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** "1:02:03.5", "02:03.250", "12.5", "12.5s", "1500ms" menjadi milidetik. -1 jika tidak dikenali. */
    static long parseTime(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return -1;
        }
        try {
            if (s.endsWith("ms")) {
                return Math.round(Double.parseDouble(s.substring(0, s.length() - 2)));
            }
            if (s.endsWith("s") && !s.contains(":")) {
                return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1000.0);
            }
            String[] parts = s.split(":");
            if (parts.length > 3) {
                return -1;
            }
            double sec = Double.parseDouble(parts[parts.length - 1]);
            long min = parts.length >= 2 ? Long.parseLong(parts[parts.length - 2]) : 0L;
            long hour = parts.length == 3 ? Long.parseLong(parts[0]) : 0L;
            long ms = Math.round(sec * 1000.0) + (min + hour * 60) * 60_000L;
            return ms < 0 ? -1 : ms;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
