package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanJni;
import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

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

    @Test
    void windowsSelectionUsesHotSpotUseAvx() throws Exception {
        String oldOs = System.getProperty("os.name");
        String oldArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");

            String selected = HyperscanNativeLoader.selectPlatform();
            assertThat(selected).isEqualTo(readHotSpotOption("UseAVX") >= 2
                    ? "windows-x86_64"
                    : "windows-x86_64-baseline");
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("os.arch", oldArch);
        }
    }

    private static int readHotSpotOption(String option) {
        try {
            Object value = ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption",
                    new Object[]{option},
                    new String[]{String.class.getName()}
            );
            Object optionValue = ((CompositeData) value).get("value");
            return Integer.parseInt(optionValue.toString());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
