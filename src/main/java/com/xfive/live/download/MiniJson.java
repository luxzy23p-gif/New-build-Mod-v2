package com.xfive.live.download;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pembaca JSON kecil (tanpa library). Hasil: Map, List, String, Double, Boolean, atau null.
 * Melempar IllegalArgumentException jika JSON rusak.
 */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("null");
        }
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("extra data at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** String dari nilai apa pun yang masuk akal (String, angka, atau elemen pertama list). */
    public static String str(Object o) {
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof Double) {
            double d = (Double) o;
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            return l.isEmpty() ? null : str(l.get(0));
        }
        return null;
    }

    public static long num(Object o, long def) {
        if (o instanceof Double) {
            return (long) (double) (Double) o;
        }
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).strip());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("too deep");
        }
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                lit("true");
                return Boolean.TRUE;
            case 'f':
                lit("false");
                return Boolean.FALSE;
            case 'n':
                lit("null");
                return null;
            default:
                return number();
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) {
            throw new IllegalArgumentException("bad literal at " + i);
        }
        i += w.length();
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (st == i) {
            throw new IllegalArgumentException("unexpected char at " + i);
        }
        try {
            return Double.parseDouble(s.substring(st, i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number at " + st);
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("key expected at " + i);
            }
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("'" + c + "' expected at " + i);
        }
        i++;
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("bad escape");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw new IllegalArgumentException("bad unicode escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("bad unicode escape");
                    }
                    i += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }
}
