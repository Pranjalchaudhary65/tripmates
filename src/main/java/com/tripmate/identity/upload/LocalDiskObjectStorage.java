package com.tripmate.identity.upload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Objects as files under {@code tripmate.storage.local-root}. Every key is
 * checked against {@link MediaKind}'s pattern and the resolved path against
 * the root, so no key, however it was built, can reach a file outside it.
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
public class LocalDiskObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalDiskObjectStorage(StorageProperties properties) {
        this.root = Path.of(properties.localRoot()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store an object", e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read an object", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete an object", e);
        }
    }

    private Path resolve(String key) {
        if (!MediaKind.isWellFormed(key)) {
            throw new IllegalArgumentException("Not an object key");
        }
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Object key escapes the storage root");
        }
        return file;
    }
}
