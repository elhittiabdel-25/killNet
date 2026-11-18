package com.coupenet.adb;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Clé RSA de Coupe-Net auprès d'adbd (l'équivalent de ~/.android/adbkey sur un PC),
 * avec son certificat auto-signé utilisé pour les connexions TLS.
 */
public final class AdbKey {

    private static final String ALIAS = "adbkey";
    private static AdbKey instance;

    public final PrivateKey privateKey;
    public final RSAPublicKey publicKey;
    public final X509Certificate certificate;
    private SSLContext sslContext;

    private AdbKey(PrivateKey priv, RSAPublicKey pub, X509Certificate cert) {
        this.privateKey = priv;
        this.publicKey = pub;
        this.certificate = cert;
    }

    public static synchronized AdbKey get(Context ctx) throws Exception {
        if (instance != null) return instance;
        File dir = new File(ctx.getNoBackupFilesDir(), "adb");
        File keyFile = new File(dir, "adbkey.pk8");
        File certFile = new File(dir, "adbkey.crt");
        if (keyFile.exists() && certFile.exists()) {
            try {
                PrivateKey priv = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new java.io.ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
                instance = new AdbKey(priv, (RSAPublicKey) cert.getPublicKey(), cert);
                return instance;
            } catch (Exception ignored) {
                // fichier abîmé : on régénère (il faudra ré-appairer)
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        byte[] certDer = selfSignedCert(kp);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(keyFile)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(certFile)) { o.write(certDer); }
        instance = new AdbKey(kp.getPrivate(), (RSAPublicKey) kp.getPublic(), cert);
        return instance;
    }

    /** Clé publique au format adb : base64(struct RSAPublicKey) + " nom". */
    public String adbPublicKey(String name) {
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32);
        n0inv = r32.subtract(n0inv).mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64);
        bb.putInt(n0inv.intValue());
        bb.put(le(n, 256));
        bb.put(le(rr, 256));
        bb.putInt(publicKey.getPublicExponent().intValue());
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " " + name;
    }

    /**
     * Signature du jeton d'authentification adb : RSA PKCS#1 v1.5 avec l'en-tête
     * DigestInfo SHA-1, appliqué directement au jeton de 20 octets (comme RSA_sign
     * avec NID_sha1 dans adb). Calcul fait à la main pour ne dépendre d'aucun
     * fournisseur de chiffrement.
     */
    public byte[] signAdbToken(byte[] token) {
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        int k = (publicKey.getModulus().bitLength() + 7) / 8;
        byte[] em = new byte[k];
        int tLen = prefix.length + token.length;
        em[0] = 0;
        em[1] = 1;
        for (int i = 2; i < k - tLen - 1; i++) em[i] = (byte) 0xff;
        em[k - tLen - 1] = 0;
        System.arraycopy(prefix, 0, em, k - tLen, prefix.length);
        System.arraycopy(token, 0, em, k - token.length, token.length);
        BigInteger d = ((java.security.interfaces.RSAPrivateKey) privateKey).getPrivateExponent();
        BigInteger s = new BigInteger(1, em).modPow(d, publicKey.getModulus());
        byte[] be = s.toByteArray();
        byte[] out = new byte[k];
        int copy = Math.min(be.length, k);
        System.arraycopy(be, be.length - copy, out, k - copy, copy);
        return out;
    }

    public synchronized SSLContext sslContext() throws Exception {
        if (sslContext != null) return sslContext;
        SSLContext c;
        try {
            c = SSLContext.getInstance("TLSv1.3");
        } catch (Exception e) {
            c = SSLContext.getInstance("TLS");
        }
        c.init(new KeyManager[]{new KM()}, new TrustManager[]{new TrustAll()}, new SecureRandom());
        sslContext = c;
        return c;
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    // ---------- Gestionnaires TLS ----------

    private final class KM extends X509ExtendedKeyManager {
        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{ALIAS}; }
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return ALIAS; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{certificate}; }
        @Override public PrivateKey getPrivateKey(String a) { return privateKey; }
    }

    /** adbd utilise un certificat auto-signé : la sécurité vient de l'appairage, pas d'une AC. */
    @SuppressWarnings("TrustAllX509TrustManager")
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    // ---------- Certificat X.509 auto-signé (DER écrit à la main) ----------

    private static byte[] selfSignedCert(KeyPair kp) throws Exception {
        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0c, "adb".getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(
                tlv(0x17, "250101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tlv(0x18, "20991231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xa0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, sigAlg, name, validity, name, kp.getPublic().getEncoded());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, sigAlg, tlv(0x03, bits));
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, cat(parts)); }
    private static byte[] set(byte[]... parts) { return tlv(0x31, cat(parts)); }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = v.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81); o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82); o.write(len >> 8); o.write(len & 0xff);
        } else {
            o.write(0x83); o.write(len >> 16); o.write((len >> 8) & 0xff); o.write(len & 0xff);
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7f);
            v >>>= 7;
            while (v > 0) { tmp[n++] = (byte) ((v & 0x7f) | 0x80); v >>>= 7; }
            for (int k = n - 1; k >= 0; k--) o.write(tmp[k]);
        }
        return tlv(0x06, o.toByteArray());
    }
}
