package io.sapient.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/** Factory for creating {@link SSLContext} instances configured for mutual TLS (mTLS). */
public class SslContextFactory {

    /** Creates a new {@code SslContextFactory}. */
    public SslContextFactory() {}

    /**
     * Creates an SSLContext configured with mutual TLS (mTLS) from raw byte arrays.
     *
     * <p>Both PEM and DER formats are accepted for the private key and certificates.
     *
     * @param clientKey private key bytes (PEM or DER, PKCS#8, PKCS#1, or SEC1/EC)
     * @param clientCert X.509 client certificate bytes (DER or PEM)
     * @param caCert X.509 CA certificate bytes (DER or PEM)
     * @return a configured SSLContext ready for use with {@link ISocketProvider}
     * @throws GeneralSecurityException if key/certificate processing fails
     * @throws IOException if reading the input bytes fails
     */
    public SSLContext create(byte[] clientKey, byte[] clientCert, byte[] caCert)
            throws GeneralSecurityException, IOException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());

        trustStore.load(null);
        trustStore.setCertificateEntry(
                "ca", cf.generateCertificate(new ByteArrayInputStream(caCert)));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null);

        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(clientCert));
        PrivateKey key = loadPrivateKey(clientKey);
        keyStore.setKeyEntry("client", key, new char[0], new Certificate[] {cert});

        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return ctx;
    }

    private static PrivateKey loadPrivateKey(byte[] keyBytes) throws IOException {
        PrivateKeyInfo keyInfo =
                isPem(keyBytes) ? parsePem(keyBytes) : PrivateKeyInfo.getInstance(keyBytes);
        return new JcaPEMKeyConverter().getPrivateKey(keyInfo);
    }

    private static boolean isPem(byte[] bytes) {
        return bytes.length > 0 && bytes[0] == '-';
    }

    private static PrivateKeyInfo parsePem(byte[] keyBytes) throws IOException {
        try (var reader =
                new PEMParser(
                        new InputStreamReader(
                                new ByteArrayInputStream(keyBytes), StandardCharsets.UTF_8))) {
            Object obj = reader.readObject();
            return obj instanceof PEMKeyPair kp ? kp.getPrivateKeyInfo() : (PrivateKeyInfo) obj;
        }
    }
}
