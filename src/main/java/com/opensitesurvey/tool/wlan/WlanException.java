package com.opensitesurvey.tool.wlan;

/** Thrown when a Wlanapi.dll call fails with a non-recoverable error code. */
public class WlanException extends RuntimeException {
    public WlanException(String message) {
        super(message);
    }
}
