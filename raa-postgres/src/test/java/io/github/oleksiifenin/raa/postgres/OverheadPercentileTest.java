package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** The nearest-rank percentile behind the numbers of {@link OverheadBenchmarkTest}; runs in every build. */
class OverheadPercentileTest {

    @Test
    void nearestRank_onOneHundredAndTenThousandValues() {
        long[] hundred = LongStream.rangeClosed(1, 100).toArray();
        long[] tenThousand = LongStream.rangeClosed(1, 10_000).toArray();

        assertThat(OverheadBenchmarkTest.percentile(hundred, 50)).isEqualTo(50);
        assertThat(OverheadBenchmarkTest.percentile(hundred, 99)).isEqualTo(99);
        assertThat(OverheadBenchmarkTest.percentile(tenThousand, 99)).isEqualTo(9_900);
        assertThat(OverheadBenchmarkTest.percentile(tenThousand, 90)).isEqualTo(9_000);
    }

    @Test
    void nearestRank_ofASingleValue_isThatValue() {
        assertThat(OverheadBenchmarkTest.percentile(new long[] {7}, 50)).isEqualTo(7);
        assertThat(OverheadBenchmarkTest.percentile(new long[] {7}, 99)).isEqualTo(7);
    }

    @Test
    void nearestRank_roundsTheRankUp() {
        long[] three = {10, 20, 30};

        assertThat(OverheadBenchmarkTest.percentile(three, 50)).isEqualTo(20); // ceil(1.5) = 2
        assertThat(OverheadBenchmarkTest.percentile(three, 99)).isEqualTo(30);
    }
}
