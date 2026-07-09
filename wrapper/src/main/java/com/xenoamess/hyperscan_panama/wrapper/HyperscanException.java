package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;

/**
 * Exception thrown when a Hyperscan operation fails.
 * This is a runtime exception that wraps native Hyperscan errors.
 */
public class HyperscanException extends RuntimeException {

    static {
        HyperscanNativeLoader.load();
    }

    /**
     * Constructs a new HyperscanException with the specified message.
     *
     * @param message The error message describing the exception
     */
    public HyperscanException(String message) {
        super(message);
    }

    /**
     * Converts a Hyperscan error code to a Java exception.
     * @param hsError Hyperscan error code
     * @return Java exception corresponding to the error code
     */
    static HyperscanException hsErrorToException(int hsError) {
        switch (hsError) {
            case -1:  return new HyperscanException("An invalid parameter has been passed. Is scratch allocated?");
            case -2:  return new HyperscanException("Hyperscan was unable to allocate memory");
            case -3:  return new HyperscanException("The engine was terminated by callback.");
            case -4:  return new HyperscanException("The pattern compiler failed.");
            case -5:  return new HyperscanException("The given database was built for a different version of Hyperscan.");
            case -6:  return new HyperscanException("The given database was built for a different platform.");
            case -7:  return new HyperscanException("The given database was built for a different mode of operation.");
            case -8:  return new HyperscanException("A parameter passed to this function was not correctly aligned.");
            case -9:  return new HyperscanException("The allocator did not return memory suitably aligned for the largest representable data type on this platform.");
            case -10: return new HyperscanException("The scratch region was already in use.");
            case -11: return new HyperscanException("Unsupported CPU architecture. At least SSE3 is needed");
            case -12: return new HyperscanException("Provided buffer was too small.");
            default:  return new HyperscanException("Unexpected error: " + hsError);
        }
    }
}
