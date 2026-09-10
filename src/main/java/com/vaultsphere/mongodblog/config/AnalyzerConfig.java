package com.vaultsphere.mongodblog.config;

import com.vaultsphere.mongodblog.parser.CompositeLogParser;
import com.vaultsphere.mongodblog.parser.LegacyLogParser;
import com.vaultsphere.mongodblog.parser.LogParser;
import com.vaultsphere.mongodblog.parser.QueryPatternNormalizer;
import com.vaultsphere.mongodblog.parser.StructuredLogParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalyzerConfig {
    @Bean
    public LogParser logParser() {
        QueryPatternNormalizer normalizer = new QueryPatternNormalizer();
        return new CompositeLogParser(
                new StructuredLogParser(normalizer),
                new LegacyLogParser(normalizer)
        );
    }
}

