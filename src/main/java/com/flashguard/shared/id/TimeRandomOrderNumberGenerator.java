package com.flashguard.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

@Component
public class TimeRandomOrderNumberGenerator implements OrderNumberGenerator {

    private static final char[] CROCKFORD_BASE32 =
        "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIMESTAMP_LENGTH = 10;
    private static final int RANDOM_LENGTH = 16;

    private final Clock clock;
    private final RandomGenerator random;

    public TimeRandomOrderNumberGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    TimeRandomOrderNumberGenerator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    @Override
    public String next() {
        char[] value = new char[TIMESTAMP_LENGTH + RANDOM_LENGTH];
        long timestamp = clock.millis();
        for (int index = TIMESTAMP_LENGTH - 1; index >= 0; index--) {
            value[index] = CROCKFORD_BASE32[(int) (timestamp & 31)];
            timestamp >>>= 5;
        }
        for (int index = TIMESTAMP_LENGTH; index < value.length; index++) {
            value[index] = CROCKFORD_BASE32[random.nextInt(CROCKFORD_BASE32.length)];
        }
        return new String(value);
    }
}
