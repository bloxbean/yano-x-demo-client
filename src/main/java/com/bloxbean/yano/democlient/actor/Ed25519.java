package com.bloxbean.yano.democlient.actor;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

/** RFC 8032 Ed25519 from a raw 32-byte seed using only the JDK (SunEC), so it also works in a native image. */
public final class Ed25519 {
    private Ed25519() {
    }

    public static byte[] sign(byte[] seed, byte[] message) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed.clone()));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(message);
            return signature.sign();
        } catch (Exception failure) {
            throw new IllegalStateException("Ed25519 signing failed", failure);
        }
    }

    /** Public key (32 bytes, RFC 8032 encoding) of a seed. The JDK key generator takes the seed as its random bytes. */
    public static byte[] publicKey(byte[] seed) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new FixedRandom(seed));
            KeyPair pair = generator.generateKeyPair();
            return encode((EdECPublicKey) pair.getPublic());
        } catch (Exception failure) {
            throw new IllegalStateException("Ed25519 key derivation failed", failure);
        }
    }

    private static byte[] encode(EdECPublicKey key) {
        byte[] y = key.getPoint().getY().toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < Math.min(32, y.length); i++) {
            out[i] = y[y.length - 1 - i];
        }
        if (key.getPoint().isXOdd()) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /** Supplies the seed as the generator's "random" bytes. */
    private static final class FixedRandom extends SecureRandom {
        private final byte[] seed;

        FixedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != seed.length) {
                throw new IllegalStateException("unexpected Ed25519 seed request of " + bytes.length + " bytes");
            }
            System.arraycopy(seed, 0, bytes, 0, bytes.length);
            Arrays.fill(seed, (byte) 0);
        }
    }
}
