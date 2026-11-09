package io.sapient.transport.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KeepalivePrefixesTest {

    @Test
    void testNetcatHasNoPrefixes() {
        // no sentinels in the out-of-band modes, so 0xFFFFFFFF still trips the frame
        // size guard and prefix 0 still means an empty SapientMessage
        assertNull(KeepalivePrefixes.of(HealthCheckType.NETCAT));
    }

    @Test
    void testIcmpHasNoPrefixes() {
        assertNull(KeepalivePrefixes.of(HealthCheckType.ICMP));
    }

    @Test
    void testEchoPingAndPongAreBothZero() {
        var p = KeepalivePrefixes.of(HealthCheckType.ECHO);
        assertEquals(0x00000000, p.ping());
        assertEquals(0x00000000, p.pong());
    }

    @Test
    void testPingpongAnswersWithAllOnes() {
        var p = KeepalivePrefixes.of(HealthCheckType.PINGPONG);
        assertEquals(0x00000000, p.ping());
        assertEquals(0xFFFFFFFF, p.pong());
    }

    @Test
    void testTransportNativeHasNoWireFormat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> KeepalivePrefixes.of(HealthCheckType.TRANSPORT_NATIVE));
    }
}
