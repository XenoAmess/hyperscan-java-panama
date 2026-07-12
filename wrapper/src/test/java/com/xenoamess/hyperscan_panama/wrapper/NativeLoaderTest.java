package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanJni;
import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeLoaderTest {

    @Test
    void loadJni_returnsSameInstance() {
        HyperscanJni jni1 = HyperscanNativeLoader.loadJni();
        HyperscanJni jni2 = HyperscanNativeLoader.loadJni();
        HyperscanJni jni3 = HyperscanNativeLoader.loadJni();

        assertThat(jni1).isSameAs(jni2);
        assertThat(jni2).isSameAs(jni3);
    }

    @Test
    void selectPlatformFamily_extractsFamilyCorrectly() {
        assertThat(HyperscanNativeLoader.selectPlatformFamily("linux-x86_64-avx2")).isEqualTo("linux-x86_64");
        assertThat(HyperscanNativeLoader.selectPlatformFamily("linux-x86_64-baseline")).isEqualTo("linux-x86_64");
        assertThat(HyperscanNativeLoader.selectPlatformFamily("windows-x86_64")).isEqualTo("windows-x86_64");
        assertThat(HyperscanNativeLoader.selectPlatformFamily("linux-arm64")).isEqualTo("linux-arm64");
    }
}
