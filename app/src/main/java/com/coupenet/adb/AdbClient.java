package com.coupenet.adb;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

/**
 * Petit client du protocole adb (comme « adb shell ») vers l'adbd du téléphone
 * lui-même, via le port TLS du Débogage sans fil. Les commandes tournent avec
 * l'identité « shell », comme depuis un PC.
 */
public final class AdbClient implements Closeable {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;
    private static final int A_VERSION = 0x01000001;
    private static final int A_STLS_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 1024 * 1024;

    public static class NotPairedException extends IOException {
        NotPairedException(String m) { super(m); }
    }

    private final Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private AdbClient(Socket s) { this.socket = s; }

    public static AdbClient connect(Context ctx, String host, int port) throws Exception {
        AdbKey key = AdbKey.get(ctx);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 5_000);
        raw.setSoTimeout(15_000);
        raw.setTcpNoDelay(true);
        AdbClient c = new AdbClient(raw);
        try {
            c.in = new DataInputStream(raw.getInputStream());
            c.out = raw.getOutputStream();
            c.send(A_CNXN, A_VERSION, MAX_PAYLOAD,
                    "host::features=shell_v2,cmd\u0000".getBytes(StandardCharsets.UTF_8));
            Msg m = c.read();
            if (m.cmd == A_STLS) {
                c.send(A_STLS, A_STLS_VERSION, 0, new byte[0]);
                SSLSocket ssl = (SSLSocket) key.sslContext().getSocketFactory()
                        .createSocket(raw, host, port, true);
                ssl.setUseClientMode(true);
                try { ssl.setEnabledProtocols(new String[]{"TLSv1.3"}); } catch (Exception ignored) {}
                try {
                    ssl.startHandshake();
                } catch (IOException e) {
                    throw new NotPairedException("Connexion TLS refusée par adbd (appairage nécessaire) : " + e.getMessage());
                }
                c.in = new DataInputStream(ssl.getInputStream());
                c.out = ssl.getOutputStream();
                m = c.read();
            }
            if (m.cmd == A_AUTH) {
                throw new NotPairedException("adbd demande une autorisation (port non TLS)");
            }
            if (m.cmd != A_CNXN) throw new IOException("Réponse adb inattendue");
            return c;
        } catch (java.io.EOFException e) {
            c.close();
            throw new NotPairedException("adbd a fermé la connexion (appairage nécessaire)");
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    /** Exécute une commande shell et renvoie [code de sortie, sortie]. */
    public Result shell(String command) throws IOException {
        int localId = nextLocalId++;
        String wrapped = "(" + command + ") 2>&1; echo \"@@RC=$?\"";
        send(A_OPEN, localId, 0, ("shell:" + wrapped + "\u0000").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) {
                remoteId = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remoteId = m.arg0;
                output.write(m.data, 0, m.data.length);
                send(A_OKAY, localId, remoteId, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                if (m.arg1 == localId || m.arg1 == 0) {
                    if (remoteId != 0) send(A_CLSE, localId, remoteId, new byte[0]);
                    break;
                }
            }
        }
        String text = output.toString("UTF-8");
        int idx = text.lastIndexOf("@@RC=");
        int code = -1;
        if (idx >= 0) {
            try { code = Integer.parseInt(text.substring(idx + 5).trim()); } catch (Exception ignored) {}
            text = text.substring(0, idx);
        }
        return new Result(code, text.trim());
    }

    public static final class Result {
        public final int code;
        public final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum
        int magic = h.getInt();
        if (magic != ~m.cmd || len < 0 || len > MAX_PAYLOAD * 2) throw new IOException("Paquet adb invalide");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

}
