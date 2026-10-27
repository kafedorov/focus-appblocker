package ru.focusguard;

import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Recovery {
    private static byte[] derive(String code, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, 120_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    public static void create(Store store, String code) throws Exception {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] hash = derive(code, salt);
        boolean saved = store.prefs.edit().putString("recovery_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("recovery_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).commit();
        if (!saved) throw new java.io.IOException("Could not save recovery code");
    }
    public static boolean exists(Store store) { return !store.text("recovery_hash", "").isEmpty(); }
    public static boolean verify(Store store, String code) {
        try {
            return MessageDigest.isEqual(derive(code, Base64.decode(store.text("recovery_salt", ""), Base64.NO_WRAP)),
                Base64.decode(store.text("recovery_hash", ""), Base64.NO_WRAP));
        } catch (Exception e) { return false; }
    }
}
