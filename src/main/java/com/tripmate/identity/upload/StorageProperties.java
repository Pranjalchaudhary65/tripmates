package com.tripmate.identity.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param localRoot directory the local object store writes under; outside
 *                  anything served statically, and git-ignored
 */
@ConfigurationProperties("tripmate.storage")
public record StorageProperties(String localRoot) {

    public StorageProperties {
        if (localRoot == null || localRoot.isBlank()) {
            throw new IllegalArgumentException("tripmate.storage.local-root is required");
        }
    }
}
