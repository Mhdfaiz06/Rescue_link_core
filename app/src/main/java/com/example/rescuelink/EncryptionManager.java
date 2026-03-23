package com.example.rescuelink;

import android.util.Log;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionManager {

    private static final String TAG           = "Encryption";
    private static final int    GCM_IV_LENGTH = 12;
    private static final int    GCM_TAG_BITS  = 128;

    private static final byte[] PSK = {
            (byte)0x52, (byte)0x65, (byte)0x73, (byte)0x63,
            (byte)0x75, (byte)0x65, (byte)0x4C, (byte)0x69,
            (byte)0x6E, (byte)0x6B, (byte)0x32, (byte)0x30,
            (byte)0x32, (byte)0x34, (byte)0x53, (byte)0x65,
            (byte)0x63, (byte)0x75, (byte)0x72, (byte)0x65,
            (byte)0x4B, (byte)0x65, (byte)0x79, (byte)0x58,
            (byte)0x58, (byte)0x58, (byte)0x58, (byte)0x58,
            (byte)0x58, (byte)0x58, (byte)0x58, (byte)0x58
    };

    // Random salt mixed into counter-based IVs so two devices with
    // the same sequence counter still produce different IVs
    private final byte[] deviceSalt;
    private final SecretKey secretKey;

    public EncryptionManager() {
        this.secretKey  = new SecretKeySpec(PSK, "AES");
        // Generate once at startup — fast SecureRandom call only once
        this.deviceSalt = new byte[4];
        new SecureRandom().nextBytes(deviceSalt);
    }

    // ─── Encrypt — FAST PATH for audio ───────────────────────────────────
    // Uses sequence number + device salt as IV instead of SecureRandom
    // Eliminates blocking entropy call on the 50fps audio hot path
    // sequence must be unique per packet — guaranteed by AtomicLong in MeshPacket

    public byte[] encryptWithSequence(byte[] plaintext, long sequence) throws Exception {
        byte[] iv = buildCounterIv(sequence);
        return encryptWithIv(plaintext, iv);
    }

    // ─── Encrypt — SAFE PATH for non-audio ───────────────────────────────
    // Uses SecureRandom — called at low frequency (text, status, SOS)
    // so blocking is not a problem

    public byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return encryptWithIv(plaintext, iv);
    }

    // ─── Core encrypt ────────────────────────────────────────────────────

    private byte[] encryptWithIv(byte[] plaintext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        // Output: [12 byte IV][ciphertext + 16 byte GCM tag]
        ByteBuffer result = ByteBuffer.allocate(GCM_IV_LENGTH + ciphertext.length);
        result.put(iv);
        result.put(ciphertext);
        return result.array();
    }

    // ─── Decrypt ─────────────────────────────────────────────────────────
    // Same code path for both counter and random IVs — IV is always prepended

    public byte[] decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length < GCM_IV_LENGTH + 1) {
            Log.w(TAG, "Too short to decrypt: "
                    + (encrypted == null ? "null" : encrypted.length));
            return null;
        }
        try {
            byte[] iv         = Arrays.copyOfRange(encrypted, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, GCM_IV_LENGTH, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);

        } catch (javax.crypto.AEADBadTagException e) {
            // Tampered or wrong key — drop silently
            // NOT logged at error level — would flood logcat at 50fps for audio
            Log.d(TAG, "Auth tag mismatch — dropped");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Decrypt error: " + e.getMessage());
            return null;
        }
    }

    // ─── Counter IV builder ───────────────────────────────────────────────
    // IV layout: [8 bytes sequence][4 bytes device salt]
    // Unique per packet because sequence is unique (AtomicLong)
    // Device salt prevents two devices with same sequence from producing same IV

    private byte[] buildCounterIv(long sequence) {
        byte[] iv = new byte[GCM_IV_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(iv);
        bb.putLong(sequence);          // 8 bytes
        bb.put(deviceSalt);            // 4 bytes
        return iv;
    }

    public static int overhead() {
        return GCM_IV_LENGTH + (GCM_TAG_BITS / 8); // 28 bytes per packet
    }
}