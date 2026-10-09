package edu.svec.fams.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    /** Injectable clock so time-dependent logic (rate limits, windows) is testable. */
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
