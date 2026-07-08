package com.waj.tool.wlan;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level wrapper around the native {@code Wlanapi.dll} handle lifecycle.
 *
 * <p>Not thread-safe: callers (see {@code WlanPoller}) must serialize access to a single
 * instance, e.g. by confining all calls to one background executor thread.
 */
public final class WlanClient implements AutoCloseable {

    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_ACCESS_DENIED = 5;

    /** Fixed header size of WLAN_INTERFACE_INFO_LIST / WLAN_BSS_LIST: two leading DWORDs. */
    private static final int LIST_HEADER_SIZE = 8;

    private final HANDLE handle;

    private WlanClient(HANDLE handle) {
        this.handle = handle;
    }

    public static WlanClient open() {
        HANDLEByReference phClientHandle = new HANDLEByReference();
        IntByReference negotiatedVersion = new IntByReference();
        int result = WlanApi.INSTANCE.WlanOpenHandle(
                WlanApi.WLAN_API_VERSION_2_0, null, negotiatedVersion, phClientHandle);
        checkResult(result, "WlanOpenHandle");
        return new WlanClient(phClientHandle.getValue());
    }

    public List<WlanInterface> listInterfaces() {
        PointerByReference ppList = new PointerByReference();
        checkResult(WlanApi.INSTANCE.WlanEnumInterfaces(handle, null, ppList), "WlanEnumInterfaces");
        Pointer base = ppList.getValue();
        try {
            // WLAN_INTERFACE_INFO_LIST { DWORD dwNumberOfItems; DWORD dwIndex; WLAN_INTERFACE_INFO[1]; }
            int count = base.getInt(0);
            int entrySize = new WlanInterfaceInfo().size();
            List<WlanInterface> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                WlanInterfaceInfo info = new WlanInterfaceInfo(base.share((long) LIST_HEADER_SIZE + (long) i * entrySize));
                info.read();
                // info.interfaceGuid is a by-value nested Structure backed by a slice of `base`,
                // which WlanFreeMemory releases below - copy it so callers can keep using the
                // GUID (e.g. in later scan()/getBssList() calls) after this method returns.
                result.add(new WlanInterface(new GUID(info.interfaceGuid), info.description(), info.isState));
            }
            return result;
        } finally {
            WlanApi.INSTANCE.WlanFreeMemory(base);
        }
    }

    /** Requests a fresh over-the-air scan. Asynchronous on the driver side; results land in the BSS cache shortly after. */
    public void scan(GUID interfaceGuid) {
        checkResult(WlanApi.INSTANCE.WlanScan(handle, interfaceGuid, null, null, null), "WlanScan");
    }

    /** Reads the driver's current (cached) BSS list - cheap, does not itself trigger an over-the-air scan. */
    public List<ApSnapshot> getBssList(GUID interfaceGuid) {
        PointerByReference ppBssList = new PointerByReference();
        checkResult(WlanApi.INSTANCE.WlanGetNetworkBssList(
                handle, interfaceGuid, null, WlanApi.DOT11_BSS_TYPE_ANY, false, null, ppBssList),
                "WlanGetNetworkBssList");
        Pointer base = ppBssList.getValue();
        try {
            // WLAN_BSS_LIST { DWORD dwTotalSize; DWORD dwNumberOfItems; WLAN_BSS_ENTRY[1]; }
            int count = base.getInt(4);
            int entrySize = new WlanBssEntry().size();
            Instant now = Instant.now();
            List<ApSnapshot> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                WlanBssEntry entry = new WlanBssEntry(base.share((long) LIST_HEADER_SIZE + (long) i * entrySize));
                entry.read();
                // Copy the IE blob out of native memory now - entry.getPointer() is backed by
                // `base`, which WlanFreeMemory releases below, and ApSnapshot.from() may still
                // need these bytes for security classification after this method returns.
                byte[] ie = readIeBytes(entry);
                result.add(ApSnapshot.from(entry, ie, now));
            }
            return result;
        } finally {
            WlanApi.INSTANCE.WlanFreeMemory(base);
        }
    }

    /** IE blob max per the WLAN_BSS_ENTRY docs is 2324 bytes; anything larger is treated as corrupt. */
    private static byte[] readIeBytes(WlanBssEntry entry) {
        int size = entry.ulIeSize;
        if (size <= 0 || size > 4096 || entry.ulIeOffset < 0) {
            return new byte[0];
        }
        try {
            return entry.getPointer().getByteArray(entry.ulIeOffset, size);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    @Override
    public void close() {
        WlanApi.INSTANCE.WlanCloseHandle(handle, null);
    }

    private static void checkResult(int result, String call) {
        if (result == ERROR_SUCCESS) {
            return;
        }
        if (result == ERROR_ACCESS_DENIED) {
            throw new WlanAccessDeniedException(Messages.get("wlan.error.accessDenied", call));
        }
        throw new WlanException(call + " failed, Win32 error code " + result);
    }
}
