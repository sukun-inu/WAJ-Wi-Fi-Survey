package com.waj.tool.wlan;

/**
 * Thrown when Windows denies a Wi-Fi scan call with {@code ERROR_ACCESS_DENIED}.
 *
 * <p>Since Windows 11 (build 25977+), {@code WlanScan} / {@code WlanGetNetworkBssList} require
 * the user to have granted precise location access, because a BSS list can be used to derive
 * device location. See:
 * https://learn.microsoft.com/en-us/windows/win32/nativewifi/wi-fi-access-location-changes
 */
public class WlanAccessDeniedException extends WlanException {
    public WlanAccessDeniedException(String message) {
        super(message);
    }
}
