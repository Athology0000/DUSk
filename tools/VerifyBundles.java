// Run with: java tools/VerifyBundles.java
//
// Decrypts every .enc in build/phantom-modules/encrypted/ using the same
// AES-GCM parameters as Loader/loader/src/main/kotlin/org/phantom/loader/
// bootstrap/ModuleCrypto.kt#decryptAesGcm, then asserts the result is a JAR
// containing phantom.addon.json + the expected entrypoint .class.
//
// Reads the key from MODULE_ENCRYPTION_KEY or MASTER_KEY (base64, 32 bytes)
// to match build.gradle.kts#moduleEncryptionKey().

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class VerifyBundles {
    public static void main(String[] args) throws Exception {
        String keyB64 = firstNonNull(System.getenv("MODULE_ENCRYPTION_KEY"), System.getenv("MASTER_KEY"));
        if (keyB64 == null || keyB64.isBlank()) {
            System.err.println("Set MODULE_ENCRYPTION_KEY or MASTER_KEY (base64 32 bytes).");
            System.exit(2);
        }
        byte[] key = Base64.getDecoder().decode(keyB64.trim());
        if (key.length != 32) {
            System.err.println("Key must decode to 32 bytes, got " + key.length);
            System.exit(2);
        }

        Path encDir = Path.of("build", "phantom-modules", "encrypted");
        if (!Files.isDirectory(encDir)) {
            System.err.println("Missing " + encDir + " — run encryptPhantomModules first.");
            System.exit(2);
        }

        Map<String, String> expectations = new LinkedHashMap<>();
        expectations.put("phantom-core",   "org/phantom/internal/remote/PhantomCoreAddon.class");
        expectations.put("phantom-mining", "org/phantom/internal/remote/PhantomMiningAddon.class");
        expectations.put("phantom-slayer", "org/phantom/internal/remote/PhantomSlayerAddon.class");
        expectations.put("phantom-diana",  "org/phantom/internal/remote/PhantomDianaAddon.class");
        expectations.put("phantom-rift",   "org/phantom/internal/remote/PhantomRiftAddon.class");

        int failures = 0;
        for (var e : expectations.entrySet()) {
            String bundle = e.getKey();
            String classEntry = e.getValue();
            Path encFile = encDir.resolve(bundle + ".enc");
            if (!Files.isRegularFile(encFile)) {
                System.out.println("FAIL  " + bundle + " : " + encFile + " missing");
                failures++;
                continue;
            }
            byte[] blob = Files.readAllBytes(encFile);
            byte[] plain;
            try {
                plain = decryptGcm(key, blob);
            } catch (Exception ex) {
                System.out.println("FAIL  " + bundle + " : decrypt error — " + ex.getMessage());
                failures++;
                continue;
            }
            TreeSet<String> entries = new TreeSet<>();
            try (var zis = new ZipInputStream(new ByteArrayInputStream(plain))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    entries.add(ze.getName());
                    zis.closeEntry();
                }
            } catch (Exception ex) {
                System.out.println("FAIL  " + bundle + " : not a zip — " + ex.getMessage());
                failures++;
                continue;
            }
            if (!entries.contains("phantom.addon.json")) {
                System.out.println("FAIL  " + bundle + " : missing phantom.addon.json");
                failures++;
                continue;
            }
            if (!entries.contains(classEntry)) {
                System.out.println("FAIL  " + bundle + " : missing " + classEntry);
                failures++;
                continue;
            }
            String plainSha = sha256Hex(plain);
            System.out.printf("OK    %-16s %4d entries  plain-sha256=%s%n",
                bundle, entries.size(), plainSha);
        }

        if (failures > 0) {
            System.err.println(failures + " bundle(s) failed verification.");
            System.exit(1);
        }
        System.out.println("All " + expectations.size() + " bundles verified.");
    }

    private static byte[] decryptGcm(byte[] key, byte[] blob) throws Exception {
        if (blob.length <= 12) throw new IllegalArgumentException("ciphertext too short");
        byte[] nonce = new byte[12];
        System.arraycopy(blob, 0, nonce, 0, 12);
        byte[] ct = new byte[blob.length - 12];
        System.arraycopy(blob, 12, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ct);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] out = md.digest(data);
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String firstNonNull(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
