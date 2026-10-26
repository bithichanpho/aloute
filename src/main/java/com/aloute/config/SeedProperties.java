package com.aloute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Typed configuration for opt-in demo data. Disabled unless explicitly enabled. */
@Component
@ConfigurationProperties(prefix = "app.seed")
public class SeedProperties {
    private boolean demoData = false;

    public boolean isDemoData() {
        return demoData;
    }

    public void setDemoData(boolean demoData) {
        this.demoData = demoData;
    }
}
