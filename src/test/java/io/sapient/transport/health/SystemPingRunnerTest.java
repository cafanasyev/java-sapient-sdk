package io.sapient.transport.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class SystemPingRunnerTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testHungProcessIsKilledAtTheTimeout() {
        // sleep stands in for a ping that does not exit on its own
        long started = System.nanoTime();
        assertFalse(new SystemPingRunner().run(List.of("sleep", "5"), Duration.ofMillis(300)));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(elapsedMillis < 650, "the check took " + elapsedMillis + " ms");
    }

    @Test
    void testLinuxCommandUsesWholeSeconds() {
        List<String> cmd = SystemPingRunner.command("Linux", "10.0.0.1", Duration.ofMillis(2500));
        assertEquals(List.of("ping", "-c", "1", "-W", "3", "10.0.0.1"), cmd);
    }

    @Test
    void testLinuxTimeoutRoundsUpToAtLeastOneSecond() {
        // -W takes whole seconds on Linux; 0 would mean "no timeout" on some builds
        List<String> cmd = SystemPingRunner.command("Linux", "10.0.0.1", Duration.ofMillis(200));
        assertEquals(List.of("ping", "-c", "1", "-W", "1", "10.0.0.1"), cmd);
    }

    @Test
    void testMacCommandUsesMillis() {
        List<String> cmd =
                SystemPingRunner.command("Mac OS X", "10.0.0.1", Duration.ofMillis(2500));
        assertEquals(List.of("ping", "-c", "1", "-W", "2500", "10.0.0.1"), cmd);
    }

    @Test
    void testWindowsCommandUsesNAndW() {
        List<String> cmd =
                SystemPingRunner.command("Windows 11", "10.0.0.1", Duration.ofMillis(2500));
        assertEquals(List.of("ping", "-n", "1", "-w", "2500", "10.0.0.1"), cmd);
    }

    @Test
    void testUnknownOsFallsBackToLinuxFlags() {
        List<String> cmd = SystemPingRunner.command("Plan 9", "10.0.0.1", Duration.ofSeconds(2));
        assertEquals(List.of("ping", "-c", "1", "-W", "2", "10.0.0.1"), cmd);
    }

    @Test
    void testLoopbackAnswers() {
        // the only test here that really spawns a process. Loopback always answers ICMP.
        assertTrue(new SystemPingRunner().ping("127.0.0.1", Duration.ofSeconds(3)));
    }
}
