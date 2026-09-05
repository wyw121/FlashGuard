package com.flashguard.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;

import org.junit.jupiter.api.Test;

class TimeRandomOrderNumberGeneratorTest {

    @Test
    void generatesFixedLengthSortableIdentifiers() {
        String earlier = generatorAt("2026-09-05T04:00:00Z").next();
        String later = generatorAt("2026-09-05T04:00:01Z").next();

        assertThat(earlier).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(later).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(earlier.compareTo(later)).isNegative();
    }

    @Test
    void randomSuffixChangesWithinTheSameMillisecond() {
        TimeRandomOrderNumberGenerator generator = generatorAt("2026-09-05T04:00:00Z");

        assertThat(generator.next()).isNotEqualTo(generator.next());
    }

    private TimeRandomOrderNumberGenerator generatorAt(String instant) {
        return new TimeRandomOrderNumberGenerator(
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
            new Random(42L)
        );
    }
}
