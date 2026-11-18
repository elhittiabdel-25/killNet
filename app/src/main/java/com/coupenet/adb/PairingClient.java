package com.coupenet.adb;

import android.content.Context;
import android.net.ssl.SSLSockets;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/**
 * Appairage « Débogage sans fil » (Android 11+), comme « adb pair hôte:port code ».
 * Réimplémentation du protocole d'AOSP (adb/pairing_connection + pairing_auth).
 */
public final class PairingClient {

    private static final byte VERSION = 1;
    private static final byte TYPE_SPAKE2 = 0;
    private static final byte TYPE_PEER_INFO = 1;
    private static final int PEER_INFO_SIZE = 8192;
    private static final String EXPORTED_KEY_LABEL = "adb-label\u0000";
    private static final byte[] CLIENT_NAME = "adb pair client\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER_NAME = "adb pair server\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8);

    public static class WrongCodeException extends IOException {
        WrongCodeException() { super("Code d'association incorrect"); }
    }

    private PairingClient() {}

    public static void pair(Context ctx, String host, int port, String code) throws Exception {
        AdbKey key = AdbKey.get(ctx);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 10_000);
        raw.setSoTimeout(20_000);
        raw.setTcpNoDelay(true);
        try (SSLSocket ssl = (SSLSocket) key.sslContext().getSocketFactory()
                .createSocket(raw, host, port, true)) {
            ssl.setUseClientMode(true);
            try { ssl.setEnabledProtocols(new String[]{"TLSv1.3"}); } catch (Exception ignored) {}
            ssl.startHandshake();

            byte[] exported = SSLSockets.exportKeyingMaterial(ssl, EXPORTED_KEY_LABEL, null, 64);
            byte[] codeBytes = code.trim().getBytes(StandardCharsets.UTF_8);
            byte[] password = new byte[codeBytes.length + exported.length];
            System.arraycopy(codeBytes, 0, password, 0, codeBytes.length);
            System.arraycopy(exported, 0, password, codeBytes.length, exported.length);

            DataInputStream in = new DataInputStream(ssl.getInputStream());
            OutputStream out = ssl.getOutputStream();

            // 1. Échange des messages SPAKE2
            Spake2 spake = new Spake2(Spake2.ALICE, CLIENT_NAME, SERVER_NAME);
            writePacket(out, TYPE_SPAKE2, spake.generateMessage(password));
            byte[] theirMsg = readPacket(in, TYPE_SPAKE2);
            byte[] keyMaterial = spake.processMessage(theirMsg);
            byte[] aesKey = hkdfSha256(keyMaterial, HKDF_INFO, 16);

            // 2. Échange chiffré des clés publiques
            byte[] peerInfo = new byte[PEER_INFO_SIZE];
            peerInfo[0] = 0; // ADB_RSA_PUB_KEY
            byte[] pub = key.adbPublicKey("coupenet@android").getBytes(StandardCharsets.UTF_8);
            System.arraycopy(pub, 0, peerInfo, 1, Math.min(pub.length, PEER_INFO_SIZE - 2));
            writePacket(out, TYPE_PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aesKey, 0, peerInfo));

            byte[] theirInfo = readPacket(in, TYPE_PEER_INFO);
            try {
                gcm(Cipher.DECRYPT_MODE, aesKey, 0, theirInfo);
            } catch (Exception e) {
                throw new WrongCodeException();
            }
        } catch (java.io.EOFException e) {
            // adbd ferme la connexion quand le code ne correspond pas.
            throw new WrongCodeException();
        }
    }

    private static void writePacket(OutputStream out, byte type, byte[] payload) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
        h.put(VERSION).put(type).putInt(payload.length);
        out.write(h.array());
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(DataInputStream in, byte expectedType) throws IOException {
        byte version = in.readByte();
        byte type = in.readByte();
        int len = in.readInt();
        if (version != VERSION || type != expectedType || len <= 0 || len > PEER_INFO_SIZE * 2) {
            throw new IOException("Réponse d'appairage inattendue");
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private static byte[] gcm(int mode, byte[] key, long seq, byte[] data) throws Exception {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++) iv[i] = (byte) (seq >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(data);
    }

    /** HKDF-SHA256 (RFC 5869), sel vide. */
    static byte[] hkdfSha256(byte[] ikm, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }
}
