//
// $Id$

package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam user interface.
 */
public class SteamUser
{
    /** Result codes for {@link #getVoice} and {@link #decompressVoice}, etc. */
    public enum VoiceResult {
        OK, NOT_INITIALIZED, NOT_RECORDING, NO_DATA, BUFFER_TOO_SMALL,
        DATA_CORRUPTED, RESTRICTED, UNSUPPORTED_CODEC };

    /**
     * A callback interface for parties interested in server connection events.
     */
    public interface SteamServerCallback
    {
        /**
         * Called when a connection has been established to the Steam servers.
         */
        public void steamServersConnected ();

        /**
         * Called when we have lost connection to the Steam servers.
         */
        public void steamServersDisconnected ();
    }

    /**
     * A callback interface for parties interested in microtransaction authorization responses.
     */
    public interface MicroTxnCallback
    {
        /**
         * Called when the user accepts or denies a microtransaction request.
         */
        public void microTxnAuthorizationResponse (int appId, long orderId, boolean authorized);
    }

    /**
     * Adds a listener for Steam server callbacks.
     */
    public static void addSteamServerCallback (SteamServerCallback callback)
    {
        if (_steamServerCallbacks.isEmpty()) {
            // Wire the broadcast handlers on first use. They translate raw Steam callback
            // payloads into our ServerCallback methods, fanning out to all listeners.
            SteamAPI.dispatcher().setBroadcastHandler(CB_ServersConnected, seg -> {
                for (SteamServerCallback cb : _steamServerCallbacks) {
                    cb.steamServersConnected();
                }
            });
            SteamAPI.dispatcher().setBroadcastHandler(CB_ServersDisconnected, seg -> {
                for (SteamServerCallback cb : _steamServerCallbacks) {
                    cb.steamServersDisconnected();
                }
            });
        }
        _steamServerCallbacks.add(callback);
    }

    /**
     * Removes a Steam server callback listener.
     */
    public static void removeSteamServerCallback (SteamServerCallback callback)
    {
        _steamServerCallbacks.remove(callback);
    }

    /**
     * Adds a listener for microtransation callbacks.
     */
    public static void addMicroTxnCallback (MicroTxnCallback callback)
    {
        if (_microTxnCallbacks.isEmpty()) {
            SteamAPI.dispatcher().setBroadcastHandler(CB_MicroTxnAuthResponse, seg -> {
                int appId = seg.get(ValueLayout.JAVA_INT, CSteam.MICROTXN_OFFSET_APPID);
                long orderId = seg.get(ValueLayout.JAVA_LONG, CSteam.MICROTXN_OFFSET_ORDERID);
                boolean authorized = seg.get(
                    ValueLayout.JAVA_BYTE, CSteam.MICROTXN_OFFSET_AUTHORIZED) != 0;
                for (MicroTxnCallback cb : _microTxnCallbacks) {
                    cb.microTxnAuthorizationResponse(appId, orderId, authorized);
                }
            });
        }
        _microTxnCallbacks.add(callback);
    }

    /**
     * Removes a microtransaction callback listener.
     */
    public static void removeMicroTxnCallback (MicroTxnCallback callback)
    {
        _microTxnCallbacks.remove(callback);
    }

    /**
     * Checks whether we have a live and active connection to the Steam servers.
     */
    public static boolean isLoggedOn ()
    {
        try {
            return (boolean) CSteam.ISteamUser_BLoggedOn.invokeExact(self());
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Returns the user's steam ID.
     */
    public static long getSteamID ()
    {
        try {
            return (long) CSteam.ISteamUser_GetSteamID.invokeExact(self());
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Starts voice recording.
     */
    public static void startVoiceRecording ()
    {
        try {
            CSteam.ISteamUser_StartVoiceRecording.invokeExact(self());
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Stops voice recording.
     */
    public static void stopVoiceRecording ()
    {
        try {
            CSteam.ISteamUser_StopVoiceRecording.invokeExact(self());
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Determines how much voice data is currently available.
     */
    public static VoiceResult getAvailableVoice (
        IntBuffer compressed, IntBuffer uncompressed, int uncompressedDesiredSampleRate)
    {
        // Steam's GetAvailableVoice writes the compressed/uncompressed sizes into uint32*
        // out parameters. The original froth API used IntBuffers (direct, capacity >= 1).
        try {
            int rc = (int) CSteam.ISteamUser_GetAvailableVoice.invokeExact(
                self(),
                CSteam.ofBuffer(compressed),
                CSteam.ofBuffer(uncompressed),
                uncompressedDesiredSampleRate);
            return VoiceResult.values()[rc];
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Retrieves the currently recorded voice data.
     *
     * @param compressed the buffer to receive the compressed data, or null for none.
     * @param uncompressed the buffer to receive the uncompressed data, or null for none.
     */
    public static VoiceResult getVoice (
        ByteBuffer compressed, ByteBuffer uncompressed, int uncompressedDesiredSampleRate)
    {
        try (Arena arena = Arena.ofConfined()) {
            // The native API writes the actual byte counts into uint32* out params. We use
            // a tiny scratch segment for them, then mirror the count back into each buffer's
            // limit as the original JNI shim did.
            MemorySegment cLen = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment uLen = arena.allocate(ValueLayout.JAVA_INT);

            int rc = (int) CSteam.ISteamUser_GetVoice.invokeExact(
                self(),
                compressed != null,
                CSteam.ofBuffer(compressed),
                compressed != null ? compressed.capacity() : 0,
                cLen,
                uncompressed != null,
                CSteam.ofBuffer(uncompressed),
                uncompressed != null ? uncompressed.capacity() : 0,
                uLen,
                uncompressedDesiredSampleRate);

            if (rc == 0) { // k_EVoiceResultOK
                if (compressed != null) {
                    compressed.limit(cLen.get(ValueLayout.JAVA_INT, 0));
                }
                if (uncompressed != null) {
                    uncompressed.limit(uLen.get(ValueLayout.JAVA_INT, 0));
                }
            }
            return VoiceResult.values()[rc];
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Decompresses a block of voice data.
     */
    public static VoiceResult decompressVoice (
        ByteBuffer compressed, ByteBuffer dest, int desiredSampleRate)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dLen = arena.allocate(ValueLayout.JAVA_INT);
            int rc = (int) CSteam.ISteamUser_DecompressVoice.invokeExact(
                self(),
                CSteam.ofBuffer(compressed),
                compressed.limit(), // matches froth's getBufferLimit semantics
                CSteam.ofBuffer(dest),
                dest.capacity(),
                dLen,
                desiredSampleRate);
            if (rc == 0) {
                dest.limit(dLen.get(ValueLayout.JAVA_INT, 0));
            }
            return VoiceResult.values()[rc];
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Returns the optimal sample rate to use for {@link #decompressVoice}.
     */
    public static int getVoiceOptimalSampleRate ()
    {
        try {
            return (int) CSteam.ISteamUser_GetVoiceOptimalSampleRate.invokeExact(self());
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Requests an authentication ticket that can be used to verify our identity.
     *
     * @return the id of the generated ticket.
     */
    public static int getAuthSessionTicket (ByteBuffer ticket)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lenOut = arena.allocate(ValueLayout.JAVA_INT);
            // SteamNetworkingIdentity is optional and we don't supply one (matches froth).
            int hAuthTicket = (int) CSteam.ISteamUser_GetAuthSessionTicket.invokeExact(
                self(),
                CSteam.ofBuffer(ticket),
                ticket.capacity(),
                lenOut,
                MemorySegment.NULL);
            ticket.limit(lenOut.get(ValueLayout.JAVA_INT, 0));
            return hAuthTicket;
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Cancels a generated ticket.
     */
    public static void cancelAuthTicket (int ticketId)
    {
        try {
            CSteam.ISteamUser_CancelAuthTicket.invokeExact(self(), ticketId);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /** Cached ISteamUser interface pointer; populated lazily after API init. */
    private static MemorySegment self ()
    {
        MemorySegment s = _self;
        if (s == null) {
            s = SteamAPI.iface(CSteam.SteamAPI_SteamUser);
            _self = s;
        }
        return s;
    }

    /** Callback IDs from the Steamworks SDK headers (k_iSteamUserCallbacks = 100). */
    private static final int CB_ServersConnected     = 101;
    private static final int CB_ServersDisconnected  = 103;
    private static final int CB_MicroTxnAuthResponse = 152;

    /** Listeners for server connect/disconnect events. */
    private static final CopyOnWriteArrayList<SteamServerCallback> _steamServerCallbacks =
        new CopyOnWriteArrayList<>();

    /** Listeners for microtransaction auth events. */
    private static final CopyOnWriteArrayList<MicroTxnCallback> _microTxnCallbacks =
        new CopyOnWriteArrayList<>();

    private static volatile MemorySegment _self;
}
