package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.TestConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage.ContentCase;

/**
 * Nodes started together do not send in lockstep: each waits a random part of a window first. These
 * tests measure real time on purpose. They read the spread from the timestamps the dispatcher put
 * on the messages.
 *
 * <p>Ten nodes, each at a uniform random point in the window, end up within a tenth of the window
 * of each other with a chance of about one in a hundred million. So a spread over a tenth of the
 * window is safe to demand.
 */
@Execution(ExecutionMode.CONCURRENT)
class JitterSpreadTest {

    private static final int NODES = 10;
    private static final Duration WINDOW = Duration.ofMillis(500);

    private final FakeFusionNode server = new FakeFusionNode();

    @Test
    void firstStatusReportsSpreadAcrossNodesWithSameInterval() {
        try (var dispatcher = server.connect(nodes())) {
            assertSpreadOverATenthOfTheWindow(firstSentAt(ContentCase.STATUS_REPORT));
        }
    }

    @Test
    void registrationsSpreadAcrossNodes() {
        var config = TestConfig.builder().withRegistrationJitterWindow(WINDOW).build();
        try (var dispatcher = server.connect(config, nodes())) {
            assertSpreadOverATenthOfTheWindow(firstSentAt(ContentCase.REGISTRATION));
        }
    }

    /** Nodes whose status interval is the window, so their first reports spread over it. */
    private static FakeEdgeNode[] nodes() {
        return IntStream.range(0, NODES)
                .mapToObj(i -> FakeEdgeNode.builder("n" + i).withStatusInterval(WINDOW).build())
                .toArray(FakeEdgeNode[]::new);
    }

    /** When each node sent its first message of {@code kind}. */
    private Collection<Instant> firstSentAt(ContentCase kind) {
        Map<String, Instant> first = new HashMap<>();
        while (first.size() < NODES) {
            SapientMessage message = server.next();
            if (message.getContentCase() == kind) {
                first.putIfAbsent(message.getNodeId(), sentAt(message.getTimestamp()));
            }
        }
        return first.values();
    }

    private static void assertSpreadOverATenthOfTheWindow(Collection<Instant> times) {
        Duration spread = Duration.between(Collections.min(times), Collections.max(times));
        assertTrue(spread.compareTo(WINDOW.dividedBy(10)) > 0, "spread " + spread);
    }

    private static Instant sentAt(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
