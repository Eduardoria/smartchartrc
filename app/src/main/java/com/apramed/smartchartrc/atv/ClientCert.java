package com.apramed.smartchartrc.atv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Certificado do celular (identidade do controle perante a TV).
 * Gerado uma vez (RSA 2048, autoassinado) e guardado na pasta do app.
 * A TV lembra deste certificado após o pareamento.
 */
public final class ClientCert {

    private static final char[] PASS = "smartchart".toCharArray();

    public final PrivateKey key;
    public final X509Certificate cert;

    private ClientCert(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    public static synchronized ClientCert loadOrCreate(File dir) throws Exception {
        File kf = new File(dir, "atv_key.der");
        File cf = new File(dir, "atv_cert.der");
        if (kf.exists() && cf.exists()) {
            try {
                PrivateKey k = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(kf.toPath())));
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(cf.toPath())));
                return new ClientCert(k, c);
            } catch (Exception ignored) {
                // arquivo corrompido: gera de novo (será preciso parear outra vez)
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        X509Certificate c = selfSign(kp, "SmartChart RC");
        dir.mkdirs();
        Files.write(kf.toPath(), kp.getPrivate().getEncoded());
        Files.write(cf.toPath(), c.getEncoded());
        return new ClientCert(kp.getPrivate(), c);
    }

    /** SSLContext que apresenta este certificado e aceita o certificado (autoassinado) da TV. */
    public SSLContext sslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("atv", key, PASS, new java.security.cert.Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
        return ctx;
    }

    // ------------------------------------------------- X.509 v1 em DER, à mão

    private static X509Certificate selfSign(KeyPair kp, String cn) throws Exception {
        byte[] algId = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}),
                tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 86_400_000L)),
                utcTime(new Date(now + 10L * 365 * 86_400_000L)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = seq(serial, algId, name, validity, name, kp.getPublic().getEncoded());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, algId, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do { tmp[n++] = (byte) (v & 0x7F); v >>>= 7; } while (v != 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] seq(byte[]... items) { return tlv(0x30, concat(items)); }

    private static byte[] set(byte[]... items) { return tlv(0x31, concat(items)); }

    private static byte[] concat(byte[]... items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] i : items) o.write(i, 0, i.length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = v.length;
        if (len < 0x80) {
            o.write(len);
        } else {
            byte[] l = BigInteger.valueOf(len).toByteArray();
            int start = l[0] == 0 ? 1 : 0;
            o.write(0x80 | (l.length - start));
            o.write(l, start, l.length - start);
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }
}
