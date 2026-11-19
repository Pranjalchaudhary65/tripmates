package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A full set of backup codes, shown once. Only their hashes are kept, so
 * they cannot be shown again; each works once, in place of a TOTP code.
 */
public record BackupCodesResponse(

        @ArraySchema(schema = @Schema(example = "7KQ2M-X9PDA"), minItems = 10, maxItems = 10)
        List<String> backupCodes) {

    public BackupCodesResponse {
        backupCodes = List.copyOf(backupCodes);
    }

    @Override
    public String toString() {
        return "BackupCodesResponse[count=" + backupCodes.size() + "]";
    }
}
