package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

class SslContextFactoryTest {

    @Test
    void derCertsAndKeys() throws GeneralSecurityException, IOException {
        var config = new TestDerTlsConfig();
        assertNotNull(config.serverContext());
        assertNotNull(config.clientContext());
    }

    @Test
    void pemCertsAndKeys() throws GeneralSecurityException, IOException {
        var config = new TestPemTlsConfig();
        assertNotNull(config.serverContext());
        assertNotNull(config.clientContext());
    }
}
