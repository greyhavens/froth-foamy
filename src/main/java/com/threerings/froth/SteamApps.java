//
// $Id$

package com.threerings.froth;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CopyOnWriteArrayList;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam apps interface.
 */
public class SteamApps
{
    /**
     * A callback interface for parties interested in DLC access.
     */
    public interface DlcInstalledCallback
    {
        /**
         * Called when the used purchases a new piece of DLC.
         */
        public void dlcInstalled (int appId);
    }

    /**
     * Adds a listener for the dlc callbacks.
     */
    public static void addDlcInstalledCallback (DlcInstalledCallback callback)
    {
        if (_dlcInstalledCallbacks.isEmpty()) {
            SteamAPI.dispatcher().setBroadcastHandler(CB_DlcInstalled, seg -> {
                int appId = seg.get(ValueLayout.JAVA_INT, CSteam.DLC_OFFSET_APPID);
                for (DlcInstalledCallback cb : _dlcInstalledCallbacks) {
                    cb.dlcInstalled(appId);
                }
            });
        }
        _dlcInstalledCallbacks.add(callback);
    }

    /**
     * Removes a dlc callback listener.
     */
    public static void removeDlcInstalledCallback (DlcInstalledCallback callback)
    {
        _dlcInstalledCallbacks.remove(callback);
    }

    /**
     * Returns the game's current language code.
     */
    public static String getCurrentGameLanguage ()
    {
        try {
            MemorySegment ptr = (MemorySegment) CSteam.ISteamApps_GetCurrentGameLanguage
                .invokeExact(self());
            return CSteam.readCString(ptr);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Returns true of the dlc is installed.
     */
    public static boolean isDlcInstalled (int appId)
    {
        try {
            return (boolean) CSteam.ISteamApps_BIsDlcInstalled.invokeExact(self(), appId);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    private static MemorySegment self ()
    {
        MemorySegment s = _self;
        if (s == null) {
            s = SteamAPI.iface(CSteam.SteamAPI_SteamApps);
            _self = s;
        }
        return s;
    }

    /** Callback ID (k_iSteamAppsCallbacks = 1000). */
    private static final int CB_DlcInstalled = 1005;

    private static final CopyOnWriteArrayList<DlcInstalledCallback> _dlcInstalledCallbacks =
        new CopyOnWriteArrayList<>();

    private static volatile MemorySegment _self;
}
