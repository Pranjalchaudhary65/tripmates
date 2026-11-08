package com.tripmate.identity.dto;

import com.tripmate.identity.entity.Gender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Shape and length rules only. Rules that need the clock (18+) or the
 * outside world (breach corpus) live in the service, and are checked there
 * before the address is looked up.
 *
 * <p>The password limit is 128 characters: long enough for any passphrase
 * or password manager, short enough that nobody can make Argon2id chew on a
 * megabyte-long input.
 */
public record RegistrationRequest(

        @Schema(example = "asha.verma@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(example = "violet-kettle-harbour-91", minLength = 12, maxLength = 128)
        @NotNull @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
        String password,

        @Schema(example = "Asha Verma")
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
        String fullName,

        @Schema(example = "2000-04-15")
        @NotNull @Past
        LocalDate dateOfBirth,

        @NotNull
        Gender gender) {

    /** Records print every component by default; this one holds a password. */
    @Override
    public String toString() {
        return "RegistrationRequest[dateOfBirth=" + dateOfBirth + ", gender=" + gender + "]";
    }
}
