package io.sapient.transport;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;

/**
 * Loads test TLS certificates from src/test/resources/tls/ and builds SSLContexts for both client
 * and server sides of mTLS.
 */
class TestDerTlsConfig {

    private final byte[] caCert;
    private final byte[] serverKey;
    private final byte[] serverCert;
    private final byte[] clientKey;
    private final byte[] clientCert;

    TestDerTlsConfig() throws IOException {
        caCert = load("tls/ca.der");
        serverKey = load("tls/server-key.der");
        serverCert = load("tls/server-cert.der");
        clientKey = load("tls/client-key.der");
        clientCert = load("tls/client-cert.der");
    }

    SSLContext serverContext() throws GeneralSecurityException, IOException {
        return new SslContextFactory().create(serverKey, serverCert, caCert);
    }

    SSLContext clientContext() throws GeneralSecurityException, IOException {
        return new SslContextFactory().create(clientKey, clientCert, caCert);
    }

    private static byte[] load(String resourcePath) throws IOException {
        try (var is = TestDerTlsConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("resource not found: " + resourcePath);
            return is.readAllBytes();
        }
    }
}
