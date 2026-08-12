package io.sapient.transport.health;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Spawns the operating system {@code ping} binary and reads its exit code.
 *
 * <p>We do not use {@code InetAddress.isReachable}. Its javadoc says it uses ICMP "if the privilege
 * can be obtained, otherwise it will try to establish a TCP connection on port 7 (Echo)".
 * Unprivileged on Linux that means it probes a port nothing listens on and calls a healthy host
 * dead. The {@code ping} binary carries {@code cap_net_raw} or the setuid bit, so it works without
 * root, and the same approach works in the Python SDK.
 */
@Slf4j
public class SystemPingRunner implements IPingRunner {

    /**
     * Builds the command line for one echo request.
     *
     * <p>The timeout flag is not portable: Linux {@code -W} takes whole seconds, macOS {@code -W}
     * takes milliseconds, Windows uses {@code -n} and {@code -w} in milliseconds. An unknown OS
     * gets the Linux form.
     *
     * @param osName value of the {@code os.name} system property
     * @param host hostname or IP address
     * @param timeout how long to wait for the reply
     * @return the command and its arguments
     */
    static List<String> command(String osName, String host, Duration timeout) {
        String os = osName.toLowerCase(Locale.ROOT);
        long millis = timeout.toMillis();
        if (os.startsWith("windows")) {
            return List.of("ping", "-n", "1", "-w", Long.toString(millis), host);
        }
        if (os.startsWith("mac") || os.contains("darwin")) {
            return List.of("ping", "-c", "1", "-W", Long.toString(millis), host);
        }
        // Linux and anything else: -W is whole seconds, and 0 means "no timeout" on some
        // builds, so round up and never go below 1
        long seconds = Math.max(1, (millis + 999) / 1000);
        return List.of("ping", "-c", "1", "-W", Long.toString(seconds), host);
    }

    @Override
    public boolean ping(String host, Duration timeout) {
        Process process = null;
        try {
            process =
                    new ProcessBuilder(command(System.getProperty("os.name", ""), host, timeout))
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            // the binary has its own timeout, but a hung process must not outlive the check
            if (!process.waitFor(timeout.toMillis() + 500, TimeUnit.MILLISECONDS)) {
                log.warn("ping to {} did not finish in time, killing it", host);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            log.error("failed to run ping for {}", host, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
