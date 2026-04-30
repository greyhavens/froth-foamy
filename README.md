# froth-foamy
Java API for the Steamworks SDK, implemented with Java's Foreign Function &amp; Memory API
(JEP 454, finalized in JDK 22).

Froth-Foamy is a drop-in replacement for the threerings/froth library: same package
(`com.threerings.froth`), same class names, same method signatures. Existing callers do
not need to change a single line of code. The reason it exists is that froth wraps the
Steamworks C/C++ API via JNI shim libraries that have to be cross-compiled per-platform;
froth-foamy talks directly to the Steam shared library via FFM, which means there are no
native build artefacts to ship from this project.

Pure Java. Java 25.

You will need to actually include the `steamworks_sdk` libraries in your project --
those are not included here. This was built against Steamworks SDK 1.64.

## Loading the Steam library

At init time (in `SteamAPI.init()`), this library calls `System.loadLibrary("steam_api")`
on Linux/macOS and `System.loadLibrary("steam_api64")` on Windows. As with froth, you are
responsible for putting the platform-appropriate Steam shared library somewhere on
`java.library.path` before initialization. Setting the system property
`com.threerings.froth.disable_steam_api=true` will avoid even trying to load the library.
