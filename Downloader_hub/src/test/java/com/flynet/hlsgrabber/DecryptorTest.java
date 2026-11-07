package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.download.Decryptor;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

/** Тесты AES-128-дешифрования (TZ раздел 9): round-trip, IV из последовательности, парсинг IV.
 *  AES-128 decryption tests (TZ section 9): round-trip, IV from sequence, IV parsing. */
class DecryptorTest {

    private static byte[] key16() {
        byte[] k = new byte[16];
        for (int i = 0; i < 16; i++) {
            k[i] = (byte) (i * 3 + 7);
        }
        return k;
    }

    private static byte[] encrypt(byte[] plain, byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(plain);
    }

    @Test
    void decryptRoundTripMatchesFfmpegHlsEncryption() throws Exception {
        byte[] key = key16();
        byte[] iv = Decryptor.ivFromSequence(0);
        byte[] plain = "segment payload ".repeat(400).getBytes();
        byte[] cipher = encrypt(plain, key, iv);
        Decryptor d = new Decryptor(key);
        assertArrayEquals(plain, d.decrypt(cipher, iv));
    }

    @Test
    void ivFromSequenceIs16ByteBigEndian() {
        byte[] iv0 = Decryptor.ivFromSequence(0);
        assertEquals(16, iv0.length);
        for (byte b : iv0) {
            assertEquals(0, b);
        }
        byte[] iv1 = Decryptor.ivFromSequence(1);
        assertEquals(1, iv1[15] & 0xFF);
        assertEquals(0, iv1[14] & 0xFF);
        byte[] iv255 = Decryptor.ivFromSequence(255);
        assertEquals(255, iv255[15] & 0xFF);
        byte[] iv65536 = Decryptor.ivFromSequence(65536);
        assertEquals(1, iv65536[13] & 0xFF);
        assertEquals(0, iv65536[14] & 0xFF);
        assertEquals(0, iv65536[15] & 0xFF);
    }

    @Test
    void parsesIvHexForms() {
        byte[] iv = Decryptor.parseIv("0x00000000000000000000000000000000");
        for (byte b : iv) {
            assertEquals(0, b);
        }
        byte[] iv2 = Decryptor.parseIv("0000000000000000000000000000000a");
        assertEquals(10, iv2[15] & 0xFF);
        byte[] iv3 = Decryptor.parseIv("0x2A");
        assertEquals(42, iv3[15] & 0xFF);
        assertEquals(0, iv3[0] & 0xFF);
    }

    @Test
    void rejectsNon16Key() {
        assertThrows(IllegalArgumentException.class, () -> new Decryptor(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> new Decryptor(new byte[32]));
    }

    @Test
    void decryptMatchesKnownVector() throws Exception {
        // Known-answer стиль: шифруем ровно так, как ffmpeg HLS AES-128 (IV = BE последовательности),
        // затем дешифруем Decryptor’ом, посимвольное равенство.
        // Known-answer style: encrypt exactly as ffmpeg HLS AES-128 does (IV = sequence BE),
        // then decrypt with Decryptor, byte-for-byte equality.
        byte[] key = key16();
        for (int seq : new int[]{0, 1, 11, 12, 13}) {
            byte[] iv = Decryptor.ivFromSequence(seq);
            byte[] plain = new byte[188 * 101 + seq];
            for (int i = 0; i < plain.length; i++) {
                plain[i] = (byte) ((i + seq) % 256);
            }
            byte[] cipher = encrypt(plain, key, iv);
            Decryptor d = new Decryptor(key);
            assertArrayEquals(plain, d.decrypt(cipher, iv),
                    "sequence " + seq);
        }
    }
}