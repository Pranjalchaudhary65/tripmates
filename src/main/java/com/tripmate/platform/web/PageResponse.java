package com.tripmate.platform.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The one pagination envelope every list endpoint returns, so a client has a
 * single pager component. Spring's own Page is never serialised: its JSON
 * shape is an implementation detail that has changed between versions.
 */
public record PageResponse<T>(

        List<T> items,

        @Schema(description = "Zero-based", example = "0")
        int page,

        @Schema(example = "20")
        int size,

        long totalItems,

        int totalPages) {

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
