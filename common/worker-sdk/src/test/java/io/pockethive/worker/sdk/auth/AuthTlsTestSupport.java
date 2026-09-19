package io.pockethive.worker.sdk.auth;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Generates test-owned TLS credentials and trusts only their certificate in the test client. */
final class AuthTlsTestSupport {
    private static final String TEST_PASSWORD = "test-password";

    private AuthTlsTestSupport() {}

    static Contexts create(Path temporary) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "oauth-test-tls-");
        Path keyStoreFile = directory.resolve("localhost.p12");
        Path output = directory.resolve("keytool.log");
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
            ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(List.of(
            keytool.toString(), "-genkeypair", "-noprompt", "-alias", "localhost",
            "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
            "-validity", "2", "-dname", "CN=localhost",
            "-ext", "SAN=DNS:localhost,IP:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
            "-storepass", TEST_PASSWORD, "-keypass", TEST_PASSWORD))
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test TLS certificate generation timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Test TLS certificate generation failed: " + Files.readString(output));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStoreFile)) {
            keys.load(input, TEST_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, TEST_PASSWORD.toCharArray());
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(keyManagers.getKeyManagers(), null, null);

        KeyStore trustedCertificate = KeyStore.getInstance("PKCS12");
        trustedCertificate.load(null, null);
        trustedCertificate.setCertificateEntry("localhost", keys.getCertificate("localhost"));
        Path trustStoreFile = directory.resolve("trusted-certificate.p12");
        try (OutputStream outputStore = Files.newOutputStream(trustStoreFile)) {
            trustedCertificate.store(outputStore, TEST_PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustedCertificate);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trustManagers.getTrustManagers(), null);
        return new Contexts(server, client, trustStoreFile, TEST_PASSWORD);
    }

    record Contexts(SSLContext server, SSLContext client, Path trustStoreFile, String trustStorePassword) {}
}
