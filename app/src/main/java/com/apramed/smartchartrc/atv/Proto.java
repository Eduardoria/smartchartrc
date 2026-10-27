package com.apramed.smartchartrc.atv;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Protobuf mínimo (só o necessário para o protocolo do Android TV Remote). */
final class Proto {
    private Proto() {}

    // ------------------------------------------------------------ escrita

    static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        W varint(int field, long v) {
            writeVarint(out, ((long) field << 3));
            writeVarint(out, v);
            return this;
        }

        W bytes(int field, byte[] b) {
            writeVarint(out, ((long) field << 3) | 2);
            writeVarint(out, b.length);
            out.write(b, 0, b.length);
            return this;
        }

        W string(int field, String s) { return bytes(field, s.getBytes(StandardCharsets.UTF_8)); }

        W msg(int field, W m) { return bytes(field, m.toBytes()); }

        byte[] toBytes() { return out.toByteArray(); }
    }

    static void writeVarint(OutputStream o, long v) {
        try {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Envia uma mensagem com o tamanho na frente (varint), como o protocolo exige. */
    static void writeDelimited(OutputStream o, byte[] msg) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(msg.length + 5);
        writeVarint(b, msg.length);
        b.write(msg);
        o.write(b.toByteArray());
        o.flush();
    }

    static byte[] readDelimited(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("conexão fechada");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) throw new IOException("tamanho inválido");
        }
        if (len > 1 << 20) throw new IOException("mensagem grande demais: " + len);
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new EOFException("conexão fechada");
            off += r;
        }
        return buf;
    }

    // ------------------------------------------------------------ leitura

    /** Mensagem decodificada: campo → lista de valores (Long para varint, byte[] para length-delimited). */
    static final class R {
        final Map<Integer, List<Object>> f = new HashMap<>();

        boolean has(int field) { return f.containsKey(field); }

        long num(int field, long def) {
            List<Object> l = f.get(field);
            if (l == null) return def;
            Object o = l.get(l.size() - 1);
            return o instanceof Long ? (Long) o : def;
        }

        byte[] raw(int field) {
            List<Object> l = f.get(field);
            if (l == null) return null;
            Object o = l.get(l.size() - 1);
            return o instanceof byte[] ? (byte[]) o : null;
        }

        R sub(int field) {
            byte[] b = raw(field);
            return b == null ? new R() : parse(b);
        }

        String str(int field) {
            byte[] b = raw(field);
            return b == null ? "" : new String(b, StandardCharsets.UTF_8);
        }

        @Override public String toString() { return "campos " + f.keySet(); }
    }

    static R parse(byte[] b) {
        R r = new R();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = readVarint(b, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            Object v;
            switch (wire) {
                case 0: v = readVarint(b, pos); break;
                case 1: pos[0] += 8; v = null; break;
                case 2: {
                    int len = (int) readVarint(b, pos);
                    if (len < 0 || pos[0] + len > b.length) throw new IllegalArgumentException("proto truncado");
                    byte[] s = new byte[len];
                    System.arraycopy(b, pos[0], s, 0, len);
                    pos[0] += len;
                    v = s;
                    break;
                }
                case 5: pos[0] += 4; v = null; break;
                default: throw new IllegalArgumentException("wire type " + wire);
            }
            if (v != null) r.f.computeIfAbsent(field, k -> new ArrayList<>()).add(v);
        }
        return r;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length) throw new IllegalArgumentException("varint truncado");
            int x = b[pos[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
        }
    }
}
