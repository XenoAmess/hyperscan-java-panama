package com.xenoamess.hyperscan_panama.wrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HyperscanExceptionTest {

    @ParameterizedTest
    @CsvSource({
            "-1, An invalid parameter has been passed. Is scratch allocated?",
            "-2, Hyperscan was unable to allocate memory",
            "-3, The engine was terminated by callback.",
            "-4, The pattern compiler failed.",
            "-5, The given database was built for a different version of Hyperscan.",
            "-6, The given database was built for a different platform.",
            "-7, The given database was built for a different mode of operation.",
            "-8, A parameter passed to this function was not correctly aligned.",
            "-9, The allocator did not return memory suitably aligned for the largest representable data type on this platform.",
            "-10, The scratch region was already in use.",
            "-11, Unsupported CPU architecture. At least SSE3 is needed",
            "-12, Provided buffer was too small."
    })
    void hsErrorToException_mapsKnownErrorCodes(int errorCode, String expectedMessage) {
        HyperscanException exception = HyperscanException.hsErrorToException(errorCode);
        assertThat(exception).hasMessage(expectedMessage);
    }

    @ParameterizedTest
    @ValueSource(ints = {-13, 0, 1, 999})
    void hsErrorToException_mapsUnknownErrorCodes(int errorCode) {
        HyperscanException exception = HyperscanException.hsErrorToException(errorCode);
        assertThat(exception).hasMessage("Unexpected error: " + errorCode);
    }

    @Test
    void constructorStoresMessage() {
        HyperscanException exception = new HyperscanException("custom message");
        assertThat(exception).hasMessage("custom message");
    }
}
