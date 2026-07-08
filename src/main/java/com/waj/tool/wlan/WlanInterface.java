package com.waj.tool.wlan;

import com.sun.jna.platform.win32.Guid.GUID;
import com.waj.tool.i18n.Messages;

/** A wireless LAN adapter as enumerated by {@code WlanEnumInterfaces}. */
public record WlanInterface(GUID guid, String description, int stateCode) {

    public String stateLabel() {
        return switch (stateCode) {
            case 0 -> Messages.get("wlan.state.notReady");
            case 1 -> Messages.get("wlan.state.connected");
            case 2 -> Messages.get("wlan.state.adHocFormed");
            case 3 -> Messages.get("wlan.state.disconnecting");
            case 4 -> Messages.get("wlan.state.disconnected");
            case 5 -> Messages.get("wlan.state.associating");
            case 6 -> Messages.get("wlan.state.discovering");
            case 7 -> Messages.get("wlan.state.authenticating");
            default -> Messages.get("wlan.state.unknown", stateCode);
        };
    }
}
