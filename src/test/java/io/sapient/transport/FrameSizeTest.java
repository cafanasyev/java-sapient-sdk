package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sapient.transport.health.HealthCheckConfig;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FrameSizeTest {

    private static final int MAX = SocketClient.DEFAULT_MAX_FRAME_SIZE;

    @Test
    void testZeroLengthIsAllowed() {
        // an empty body is a valid frame today: it parses into an empty SapientMessage
        assertDoesNotThrow(() -> SocketClient.checkFrameSize(0, MAX));
    }

    @Test
    void testExactlyMaxIsAllowed() {
        assertDoesNotThrow(() -> SocketClient.checkFrameSize(MAX, MAX));
    }

    @Test
    void testOneByteOverMaxIsRejected() {
        IOException e =
                assertThrows(IOException.class, () -> SocketClient.checkFrameSize(MAX + 1, MAX));
        assertTrue(e.getMessage().contains("frame too large"), e.getMessage());
    }

    @Test
    void testNegativeLengthIsRejectedAsHugeUnsigned() {
        // 0xFFFFFFFF reads back as -1 in a signed int. It must be treated as 4294967295,
        // not as a small negative number, or `new byte[len]` throws NegativeArraySizeException.
        IOException e = assertThrows(IOException.class, () -> SocketClient.checkFrameSize(-1, MAX));
        assertTrue(e.getMessage().contains("4294967295"), e.getMessage());
    }

    @Test
    void testLargePositiveLengthIsRejected() {
        // Integer.MAX_VALUE would be a 2 GiB allocation → OutOfMemoryError
        assertThrows(IOException.class, () -> SocketClient.checkFrameSize(Integer.MAX_VALUE, MAX));
    }

    @Test
    void testSignBitOnlyLengthIsRejected() {
        // 0x80000000 is the other dangerous pattern: negative as a signed int, but
        // 2147483648 unsigned — a 2 GiB allocation if it ever got through
        IOException e =
                assertThrows(
                        IOException.class,
                        () -> SocketClient.checkFrameSize(Integer.MIN_VALUE, MAX));
        assertTrue(e.getMessage().contains("2147483648"), e.getMessage());
    }

    @Test
    void testCustomLimitIsApplied() {
        // a frame far below the default cap is still rejected when the client was
        // built with a smaller limit of its own
        IOException e =
                assertThrows(IOException.class, () -> SocketClient.checkFrameSize(2048, 1024));
        assertTrue(e.getMessage().contains("max 1024 bytes"), e.getMessage());
        assertDoesNotThrow(() -> SocketClient.checkFrameSize(1024, 1024));
    }

    @Test
    void testNonPositiveMaxFrameSizeIsRejectedByTheConstructor() {
        ISocketProvider provider = org.mockito.Mockito.mock(ISocketProvider.class);
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SocketClient(
                                        provider,
                                        HealthCheckConfig.DEFAULT,
                                        Duration.ofSeconds(1),
                                        0));
        assertTrue(e.getMessage().contains("maxFrameSize"), e.getMessage());
    }
}
