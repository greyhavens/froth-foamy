//
// $Id$

package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam networking interface.
 */
public class SteamNetworking
{
    /** The available P2P send types. */
    public enum P2PSend {
        UNRELIABLE, UNRELIABLE_NO_DELAY, RELIABLE, RELIABLE_WITH_BUFFERING };

    /**
     * Response codes for {@link #sendP2PPacket}.
     *
     * <p>Note: ordinals here are intentionally arranged so that {@code values()[errorCode]}
     * yields the right enum for the sparse Steam codes (0=None, 2=NoRights, 4=Timeout).
     * NOT_RUNNING_APP and DESTINATION_NOT_LOGGED_IN are placeholders preserved from the
     * original froth API for binary compatibility.
     */
    public enum P2PSessionError {
        NONE, NOT_RUNNING_APP, NO_RIGHTS_TO_APP, DESTINATION_NOT_LOGGED_IN, TIMEOUT };

    /**
     * A callback interface for parties interested in requests to establish P2P connections.
     */
    public interface P2PSessionRequestCallback
    {
        /**
         * Called when a P2P session is requested by the identified user.
         */
        public void p2pSessionRequest (long steamIdRemote);
    }

    /**
     * A callback interface for parties interested in failure to establish P2P connections.
     */
    public interface P2PSessionConnectCallback
    {
        /**
         * Called when we have failed to establish a P2P session in order to send a packet.
         */
        public void p2pSessionConnectFail (long steamIdRemote, P2PSessionError error);
    }

    /** The maximum size for unreliable messages (from the Steamworks documentation). */
    public static final int MAX_UNRELIABLE_SIZE = 1200;

    /** The maximum size for reliable messages. */
    public static final int MAX_RELIABLE_SIZE = 1048576;

    /**
     * Adds a listener for session request callbacks.
     */
    public static void addSessionRequestCallback (P2PSessionRequestCallback callback)
    {
        if (_sessionRequestCallbacks.isEmpty()) {
            SteamAPI.dispatcher().setBroadcastHandler(CB_P2PSessionRequest, seg -> {
                long remote = seg.get(ValueLayout.JAVA_LONG, CSteam.P2P_REQ_OFFSET_REMOTE);
                for (P2PSessionRequestCallback cb : _sessionRequestCallbacks) {
                    cb.p2pSessionRequest(remote);
                }
            });
        }
        _sessionRequestCallbacks.add(callback);
    }

    /**
     * Removes a session request callback listener.
     */
    public static void removeSessionRequestCallback (P2PSessionRequestCallback callback)
    {
        _sessionRequestCallbacks.remove(callback);
    }

    /**
     * Adds a listener for session connect callbacks.
     */
    public static void addSessionConnectCallback (P2PSessionConnectCallback callback)
    {
        if (_sessionConnectCallbacks.isEmpty()) {
            SteamAPI.dispatcher().setBroadcastHandler(CB_P2PSessionConnectFail, seg -> {
                long remote = seg.get(ValueLayout.JAVA_LONG, CSteam.P2P_FAIL_OFFSET_REMOTE);
                int err = seg.get(ValueLayout.JAVA_BYTE, CSteam.P2P_FAIL_OFFSET_ERROR) & 0xFF;
                P2PSessionError[] vals = P2PSessionError.values();
                P2PSessionError error = (err >= 0 && err < vals.length) ?
                    vals[err] : P2PSessionError.NONE;
                for (P2PSessionConnectCallback cb : _sessionConnectCallbacks) {
                    cb.p2pSessionConnectFail(remote, error);
                }
            });
        }
        _sessionConnectCallbacks.add(callback);
    }

    /**
     * Removes a session request connect listener.
     */
    public static void removeSessionConnectCallback (P2PSessionConnectCallback callback)
    {
        _sessionConnectCallbacks.remove(callback);
    }

    /**
     * Attempts to send a P2P packet to a remote user.
     */
    public static boolean sendP2PPacket (
        long steamIdRemote, ByteBuffer data, P2PSend sendType, int channel)
    {
        try {
            // Match froth's behavior: payload length is the buffer's current limit.
            return (boolean) CSteam.ISteamNetworking_SendP2PPacket.invokeExact(
                self(), steamIdRemote, CSteam.ofBuffer(data), data.limit(),
                sendType.ordinal(), channel);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Checks whether a P2P packet is available to read and (if so) fetches the size.
     */
    public static boolean isP2PPacketAvailable (IntBuffer msgSize, int channel)
    {
        try {
            return (boolean) CSteam.ISteamNetworking_IsP2PPacketAvailable.invokeExact(
                self(), CSteam.ofBuffer(msgSize), channel);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Reads an incoming P2P packet.
     */
    public static boolean readP2PPacket (
        ByteBuffer dest, LongBuffer steamIdRemote, int channel)
    {
        try (Arena arena = Arena.ofConfined()) {
            // Steam writes the actual byte count into a uint32* out param. Native shim
            // sets the buffer's limit to that count on success.
            MemorySegment lenOut = arena.allocate(ValueLayout.JAVA_INT);
            boolean ok = (boolean) CSteam.ISteamNetworking_ReadP2PPacket.invokeExact(
                self(),
                CSteam.ofBuffer(dest),
                dest.capacity(),
                lenOut,
                CSteam.ofBuffer(steamIdRemote),
                channel);
            if (ok) {
                dest.limit(lenOut.get(ValueLayout.JAVA_INT, 0));
            }
            return ok;
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Accepts a P2P session with a user.
     */
    public static boolean acceptP2PSessionWithUser (long steamIdRemote)
    {
        try {
            return (boolean) CSteam.ISteamNetworking_AcceptP2PSessionWithUser.invokeExact(
                self(), steamIdRemote);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Closes a P2P session with a user.
     */
    public static boolean closeP2PSessionWithUser (long steamIdRemote)
    {
        try {
            return (boolean) CSteam.ISteamNetworking_CloseP2PSessionWithUser.invokeExact(
                self(), steamIdRemote);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Closes a P2P channel with a user.
     */
    public static boolean closeP2PChannelWithUser (long steamIdRemote, int channel)
    {
        try {
            return (boolean) CSteam.ISteamNetworking_CloseP2PChannelWithUser.invokeExact(
                self(), steamIdRemote, channel);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    private static MemorySegment self ()
    {
        MemorySegment s = _self;
        if (s == null) {
            s = SteamAPI.iface(CSteam.SteamAPI_SteamNetworking);
            _self = s;
        }
        return s;
    }

    /** Callback IDs (k_iSteamNetworkingCallbacks = 1200). */
    private static final int CB_P2PSessionRequest     = 1202;
    private static final int CB_P2PSessionConnectFail = 1203;

    private static final CopyOnWriteArrayList<P2PSessionRequestCallback> _sessionRequestCallbacks =
        new CopyOnWriteArrayList<>();

    private static final CopyOnWriteArrayList<P2PSessionConnectCallback> _sessionConnectCallbacks =
        new CopyOnWriteArrayList<>();

    private static volatile MemorySegment _self;
}
