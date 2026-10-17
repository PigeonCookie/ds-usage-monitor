package com.pigeoncookie.dsmonitor.core;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES/GCM encryption backed by a non-exportable Android Keystore key.
 *
 * <p>The key never leaves the Keystore (hardware-backed where available), so the stored ciphertext
 * is useless to anything that reads the preferences file, including a rooted device or a backup.
 */
public final class SecretBox {

    /** Prefix marking a value that was written by {@link #seal(String)}. */
    public static final String PREFIX = "enc:";

    private static final String ALIAS = "dsmonitor.secrets";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private SecretBox() {
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    /** @return base64(iv || ciphertext), or null when the keystore is unavailable. */
    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the plain text, or null when it cannot be decrypted (new device / cleared keystore). */
    public static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) {
            return null;
        }
        try {
            byte[] all = Base64.decode(sealed, Base64.NO_WRAP);
            if (all.length <= IV_BYTES) {
                return null;
            }
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
