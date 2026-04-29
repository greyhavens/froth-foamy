package com.threerings.froth.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-pipe Steam manual-dispatch driver. Responsible for delivering both the broadcast
 * {@code STEAM_CALLBACK} events (multi-listener "this happened" notifications) and the
 * one-shot {@code CCallResult} events (results from async API calls returning a
 * {@code SteamAPICall_t} handle).
 *
 * <p>One instance per Steam pipe: there is a client pipe (driven by {@link
 * com.threerings.froth.SteamAPI#runCallbacks}) and a game-server pipe (driven by {@link
 * com.threerings.froth.SteamGameServer#runCallbacks}). Callers register interest by
 * calling {@link #setBroadcastHandler} once per callback ID, and {@link
 * #registerCallResult} once per async call.
 *
 * <p>Not thread-safe with respect to {@link #runFrame}; callers must invoke {@code
 * runFrame} from a single thread (typically the game thread). The handler-registration
 * methods are safe to call from any thread.
 */
public final class CallbackDispatcher
{
  /** Standard callback ID for {@code SteamAPICallCompleted_t}: k_iSteamUtilsCallbacks + 3. */
  public static final int CB_SteamAPICallCompleted = 703;

  /**
   * Receives a broadcast callback. The supplied {@code paramSeg} is a MemorySegment
   * sized to the callback struct's {@code m_cubParam}; handlers should read fields by
   * offset using {@link CSteam}'s offset constants and the {@link ValueLayout} accessors.
   */
  @FunctionalInterface
  public interface CallbackHandler
  {
    void handle (MemorySegment paramSeg);
  }

  /**
   * Receives the result of an async API call. {@code resultSeg} contains the deserialized
   * result struct (allocated in a confined arena that lives only for the duration of the
   * call); {@code ioFailure} is true if the call failed at the network/IO layer.
   */
  @FunctionalInterface
  public interface CallResultHandler
  {
    void handle (MemorySegment resultSeg, boolean ioFailure);
  }

  /**
   * Construct a dispatcher driven by {@code getPipe} (returns the current HSteamPipe
   * handle on each call -- it can change after a re-init, so we read it lazily) and
   * {@code runFrame} (which runs the manual-dispatch frame on the pipe).
   *
   * @param name a short human-readable label used in error messages.
   */
  public CallbackDispatcher (String name, PipeAccessor getPipe)
  {
    _name = name;
    _getPipe = getPipe;
  }

  /** Fetches the current pipe handle for this dispatcher. */
  @FunctionalInterface
  public interface PipeAccessor { int get () throws Throwable; }

  /**
   * Register (or replace) the broadcast handler for the given callback ID. The Steam
   * API only delivers one instance of each callback per frame; we let callers fan out
   * to multiple Java listeners via their own list, mirroring froth's pattern.
   */
  public void setBroadcastHandler (int callbackId, CallbackHandler handler)
  {
    _handlers.put(callbackId, handler);
  }

  /**
   * Register a one-shot handler for an async API call. The handler fires (and is then
   * removed) when {@code SteamAPICallCompleted_t} arrives with a matching call handle.
   *
   * @param hCall the {@code SteamAPICall_t} handle returned by the API call.
   * @param expectedCallbackId the {@code k_iCallback} value of the expected result struct.
   * @param resultSize the size in bytes of the expected result struct.
   * @param handler invoked exactly once when the result lands.
   */
  public void registerCallResult (
    long hCall, int expectedCallbackId, int resultSize, CallResultHandler handler)
  {
    _callResults.put(hCall,
      new CallResultRegistration(expectedCallbackId, resultSize, handler));
  }

  /**
   * Run one dispatch frame. Called from {@code SteamAPI.runCallbacks} or {@code
   * SteamGameServer.runCallbacks}. Drains every pending callback on this pipe, invokes
   * registered handlers, and frees each callback in turn.
   */
  public void runFrame ()
  {
    if (!CSteam.LIB_LOADED) return;
    int pipe;
    try {
      pipe = (int) _getPipe.get();
    } catch (Throwable t) {
      return; // pipe inaccessible (uninitialized) -- nothing to dispatch
    }
    if (pipe == 0) return; // not yet initialized

    try {
      CSteam.SteamAPI_ManualDispatch_RunFrame.invokeExact(pipe);
    } catch (Throwable t) {
      throw rethrow("ManualDispatch_RunFrame", t);
    }

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment msg = arena.allocate(CSteam.CALLBACK_MSG_LAYOUT);
      while (true) {
        boolean got;
        try {
          got = (boolean) CSteam.SteamAPI_ManualDispatch_GetNextCallback
            .invokeExact(pipe, msg);
        } catch (Throwable t) {
          throw rethrow("ManualDispatch_GetNextCallback", t);
        }
        if (!got) break;

        int iCallback = msg.get(ValueLayout.JAVA_INT, CSteam.CB_OFFSET_ICALLBACK);
        int cubParam = msg.get(ValueLayout.JAVA_INT, CSteam.CB_OFFSET_CUBPARAM);
        MemorySegment pubParam = msg.get(
          ValueLayout.ADDRESS, CSteam.CB_OFFSET_PUBPARAM);

        try {
          if (iCallback == CB_SteamAPICallCompleted) {
            dispatchApiCallResult(pipe, pubParam, cubParam, arena);
          } else {
            dispatchBroadcast(iCallback, pubParam, cubParam);
          }
        } catch (Throwable handlerError) {
          // Don't let one bad handler break the whole pipe; report and keep
          // draining. Without a logger to depend on, dump to stderr -- this
          // mirrors what froth's native shim would do via JVM-default error
          // handling on a JNI exception.
          System.err.println("[froth-foamy] " + _name + " callback " + iCallback +
            " handler threw: " + handlerError);
          handlerError.printStackTrace();
        } finally {
          try {
            CSteam.SteamAPI_ManualDispatch_FreeLastCallback.invokeExact(pipe);
          } catch (Throwable t) {
            throw rethrow("ManualDispatch_FreeLastCallback", t);
          }
        }
      }
    }
  }

  private void dispatchBroadcast (int iCallback, MemorySegment pubParam, int cubParam)
  {
    CallbackHandler handler = _handlers.get(iCallback);
    if (handler == null) {
      return; // no listener registered -- safe to ignore
    }
    if (pubParam.equals(MemorySegment.NULL) || cubParam <= 0) {
      handler.handle(MemorySegment.NULL);
      return;
    }
    // The SDK gives us a pointer with zero size; reinterpret to the actual size so
    // the handler can read fields safely. The pointed-at memory lives only until
    // FreeLastCallback, so the handler must extract any data it needs synchronously.
    handler.handle(pubParam.reinterpret(cubParam));
  }

  private void dispatchApiCallResult (
    int pipe, MemorySegment pubParam, int cubParam, Arena callArena) throws Throwable
  {
    // The pubParam for SteamAPICallCompleted_t describes the original call we made.
    MemorySegment completedSeg = pubParam.reinterpret(cubParam);
    long hCall = completedSeg.get(ValueLayout.JAVA_LONG,
      CSteam.APICOMPLETED_OFFSET_HASYNCCALL);
    int innerCallback = completedSeg.get(ValueLayout.JAVA_INT,
      CSteam.APICOMPLETED_OFFSET_ICALLBACK);
    int innerSize = completedSeg.get(ValueLayout.JAVA_INT,
      CSteam.APICOMPLETED_OFFSET_CUBPARAM);

    CallResultRegistration reg = _callResults.remove(hCall);
    if (reg == null) {
      return; // we didn't register for this -- safe to ignore
    }
    if (reg.expectedCallbackId != innerCallback) {
      // Probably means a callback ID drift between SDK versions.
      System.err.println("[froth-foamy] " + _name + " unexpected result callback id " +
        innerCallback + " (expected " + reg.expectedCallbackId +
        " for call " + hCall + ")");
      return;
    }

    // Allocate a buffer for the actual result struct, then ask Steam to fill it in.
    MemorySegment resultBuf = callArena.allocate(innerSize);
    MemorySegment failedFlag = callArena.allocate(ValueLayout.JAVA_BOOLEAN);
    boolean ok = (boolean) CSteam.SteamAPI_ManualDispatch_GetAPICallResult.invokeExact(
      pipe, hCall, resultBuf, innerSize, innerCallback, failedFlag);
    boolean ioFailure = failedFlag.get(ValueLayout.JAVA_BOOLEAN, 0);

    if (!ok) {
      reg.handler.handle(resultBuf, true);
      return;
    }
    reg.handler.handle(resultBuf, ioFailure);
  }

  private RuntimeException rethrow (String op, Throwable t)
  {
    if (t instanceof RuntimeException re) return re;
    if (t instanceof Error err) throw err;
    return new RuntimeException(_name + ": " + op + " failed", t);
  }

  private record CallResultRegistration(
    int expectedCallbackId, int resultSize, CallResultHandler handler) {}

  private final String _name;
  private final PipeAccessor _getPipe;
  private final Map<Integer, CallbackHandler> _handlers = new ConcurrentHashMap<>();
  private final Map<Long, CallResultRegistration> _callResults = new ConcurrentHashMap<>();
}
