package com.tripmate.identity.controller;

import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.identity.upload.SignedMediaUrls;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves one image to whoever holds a valid signed link. No bearer token:
 * an {@code <img>} cannot send one, and the signature already says who the
 * server chose to show it to and until when. A bad, expired or tampered link
 * gets the same 404 as one that never existed.
 */
@RestController
@RequestMapping(MediaController.BASE)
@Tag(name = "Media", description = "Images behind short-lived signed links")
public class MediaController {

    public static final String BASE = "/media";

    public static final String BY_TOKEN = "/{token}";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final SignedMediaUrls mediaUrls;

    private final ObjectStorage storage;

    public MediaController(SignedMediaUrls mediaUrls, ObjectStorage storage) {
        this.mediaUrls = mediaUrls;
        this.storage = storage;
    }

    @GetMapping(BY_TOKEN)
    @Operation(summary = "Load an image from a signed link")
    @ApiResponse(responseCode = "200", description = "The image, as a JPEG",
            content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE))
    @ApiResponse(responseCode = "404", description = "not_found: invalid or expired link, or the image is gone",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<byte[]> image(@PathVariable String token) {
        byte[] image = mediaUrls.verify(token)
                .flatMap(storage::get)
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(image);
    }
}
