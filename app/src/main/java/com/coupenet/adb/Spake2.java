package com.coupenet.adb;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SPAKE2 sur Ed25519, compatible bit à bit avec l'implémentation de BoringSSL
 * (crypto/curve25519/spake25519.c) utilisée par adbd pour l'appairage
 * « Débogage sans fil » d'Android 11+.
 *
 * Calculs en BigInteger : lent mais largement suffisant (une seule opération).
 */
public final class Spake2 {

    public static final int ALICE = 0; // client adb
    public static final int BOB = 1;   // serveur adbd

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private static final Point B;
    private static final Point M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final Point N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = recoverX(by, 0);
        B = new Point(bx, by, BigInteger.ONE, bx.multiply(by).mod(P));
    }

    private final int role;
    private final byte[] myName;
    private final byte[] theirName;
    private BigInteger privateKey;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;

    public Spake2(int role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName.clone();
        this.theirName = theirName.clone();
    }

    public byte[] generateMessage(byte[] password) throws Exception {
        byte[] rnd = new byte[64];
        new SecureRandom().nextBytes(rnd);
        return generateMessage(password, rnd);
    }

    /** Version déterministe (tests). */
    byte[] generateMessage(byte[] password, byte[] random64) throws Exception {
        // x25519_sc_reduce puis multiplication par le cofacteur 8.
        privateKey = leToInt(random64).mod(L).shiftLeft(3);

        passwordHash = MessageDigest.getInstance("SHA-512").digest(password);
        BigInteger pw = leToInt(passwordHash).mod(L);
        // « password scalar hack » de BoringSSL : on ajoute l, 2l, 4l selon les bits bas.
        if (pw.testBit(0)) pw = pw.add(L);
        if (pw.testBit(1)) pw = pw.add(L.shiftLeft(1));
        if (pw.testBit(2)) pw = pw.add(L.shiftLeft(2));
        passwordScalar = pw;

        Point mask = mul(role == ALICE ? M : N, passwordScalar);
        Point pStar = add(mul(B, privateKey), mask);
        myMsg = encode(pStar);
        return myMsg.clone();
    }

    public byte[] processMessage(byte[] theirMsg) throws Exception {
        if (theirMsg.length != 32 || myMsg == null) throw new IllegalStateException("SPAKE2");
        Point qStar = decode(theirMsg);
        Point peersMask = mul(role == ALICE ? N : M, passwordScalar);
        Point q = add(qStar, neg(peersMask));
        byte[] shared = encode(mul(q, privateKey));

        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        if (role == ALICE) {
            lp(sha, myName); lp(sha, theirName); lp(sha, myMsg); lp(sha, theirMsg);
        } else {
            lp(sha, theirName); lp(sha, myName); lp(sha, theirMsg); lp(sha, myMsg);
        }
        lp(sha, shared);
        lp(sha, passwordHash);
        return sha.digest();
    }

    private static void lp(MessageDigest sha, byte[] data) {
        long l = data.length;
        byte[] le = new byte[8];
        for (int i = 0; i < 8; i++) { le[i] = (byte) (l & 0xff); l >>>= 8; }
        sha.update(le);
        sha.update(data);
    }

    // ---------- Courbe Edwards (coordonnées étendues) ----------

    private static final class Point {
        final BigInteger x, y, z, t;
        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) { this.x = x; this.y = y; this.z = z; this.t = t; }
    }

    private static Point add(Point p, Point q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = TWO.multiply(D).multiply(p.t).multiply(q.t).mod(P);
        BigInteger dd = TWO.multiply(p.z).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a), f = dd.subtract(c), g = dd.add(c), h = b.add(a);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    private static Point neg(Point p) {
        return new Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P));
    }

    private static Point mul(Point p, BigInteger s) {
        Point r = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (s.testBit(i)) r = add(r, p);
        }
        return r;
    }

    private static byte[] encode(Point p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zi).mod(P);
        BigInteger y = p.y.multiply(zi).mod(P);
        byte[] out = intToLe(y, 32);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    private static Point decode(byte[] s) {
        byte[] c = s.clone();
        int sign = (c[31] >> 7) & 1;
        c[31] &= 0x7f;
        BigInteger y = leToInt(c).mod(P);
        BigInteger x = recoverX(y, sign);
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("Point hors de la courbe");
        }
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P);
        return x;
    }

    // ---------- Utilitaires ----------

    private static BigInteger leToInt(byte[] le) {
        byte[] be = new byte[le.length + 1];
        for (int i = 0; i < le.length; i++) be[le.length - i] = le[i];
        return new BigInteger(be);
    }

    private static byte[] intToLe(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @SuppressWarnings("unused")
    private static boolean same(byte[] a, byte[] b) { return Arrays.equals(a, b); }
}
