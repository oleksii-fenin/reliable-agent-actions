package io.github.oleksiifenin.raa.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JvmProcessTest {

    /** A child that passes every point except the armed one, where it stops. */
    public static final class Child {
        public static void main(String[] args) {
            FaultInjector injector = new FaultInjector(FaultPoint.valueOf(args[0]), System.out);
            for (FaultPoint point : FaultPoint.values()) {
                System.out.println("passing " + point);
                injector.reach(point);
            }
            System.out.println("done");
        }
    }

    @Test
    void childStopsAtItsArmedPoint_andIsKilledThere_asAJvmWithoutWrapper() {
        try (JvmProcess child = JvmProcess.start(Child.class, List.of("AFTER_RESPONSE_BEFORE_RECORD"))) {
            String marker = child.awaitLine(FaultInjector.MARKER, Duration.ofSeconds(20));

            assertThat(marker).isEqualTo(FaultInjector.MARKER + "AFTER_RESPONSE_BEFORE_RECORD");
            assertThat(child.command()).hasValueSatisfying(command -> assertThat(command).endsWith("/java"));
            assertThat(child.kill()).isEqualTo(JvmProcess.KILLED_BY_SIGKILL);
            assertThat(child.output()).contains("passing AFTER_CLAIM", "passing AFTER_RESPONSE_BEFORE_RECORD")
                    .doesNotContain("passing AFTER_RECORD_BEFORE_MEMORY", "done");
        }
    }

    @Test
    void awaitLine_failsWithTheOutput_whenTheChildEndsFirst() {
        try (JvmProcess child = JvmProcess.start(Child.class, List.of("AFTER_CLAIM"))) {
            child.awaitLine(FaultInjector.MARKER, Duration.ofSeconds(20));
            child.kill();

            assertThatThrownBy(() -> child.awaitLine("never printed", Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ended before")
                    .hasMessageContaining("passing AFTER_CLAIM");
        }
    }
}
