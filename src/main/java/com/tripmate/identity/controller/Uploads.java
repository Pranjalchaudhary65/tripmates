package com.tripmate.identity.controller;

import com.tripmate.identity.service.VerificationProblem;
import com.tripmate.identity.upload.ImageSanitizer;
import com.tripmate.platform.error.ApiProblemException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Reads an uploaded part into memory only once its size is known to be
 * within the image limit. The multipart limit has already refused larger
 * requests before they were read; this repeats the check per part, so a
 * limit raised in configuration can never let a bigger image through.
 */
final class Uploads {

    private Uploads() {
    }

    static byte[] bytesOf(MultipartFile part) throws IOException {
        if (part == null || part.isEmpty() || part.getSize() > ImageSanitizer.MAX_BYTES) {
            throw new ApiProblemException(VerificationProblem.INVALID_IMAGE);
        }
        return part.getBytes();
    }
}
