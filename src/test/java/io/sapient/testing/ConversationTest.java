package io.sapient.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

@Execution(ExecutionMode.CONCURRENT)
class ConversationTest {

    private final Conversation<SapientMessage> conversation = new Conversation<>();

    @Test
    void expectReadsTheNextLinesInOrder() {
        add("a", "b");

        conversation.expect("a", "b");
        conversation.expectNothingMore();
    }

    @Test
    void expectFailsAtTheFirstDifferentLine() {
        add("a", "c");

        var error = assertThrows(AssertionError.class, () -> conversation.expect("a", "b"));
        assertEquals("expected <b> but got <c>; conversation: [a, c]", error.getMessage());
    }

    @Test
    void awaitSkipsToTheFirstMatchAndReturnsItsMessage() {
        var b = SapientMessage.newBuilder().setNodeId("b").build();
        add("a");
        conversation.add("b", b);
        add("c");

        assertSame(b, conversation.await("b"));
        conversation.expect("c");
    }

    @Test
    void expectWaitsForALineAddedLater() throws Exception {
        var reader = Async.run(() -> conversation.expect("late"));
        reader.awaitParkedOrDone();

        add("late");
        reader.join();
    }

    @Test
    void nextReturnsTheMessage() {
        var message = SapientMessage.newBuilder().setNodeId("n1").build();
        conversation.add("a", message);

        assertSame(message, conversation.next());
    }

    @Test
    void expectNothingMoreFailsOnAnUnreadLine() {
        add("a");

        var error = assertThrows(AssertionError.class, conversation::expectNothingMore);
        assertEquals("expected nothing more but got <a>; conversation: [a]", error.getMessage());
    }

    @Test
    void concurrentAddsKeepEveryLine() throws Exception {
        Async[] writers =
                IntStream.range(0, 8)
                        .mapToObj(writer -> Async.run(() -> addMany(writer, 500)))
                        .toArray(Async[]::new);
        Async.joinAll(writers);

        assertEquals(4000, Set.copyOf(conversation.lines()).size());
    }

    @Test
    void interruptedWaitSaysWhatItWaitedFor() {
        add("a");
        var reader = Async.run(() -> conversation.expect("a", "x"));
        reader.awaitParkedOrDone();

        reader.interrupt();
        assertEquals("still waiting for <x>; conversation: [a]", reader.joinFailure().getMessage());
    }

    private void add(String... lines) {
        for (String line : lines) {
            conversation.add(line, SapientMessage.getDefaultInstance());
        }
    }

    private void addMany(int writer, int count) {
        for (int i = 0; i < count; i++) {
            add(writer + ":" + i);
        }
    }
}
