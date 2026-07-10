package com.opensitesurvey.tool.wlan;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Raw JNA binding of the subset of {@code Wlanapi.dll} (Native Wifi API) this app needs. */
interface WlanApi extends StdCallLibrary {

    WlanApi INSTANCE = Native.load("Wlanapi", WlanApi.class, W32APIOptions.DEFAULT_OPTIONS);

    int WLAN_API_VERSION_2_0 = 2;
    int DOT11_BSS_TYPE_ANY = 3;

    int WlanOpenHandle(int dwClientVersion, Pointer pReserved, IntByReference pdwNegotiatedVersion,
                        HANDLEByReference phClientHandle);

    int WlanCloseHandle(HANDLE hClientHandle, Pointer pReserved);

    int WlanEnumInterfaces(HANDLE hClientHandle, Pointer pReserved, PointerByReference ppInterfaceList);

    int WlanScan(HANDLE hClientHandle, GUID pInterfaceGuid, Dot11Ssid pDot11Ssid, Pointer pIeData,
                 Pointer pReserved);

    int WlanGetNetworkBssList(HANDLE hClientHandle, GUID pInterfaceGuid, Dot11Ssid pDot11Ssid,
                               int dot11BssType, boolean bSecurityEnabled, Pointer pReserved,
                               PointerByReference ppWlanBssList);

    void WlanFreeMemory(Pointer pMemory);
}
