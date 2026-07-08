package com.xenoamess.hyperscan.performance;

import com.gliwka.hyperscan.jni.HyperscanNativeLoader;
import com.gliwka.hyperscan.wrapper.Scanner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSelectionTest {

    @Test
    void platformIsValid() {
        assertThat(HyperscanNativeLoader.selectPlatform()).isNotBlank();
    }

    @Test
    void nativeLibraryLoads() {
        HyperscanNativeLoader.load();
        assertThat(Scanner.getIsValidPlatform()).isTrue();
        assertThat(Scanner.getVersion()).isNotEmpty();
    }

    @Test
    void explicitPlatformOverridesSelection() {
        String explicit = System.getProperty("com.xenoamess.hyperscan_panama.platform");
        if (explicit != null) {
            HyperscanNativeLoader.load();
            String actual = System.getProperty("com.xenoamess.hyperscan_panama.platform");
            assertThat(actual).isEqualTo(explicit);
        }
    }
}
