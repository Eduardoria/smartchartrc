package com.apramed.smartchartrc.atv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Cliente do protocolo "Android TV Remote v2" — o mesmo usado pelo app Google TV
 * para controlar a TV pelo celular via Wi‑Fi.
 *
 *  - Porta 6467: pareamento (a TV mostra um código de 6 caracteres).
 *  - Porta 6466: comandos (teclas).
 *
 * Código Java puro (sem APIs do Android) — os callbacks chegam em threads de fundo.
 */
public class AtvRemote {

    public static final int PORT_REMOTE = 6466;
    public static final int PORT_PAIRING = 6467;

    public interface Listener {
        /** Pronto para enviar teclas. */
        void onConnected(String tvName);
        /** Conexão encerrada. needsPairing = a TV não reconhece este celular. */
        void onDisconnected(boolean needsPairing, String reason);
        /** A TV está mostrando o código; chame finishPairing(código). */
        void onPairingCodeShown();
        void onPairingResult(boolean ok, String message);
        void onLog(String line);
    }

    // Direções de tecla
    public static final int SHORT = 3, START_LONG = 1, END_LONG = 2;

    private static final int STATUS_OK = 200;
    // Recursos: PING | KEY | POWER | VOLUME | APP_LINK (IME desligado de propósito)
    private static final int FEATURES = 1 | 2 | 32 | 64 | 512;

    private final ClientCert cert;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private volatile SSLSocket remoteSock;
    private volatile OutputStream remoteOut;
    private volatile boolean ready;
    private volatile String tvName = "";
    private volatile SSLSocket pairSock;
    private volatile boolean closing;
    private volatile int gen;
    private volatile long active = FEATURES;

    public AtvRemote(ClientCert cert, Listener listener) {
        this.cert = cert;
        this.listener = listener;
    }

    public boolean isReady() { return ready; }

    private void log(String s) { listener.onLog("[Wi‑Fi] " + s); }

    private SSLSocket open(String host, int port) throws Exception {
        SSLSocket s = (SSLSocket) cert.sslContext().getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(0);
        s.setTcpNoDelay(true);
        s.startHandshake();
        return s;
    }

    // ================================================================ COMANDOS

    /** Conecta à porta de comandos em uma thread própria. */
    public void connect(final String host) {
        disconnect();
        closing = false;
        final int my = ++gen;
        Thread t = new Thread(() -> runRemote(host, my), "atv-remote");
        t.setDaemon(true);
        t.start();
    }

    private void runRemote(String host, int my) {
        SSLSocket s = null;
        boolean needsPairing = false;
        boolean tcpOk = false;
        String reason = "";
        try {
            log("Conectando a " + host + ":" + PORT_REMOTE);
            s = (SSLSocket) cert.sslContext().getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, PORT_REMOTE), 5000);
            tcpOk = true;
            s.setTcpNoDelay(true);
            s.startHandshake();
            if (my != gen) return;
            remoteSock = s;
            remoteOut = s.getOutputStream();
            InputStream in = s.getInputStream();
            tvName = "";
            log("Conexão segura aberta");
            while (!closing && my == gen) {
                handleRemote(Proto.parse(Proto.readDelimited(in)));
            }
        } catch (SSLException e) {
            // A TV encerra o handshake quando não conhece o certificado deste celular.
            needsPairing = true;
            reason = "TV pediu pareamento (" + e.getMessage() + ")";
        } catch (Exception e) {
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            // Com TLS 1.3 a recusa do certificado aparece só na primeira leitura
            // (conexão resetada/fechada antes de a TV mandar qualquer coisa).
            if (tcpOk && !ready && !closing && tvName.isEmpty()) needsPairing = true;
        } finally {
            boolean current = my == gen && !closing;
            if (my == gen) {
                ready = false;
                remoteOut = null;
                remoteSock = null;
            }
            try { if (s != null) s.close(); } catch (IOException ignored) { }
            if (current) {
                log("Desconectado: " + reason);
                listener.onDisconnected(needsPairing, reason);
            }
        }
    }

    private void handleRemote(Proto.R m) {
        if (m.has(1)) {                                   // remote_configure
            Proto.R cfg = m.sub(1);
            long supported = cfg.num(1, 0);
            Proto.R di = cfg.sub(2);
            tvName = (di.str(2) + " " + di.str(1)).trim();
            log("TV: " + tvName + " | recursos 0x" + Long.toHexString(supported));
            if ((supported & 2) == 0) log("ATENÇÃO: a TV informou que não aceita teclas");
            active = FEATURES & supported;
            send(new Proto.W().msg(1, new Proto.W()
                    .varint(1, active)
                    .msg(2, new Proto.W()
                            .string(1, "SmartChart RC")
                            .string(2, "Apramed")
                            .varint(3, 1)
                            .string(4, "1")
                            .string(5, "atvremote")
                            .string(6, "1.0.0"))));
        } else if (m.has(2)) {                            // remote_set_active
            send(new Proto.W().msg(2, new Proto.W().varint(1, active)));
            markReady();
        } else if (m.has(8)) {                            // ping
            long v = m.sub(8).num(1, 0);
            send(new Proto.W().msg(9, new Proto.W().varint(1, v)));
        } else if (m.has(40)) {                           // remote_start
            markReady();
        } else if (m.has(3)) {
            log("TV informou erro: " + m.sub(3));
        }
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        listener.onConnected(tvName.isEmpty() ? "Android TV" : tvName);
    }

    /** Envia uma tecla (código KEYCODE do Android). */
    public void sendKey(int androidKeyCode, int direction) {
        if (remoteOut == null) return;
        send(new Proto.W().msg(10, new Proto.W().varint(1, androidKeyCode).varint(2, direction)));
    }

    private void send(Proto.W msg) {
        final byte[] b = msg.toBytes();
        writer.execute(() -> {
            OutputStream o = remoteOut;
            if (o == null) return;
            try {
                Proto.writeDelimited(o, b);
            } catch (IOException e) {
                log("Falha ao enviar: " + e.getMessage());
                try { SSLSocket s = remoteSock; if (s != null) s.close(); } catch (IOException ignored) { }
            }
        });
    }

    public void disconnect() {
        closing = true;
        ready = false;
        try { SSLSocket s = remoteSock; if (s != null) s.close(); } catch (IOException ignored) { }
    }

    // ================================================================ PAREAMENTO

    /** Abre o pareamento; a TV passa a mostrar o código. Chama onPairingCodeShown(). */
    public void startPairing(final String host) {
        cancelPairing();
        Thread t = new Thread(() -> {
            try {
                log("Pareando com " + host + ":" + PORT_PAIRING);
                SSLSocket s = open(host, PORT_PAIRING);
                pairSock = s;
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                sendPolo(out, new Proto.W().msg(10, new Proto.W()
                        .string(1, "atvremote")
                        .string(2, "SmartChart RC")));
                while (true) {
                    Proto.R m = Proto.parse(Proto.readDelimited(in));
                    checkStatus(m);
                    if (m.has(11)) {                     // pairing_request_ack
                        sendPolo(out, new Proto.W().msg(20, new Proto.W()
                                .msg(1, new Proto.W().varint(1, 3).varint(2, 6))   // hexadecimal, 6
                                .varint(3, 1)));                                    // papel: input
                    } else if (m.has(20)) {              // options
                        sendPolo(out, new Proto.W().msg(30, new Proto.W()
                                .msg(1, new Proto.W().varint(1, 3).varint(2, 6))
                                .varint(2, 1)));
                    } else if (m.has(31)) {              // configuration_ack → código na tela
                        log("A TV está mostrando o código");
                        listener.onPairingCodeShown();
                        return;                          // socket fica aberto até finishPairing
                    } else {
                        throw new IOException("resposta inesperada: " + m);
                    }
                }
            } catch (Exception e) {
                cancelPairing();
                listener.onPairingResult(false, "Não foi possível iniciar o pareamento: " + e.getMessage());
            }
        }, "atv-pair");
        t.setDaemon(true);
        t.start();
    }

    /** Envia o código digitado (6 caracteres hexadecimais mostrados na TV). */
    public void finishPairing(final String rawCode) {
        Thread t = new Thread(() -> {
            SSLSocket s = pairSock;
            try {
                if (s == null) throw new IOException("pareamento não iniciado");
                String code = rawCode.trim().toUpperCase(java.util.Locale.US);
                if (!code.matches("[0-9A-F]{6}")) {
                    listener.onPairingResult(false, "O código tem 6 caracteres (0–9 e A–F).");
                    return;
                }
                RSAPublicKey c = (RSAPublicKey) cert.cert.getPublicKey();
                RSAPublicKey sv = (RSAPublicKey) ((X509Certificate) s.getSession().getPeerCertificates()[0])
                        .getPublicKey();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(unsigned(c.getModulus()));
                md.update(unsigned(c.getPublicExponent()));
                md.update(unsigned(sv.getModulus()));
                md.update(unsigned(sv.getPublicExponent()));
                md.update(hex(code.substring(2)));
                byte[] h = md.digest();
                if ((h[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
                    listener.onPairingResult(false, "Código incorreto. Confira o código na TV.");
                    return;   // mantém o pareamento aberto para tentar de novo
                }
                OutputStream out = s.getOutputStream();
                sendPolo(out, new Proto.W().msg(40, new Proto.W().bytes(1, h)));
                Proto.R m = Proto.parse(Proto.readDelimited(s.getInputStream()));
                checkStatus(m);
                if (!m.has(41)) throw new IOException("resposta inesperada: " + m);
                log("Pareado com sucesso");
                cancelPairing();
                listener.onPairingResult(true, "Pareado!");
            } catch (Exception e) {
                cancelPairing();
                listener.onPairingResult(false, "Pareamento recusado: " + e.getMessage());
            }
        }, "atv-pair-finish");
        t.setDaemon(true);
        t.start();
    }

    public void cancelPairing() {
        SSLSocket s = pairSock;
        pairSock = null;
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }

    private static void sendPolo(OutputStream out, Proto.W body) throws IOException {
        // OuterMessage: protocol_version=2, status=OK, + corpo
        Proto.W w = new Proto.W().varint(1, 2).varint(2, STATUS_OK);
        byte[] head = w.toBytes();
        byte[] b = body.toBytes();
        byte[] all = new byte[head.length + b.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(b, 0, all, head.length, b.length);
        Proto.writeDelimited(out, all);
    }

    private static void checkStatus(Proto.R m) throws IOException {
        long st = m.num(2, STATUS_OK);
        if (st != STATUS_OK) throw new IOException("TV respondeu status " + st);
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
