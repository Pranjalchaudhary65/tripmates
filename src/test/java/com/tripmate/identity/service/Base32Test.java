package com.tripmate.identity.service;

import com.tripmate.support.TestAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Base32Test {

    /** RFC 4648 section 10, with the padding removed. */
    @ParameterizedTest
    @CsvSource({
            "'', ''", "f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI",
    })
    void matchesTheTestVectorsOfRfc4648(String input, String expected) {
        assertThat(Base32.encode(input.getBytes(StandardCharsets.US_ASCII))).isEqualTo(expected);
    }

    @Test
    void aTotpSecretIsThirtyTwoCharactersAndDecodesBackToItself() {
        byte[] secret = Totp.generateSecret();

        String encoded = Base32.encode(secret);

        assertThat(encoded).matches("[A-Z2-7]{32}");
        assertThat(TestAuthenticator.decodeBase32(encoded)).isEqualTo(secret);
    }
}
