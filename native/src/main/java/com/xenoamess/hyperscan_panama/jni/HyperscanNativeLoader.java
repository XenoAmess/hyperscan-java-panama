package com.xenoamess.hyperscan_panama.jni;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads the native Hyperscan/VectorScan libraries required by the Panama bindings.
 * Selects the best ISA variant for the current CPU and extracts the libraries
 * from the classpath to a temporary directory where they can be loaded with
 * {@link System#load(String)}.
 */
public final class HyperscanNativeLoader {

    private static final Set<String> LINUX_X86_64_BASELINE_FLAGS = new HashSet<>(
            Arrays.asList("sse4_2", "popcnt")
    );
    private static final Set<String> LINUX_X86_64_AVX2_FLAGS = new HashSet<>(
            Arrays.asList("avx2", "bmi2")
    );
    private static final Set<String> LINUX_ARM64_SVE2_FLAGS = new HashSet<>(
            Arrays.asList("sve2")
    );

    private static final String PLATFORM_PROPERTY = "com.xenoamess.hyperscan_panama.platform";
    private static final String RESOURCE_BASE = "com/xenoamess/hyperscan_panama/jni";

    private static volatile boolean loaded = false;
    private static volatile HyperscanJni jni;

    private HyperscanNativeLoader() {
    }

    /**
     * Loads the native libraries for the current platform if they have not been
     * loaded already. This method is idempotent and thread-safe.
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }

        String platform = System.getProperty(PLATFORM_PROPERTY);
        if (platform == null || platform.isEmpty()) {
            platform = selectPlatform();
        }

        if (platform == null) {
            throw new UnsatisfiedLinkError(
                    "Unable to determine a supported hyperscan platform for this system"
            );
        }

        Path tempDir = createTempDir();
        loadLibrary(platform, "hs", tempDir);
        loadLibrary(platform, "hs_runtime", tempDir);

        loaded = true;
    }

    /**
     * Loads the native libraries and the platform-specific {@link HyperscanJni}
     * implementation. This method is idempotent and thread-safe.
     *
     * @return the platform-specific HyperscanJni implementation
     */
    public static synchronized HyperscanJni loadJni() {
        if (jni == null) {
            load();

            String platform = System.getProperty(PLATFORM_PROPERTY);
            if (platform == null || platform.isEmpty()) {
                platform = selectPlatform();
            }
            if (platform == null) {
                throw new UnsatisfiedLinkError(
                        "Unable to determine a supported hyperscan platform for this system"
                );
            }

            String family = selectPlatformFamily(platform);
            String implClass = "com.xenoamess.hyperscan_panama.jni."
                    + family.replace('-', '_')
                    + ".HyperscanJniImpl";
            try {
                Class<?> cls = Class.forName(implClass);
                jni = (HyperscanJni) cls.getConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load HyperscanJni implementation for platform: " + platform,
                        e
                );
            }
        }
        return jni;
    }

    /**
     * Returns the platform family (e.g. {@code linux-x86_64}) for a full platform
     * identifier (e.g. {@code linux-x86_64-avx2}).
     */
    public static String selectPlatformFamily(String platform) {
        int lastDash = platform.lastIndexOf('-');
        int secondLastDash = platform.lastIndexOf('-', lastDash - 1);
        if (secondLastDash < 0) {
            return platform;
        }
        return platform.substring(0, lastDash);
    }

    /**
     * Selects the best platform variant based on the operating system and CPU
     * features. Returns {@code null} if the platform is not supported.
     */
    public static String selectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        boolean isLinux = os.contains("linux");
        boolean isWindows = os.contains("windows");
        boolean isMac = os.contains("mac");
        boolean isX86_64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (isLinux && isX86_64) {
            return selectLinuxX86_64Variant();
        }
        if (isLinux && isArm64) {
            return selectLinuxArm64Variant();
        }
        if (isWindows && isX86_64) {
            return selectWindowsX86_64Variant();
        }
        if (isMac && isX86_64) {
            return "macosx-x86_64";
        }
        if (isMac && isArm64) {
            return "macosx-arm64";
        }

        return null;
    }

    private static String selectLinuxX86_64Variant() {
        Set<String> flags = readLinuxCpuFlags();

        // Default to AVX2 even when AVX-512 is advertised: many virtualized or
        // containerized environments expose AVX-512 flags but do not reliably
        // execute AVX-512 instructions. Users who are sure their host supports
        // it can force the AVX-512 build via -Dcom.xenoamess.hyperscan_panama.platform=linux-x86_64.
        if (flags.containsAll(LINUX_X86_64_AVX2_FLAGS)) {
            return "linux-x86_64-avx2";
        }
        if (flags.containsAll(LINUX_X86_64_BASELINE_FLAGS)) {
            return "linux-x86_64-baseline";
        }
        return "linux-x86_64-baseline";
    }

    private static String selectLinuxArm64Variant() {
        Set<String> flags = readLinuxCpuFlags();

        if (flags.containsAll(LINUX_ARM64_SVE2_FLAGS)) {
            return "linux-arm64";
        }
        return "linux-arm64-baseline";
    }

    private static String selectWindowsX86_64Variant() {
        Set<String> flags = readWindowsCpuFlags();

        // Intel Hyperscan 5.4.2 does not provide a working AVX-512 MSVC build,
        // so we ship only baseline (SSE4.2-class) and AVX2 tiers. Both AVX2 and
        // AVX-512 capable hosts use the AVX2 build published as windows-x86_64.
        if (flags.containsAll(LINUX_X86_64_AVX2_FLAGS)) {
            return "windows-x86_64";
        }
        return "windows-x86_64-baseline";
    }

    private static Set<String> readWindowsCpuFlags() {
        Set<String> flags = new HashSet<>();
        String cpuIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (cpuIdentifier != null) {
            String lower = cpuIdentifier.toLowerCase();
            if (lower.contains("avx512vbmi")) {
                flags.add("avx512f");
                flags.add("avx512bw");
                flags.add("avx512vl");
                flags.add("avx512vbmi");
            } else if (lower.contains("avx512")) {
                flags.add("avx512f");
                flags.add("avx512bw");
                flags.add("avx512vl");
            } else if (lower.contains("avx2")) {
                flags.add("avx2");
                flags.add("bmi2");
            }
        }
        return flags;
    }

    private static Set<String> readLinuxCpuFlags() {
        Set<String> flags = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().startsWith("flags") || line.startsWith("Features")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0) {
                        String[] parts = line.substring(idx + 1).trim().split("\\s+");
                        flags.addAll(Arrays.asList(parts));
                    }
                    break;
                }
            }
        } catch (IOException e) {
            return flags;
        }
        return flags;
    }

    private static void loadLibrary(String platform, String name, Path tempDir) {
        String libName = System.mapLibraryName(name);
        String resource = RESOURCE_BASE + "/" + platform + "/" + libName;

        try (InputStream is = HyperscanNativeLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new UnsatisfiedLinkError("Native library not found on classpath: " + resource);
            }
            Path libFile = tempDir.resolve(libName);
            Files.copy(is, libFile, StandardCopyOption.REPLACE_EXISTING);
            libFile.toFile().deleteOnExit();
            System.load(libFile.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTempDir() {
        try {
            Path tempDir = Files.createTempDirectory("hyperscan-panama-");
            tempDir.toFile().deleteOnExit();
            return tempDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
