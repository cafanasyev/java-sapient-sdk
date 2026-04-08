package io.sapient.transport;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;

/**
 * Loads test TLS certificates from src/test/resources/tls/ (PEM format) and builds SSLContexts for
 * both client and server sides of mTLS.
 */
class TestPemTlsConfig {

    private final byte[] caCert;
    private final byte[] serverKey;
    private final byte[] serverCert;
    private final byte[] clientKey;
    private final byte[] clientCert;

    TestPemTlsConfig() throws IOException {
        caCert = load("tls/ca.pem");
        serverKey = load("tls/server-key.pem");
        serverCert = load("tls/server-cert.pem");
        clientKey = load("tls/client-key.pem");
        clientCert = load("tls/client-cert.pem");
    }

    SSLContext serverContext() throws GeneralSecurityException, IOException {
        return new SslContextFactory().create(serverKey, serverCert, caCert);
    }

    SSLContext clientContext() throws GeneralSecurityException, IOException {
        return new SslContextFactory().create(clientKey, clientCert, caCert);
    }

    private static byte[] load(String resourcePath) throws IOException {
        try (var is = TestPemTlsConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("resource not found: " + resourcePath);
            return is.readAllBytes();
        }
    }
}
