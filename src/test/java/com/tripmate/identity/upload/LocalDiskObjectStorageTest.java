package com.tripmate.identity.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskObjectStorageTest {

    @TempDir
    Path root;

    @Test
    void storesReadsAndDeletes() {
        LocalDiskObjectStorage storage = new LocalDiskObjectStorage(new StorageProperties(root.toString()));
        String key = MediaKind.PROFILE_PHOTO.newKey();

        storage.put(key, new byte[] {1, 2, 3});
        assertThat(storage.get(key)).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
        assertThat(Files.exists(root.resolve(key))).isTrue();

        storage.delete(key);
        storage.delete(key);
        assertThat(storage.get(key)).isEmpty();
    }

    @Test
    void aKeyIsNeverOverwritten() {
        LocalDiskObjectStorage storage = new LocalDiskObjectStorage(new StorageProperties(root.toString()));
        String key = MediaKind.SELFIE.newKey();
        storage.put(key, new byte[] {1});

        assertThatThrownBy(() -> storage.put(key, new byte[] {2})).isInstanceOf(RuntimeException.class);
        assertThat(storage.get(key)).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1));
    }

    @Test
    void keysThatCouldLeaveTheRootAreRefused() {
        LocalDiskObjectStorage storage = new LocalDiskObjectStorage(new StorageProperties(root.toString()));

        for (String key : new String[] {"../escape.jpg", "selfies/../../escape.jpg", "/etc/passwd",
                "selfies\\..\\x.jpg", "selfies/abc.jpg", "other/00000000-0000-0000-0000-000000000000.jpg"}) {
            assertThatThrownBy(() -> storage.get(key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(key, new byte[] {1})).as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
