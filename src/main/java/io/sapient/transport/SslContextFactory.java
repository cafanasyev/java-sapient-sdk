package io.sapient.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Factory for creating {@link SSLContext} instances configured for mutual TLS (mTLS). */
public class SslContextFactory {

    /** Creates a new {@code SslContextFactory}. */
    public SslContextFactory() {}

    /**
     * Creates an SSLContext configured with mutual TLS (mTLS) from raw byte arrays.
     *
     * @param clientKey PKCS8-encoded private key bytes
     * @param keyAlgorithm private key algorithm (e.g. "RSA", "EC")
     * @param clientCert X.509-encoded client certificate bytes (DER or PEM)
     * @param caCert X.509-encoded CA certificate bytes (DER or PEM)
     * @return a configured SSLContext ready for use with SocketProvider
     * @throws GeneralSecurityException if key/certificate processing fails
     * @throws IOException if reading the input bytes fails
     */
    public SSLContext create(
            byte[] clientKey, String keyAlgorithm, byte[] clientCert, byte[] caCert)
            throws GeneralSecurityException, IOException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());

        trustStore.load(null);
        trustStore.setCertificateEntry("ca", cf.generateCertificate(new ByteArrayInputStream(caCert)));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null);

        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(clientCert));
        PrivateKey key = KeyFactory.getInstance(keyAlgorithm).generatePrivate(new PKCS8EncodedKeySpec(clientKey));
        keyStore.setKeyEntry("client", key, new char[0], new Certificate[] {cert});

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return ctx;
    }
}
