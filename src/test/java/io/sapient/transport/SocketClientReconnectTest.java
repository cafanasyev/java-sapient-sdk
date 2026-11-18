package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.sapient.testing.FakeFusionNode;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** The client connects again after it loses a connection, or fails to get one. */
@Execution(ExecutionMode.CONCURRENT)
class SocketClientReconnectTest {

    /** NETCAT every 30 s, so no check fires during a test. */
    private static final HealthCheckConfig QUIET =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(30), Duration.ofMillis(100), 1);

    private final FakeFusionNode server = new FakeFusionNode();
    private final SocketClient client =
            new SocketClient(server.sockets(), QUIET, Duration.ofMillis(10));
    private final BlockingQueue<SapientMessage> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void subscribe() {
        client.subscribe(received::add);
    }

    @AfterEach
    void close() {
        client.close();
        server.close();
    }

    @Test
    void testReconnectAfterConnectionLost() throws Exception {
        client.start();
        server.awaitConnects(1);
        server.send(msg("msg1"));
        assertEquals(msg("msg1"), received.take());
        client.publish(msg("pub1"), Duration.ofSeconds(2));
        assertEquals(msg("pub1"), server.next());

        server.drop(); // EOF on read, so the client connects again

        server.awaitConnects(2);
        server.send(msg("msg2"));
        assertEquals(msg("msg2"), received.take());
        client.publish(msg("pub2"), Duration.ofSeconds(2));
        assertEquals(msg("pub2"), server.next());
    }

    @Test
    void testReconnectAfterConnectionFailure() throws Exception {
        server.refuseNextConnection();

        client.start();

        server.awaitConnects(1); // the second try
        server.send(msg("hello"));
        assertEquals(msg("hello"), received.take());
        client.publish(msg("world"), Duration.ofSeconds(2));
        assertEquals(msg("world"), server.next());
    }

    private static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }
}
