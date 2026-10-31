package com.example.demo.knowledgebase.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KbProperties.class)
public class KbConfig {
}
