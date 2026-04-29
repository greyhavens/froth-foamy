package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam game server statistics interface.
 */
public class SteamGameServerStats
{
  /**
   * Sets a user achievement.
   */
  public static boolean setUserAchievement (long userSteamId, String name)
  {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment n = CSteam.allocCString(arena, name);
      return (boolean) CSteam.ISteamGameServerStats_SetUserAchievement.invokeExact(
        self(), userSteamId, n);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Clears a user achievement.
   */
  public static boolean clearUserAchievement (int userSteamId, String name)
  {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment n = CSteam.allocCString(arena, name);
      return (boolean) CSteam.ISteamGameServerStats_ClearUserAchievement.invokeExact(
        self(), (long) userSteamId, n);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  private static MemorySegment self ()
  {
    MemorySegment s = _self;
    if (s == null) {
      s = SteamAPI.iface(CSteam.SteamAPI_SteamGameServerStats);
      _self = s;
    }
    return s;
  }

  private static volatile MemorySegment _self;
}
