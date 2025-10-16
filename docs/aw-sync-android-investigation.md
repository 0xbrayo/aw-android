# aw-sync Android Implementation Investigation

**Branch**: `investigate-aw-sync`
**Date**: 2025-10-16
**Status**: Phases 1-3 Complete (aw-server-rust), Phases 4-5 Pending (aw-android)

## Update: Implementation Progress

### ✅ Completed in aw-server-rust (dev/sync-jni branch)

**Phase 1: Library Preparation**
- Modified `aw-sync/Cargo.toml` to make CLI dependencies optional
- Added JNI dependency for Android target
- Created `aw-sync/src/android.rs` with JNI bindings
- Made `main.rs` conditional on `cli` feature
- Fixed `sync.rs` to conditionally import clap

**Phase 2: Android Build Integration**
- Updated `compile-android.sh` to build aw-sync alongside aw-server
- Configured to build with `--no-default-features` for Android

**Phase 3: JNI Implementation**
- Implemented 5 JNI functions in android.rs:
  - `syncPullAll()` - Pull from all hosts
  - `syncPull(hostname)` - Pull from specific host
  - `syncPush()` - Push local data
  - `syncBoth()` - Full bidirectional sync
  - `getSyncDir()` - Get sync directory path
- All functions return JSON responses with success/error status
- Build verified: `cargo check -p aw-sync --lib --no-default-features` ✅

**Commits:**
- `dc06dea` - feat(aw-sync): add Android JNI support and library build
- `b3fd7ef` - fix(aw-sync): make clap import and ValueEnum derive conditional

### 🔄 Next: Phases 4-5 in aw-android

These phases need to be implemented in the aw-android repository.

## Executive Summary

aw-sync can be made to work on Android, but requires several modifications:
1. Build aw-sync as a shared library for Android (currently only aw-server is built)
2. Add JNI bindings to expose sync functions to Java/Kotlin
3. Handle Android-specific storage paths
4. Remove CLI-specific dependencies that don't work on Android
5. Integrate into the Android app UI

## Current State

### What Works
- aw-server-rust successfully runs on Android as a native library via JNI
- aw-server-rust is compiled for all Android architectures (arm64, x86_64, x86, arm)
- The Android app has a working JNI interface (`RustInterface.kt`)
- aw-sync exists as a library and CLI tool in aw-server-rust

### What Doesn't Work Yet
- aw-sync is not built for Android (compile-android.sh only builds aw-server)
- aw-sync uses CLI-specific dependencies (clap, ctrlc) that don't work on Android
- aw-sync uses standard Unix directory conventions that don't match Android's storage model
- No JNI interface exists for aw-sync functionality

## Technical Analysis

### 1. Build System

**Current State**: `compile-android.sh` only builds aw-server

**Required Change**: Also build aw-sync as a library alongside aw-server

### 2. Directory Handling

**Issue**: aw-sync's `get_sync_dir()` uses `home_dir()` which doesn't work reliably on Android.

**Solution**: aw-sync already supports the `AW_SYNC_DIR` environment variable! We can set this from Android:
- Use `Os.setenv()` to set the sync directory before loading the library
- Point to Android-appropriate storage (internal or external app directory)

### 3. Dependency Issues

**Problematic Dependencies**:
- `clap` - CLI argument parsing (not needed for library)
- `ctrlc` - Signal handling (not needed for library)  
- `appdirs` - Directory resolution (can be worked around with env vars)

**Solution**: Make these dependencies optional and only compile them for the binary target.

### 4. JNI Interface Pattern

aw-server already demonstrates the pattern:
- Load library with `System.loadLibrary()`
- Declare external functions in Kotlin
- Call Rust functions via JNI
- Run blocking operations in background threads

## Implementation Plan

### Phase 1: Library Preparation
1. Modify `aw-sync/Cargo.toml` to make CLI dependencies optional
2. Create `aw-sync/src/lib.rs` that exports core sync functions
3. Add JNI bindings module for Android

### Phase 2: Android Build Integration  
1. Modify `compile-android.sh` to also build aw-sync
2. Copy `libaw_sync.so` to `jniLibs/` for each architecture
3. Verify build outputs

### Phase 3: JNI Implementation
1. Create Rust JNI functions in aw-sync for:
   - Pull sync from directory
   - Push sync to directory
   - List buckets and sync status
2. Create Kotlin wrapper class `SyncInterface.kt`
3. Handle sync operations in background threads

### Phase 4: Android App Integration
1. Add sync settings UI
2. Add manual sync button  
3. Implement periodic sync with WorkManager
4. Handle storage permissions
5. Show sync status/progress to user

### Phase 5: Testing & Polish
1. Test pull/push operations
2. Test with multiple devices
3. Add error handling and user feedback
4. Document setup process

## Key Code Changes Needed

### 1. aw-sync/Cargo.toml
Make CLI dependencies optional and add JNI support for Android.

### 2. aw-sync/src/lib.rs (new)
Export core sync functions and conditionally compile JNI bindings for Android.

### 3. aw-sync/src/android.rs (new)
JNI function implementations for pull, push, and status operations.

### 4. compile-android.sh
Add aw-sync to the build pipeline alongside aw-server.

### 5. SyncInterface.kt (new)
Kotlin wrapper following the same pattern as `RustInterface.kt`.

## Challenges & Considerations

### Storage Location
- **Internal Storage**: Private to app, more secure
- **External Storage**: Accessible to sync apps (Syncthing, etc.), requires permissions
- **Recommendation**: Use external storage for compatibility with sync tools

### Sync Triggers
- Manual sync via button (immediate)
- Periodic background sync with WorkManager (when conditions met)
- Respect battery optimization and network preferences

### Permissions Required
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`
- `INTERNET` (for potential cloud sync backends)

### Battery & Data Optimization
- Use WorkManager for intelligent scheduling
- Default to WiFi-only sync
- Respect battery optimization settings
- Show sync status to keep user informed

## Feasibility Assessment

**Overall: FEASIBLE**

The implementation follows the established pattern from aw-server integration. Key advantages:
- aw-sync is already modular and library-friendly
- Environment variable configuration already supported
- JNI pattern proven with aw-server
- No fundamental blockers identified

Main work areas:
1. Build system changes (straightforward)
2. JNI bindings (following existing pattern)
3. Android app integration (UI and background scheduling)

Estimated complexity: **Medium**
- Most complex part is proper JNI implementation and error handling
- UI integration is straightforward
- Testing with real sync scenarios will require setup

## Next Steps

1. **Prototype**: Build aw-sync for Android and verify library loads
2. **JNI Basics**: Implement minimal JNI bindings for one sync operation
3. **Integration**: Add basic UI to trigger sync manually
4. **Expand**: Add remaining operations and background sync
5. **Polish**: Error handling, status reporting, documentation

## References

- aw-server-rust Android integration: `/mobile/src/main/java/net/activitywatch/android/RustInterface.kt`
- aw-sync implementation: `/aw-server-rust/aw-sync/`
- Current build script: `/aw-server-rust/compile-android.sh`
- aw-sync README: `/aw-server-rust/aw-sync/README.md`

## Phase 4-5 Implementation Guide (aw-android)

### Prerequisites

1. Rebuild aw-server-rust with sync support:
   ```bash
   cd aw-server-rust
   git checkout dev/sync-jni
   ./compile-android.sh
   ```
   This will generate both `libaw_server.so` and `libaw_sync.so` for all architectures.

2. Copy the generated libraries to aw-android:
   ```bash
   # From aw-server-rust directory
   for arch in arm64-v8a armeabi-v7a x86 x86_64; do
     cp target/*/release/libaw_sync.so ../aw-android/mobile/src/main/jniLibs/$arch/
   done
   ```

### Phase 4: Android App Integration

#### Step 1: Create SyncInterface.kt

Create `mobile/src/main/java/net/activitywatch/android/SyncInterface.kt`:

```kotlin
package net.activitywatch.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

private const val TAG = "SyncInterface"

class SyncInterface(context: Context) {
    private val appContext: Context = context.applicationContext
    private val syncDir: String
    
    init {
        // Set sync directory to external storage for access by sync apps
        syncDir = appContext.getExternalFilesDir(null)?.absolutePath + "/ActivityWatchSync"
        Os.setenv("AW_SYNC_DIR", syncDir, true)
        
        // Create sync directory if it doesn't exist
        java.io.File(syncDir).mkdirs()
        
        System.loadLibrary("aw_sync")
        Log.i(TAG, "aw-sync initialized with sync dir: $syncDir")
    }
    
    // Native functions
    private external fun syncPullAll(port: Int): String
    private external fun syncPull(port: Int, hostname: String): String
    private external fun syncPush(port: Int): String
    private external fun syncBoth(port: Int): String
    external fun getSyncDir(): String
    
    // Async wrappers
    fun syncPullAllAsync(callback: (Boolean, String) -> Unit) {
        performSyncAsync("Pull All") {
            syncPullAll(5600)
        }.also { (success, message) ->
            callback(success, message)
        }
    }
    
    fun syncPushAsync(callback: (Boolean, String) -> Unit) {
        performSyncAsync("Push") {
            syncPush(5600)
        }.also { (success, message) ->
            callback(success, message)
        }
    }
    
    fun syncBothAsync(callback: (Boolean, String) -> Unit) {
        performSyncAsync("Full Sync") {
            syncBoth(5600)
        }.also { (success, message) ->
            callback(success, message)
        }
    }
    
    private fun performSyncAsync(operation: String, syncFn: () -> String): Pair<Boolean, String> {
        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        
        var result: Pair<Boolean, String> = Pair(false, "")
        
        executor.execute {
            try {
                val response = syncFn()
                val json = JSONObject(response)
                val success = json.getBoolean("success")
                val message = if (success) {
                    json.getString("message")
                } else {
                    json.getString("error")
                }
                result = Pair(success, message)
                
                handler.post {
                    Log.i(TAG, "$operation completed: $message")
                }
            } catch (e: Exception) {
                result = Pair(false, "Exception: ${e.message}")
                handler.post {
                    Log.e(TAG, "$operation failed", e)
                }
            }
        }
        
        return result
    }
    
    fun getSyncDirectory(): String = syncDir
}
```

#### Step 2: Add Sync UI

Create a sync fragment or add to settings. Example in `SettingsFragment.kt`:

```kotlin
// In your settings or main activity
private lateinit var syncInterface: SyncInterface

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    syncInterface = SyncInterface(requireContext())
    
    // Add sync buttons
    binding.btnSyncPull.setOnClickListener {
        showSyncProgress("Pulling data...")
        syncInterface.syncPullAllAsync { success, message ->
            hideSyncProgress()
            showSyncResult(success, message)
        }
    }
    
    binding.btnSyncPush.setOnClickListener {
        showSyncProgress("Pushing data...")
        syncInterface.syncPushAsync { success, message ->
            hideSyncProgress()
            showSyncResult(success, message)
        }
    }
    
    binding.btnSyncBoth.setOnClickListener {
        showSyncProgress("Syncing...")
        syncInterface.syncBothAsync { success, message ->
            hideSyncProgress()
            showSyncResult(success, message)
        }
    }
    
    // Display sync directory
    binding.txtSyncDir.text = "Sync Directory: ${syncInterface.getSyncDirectory()}"
}
```

#### Step 3: Add Storage Permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>
```

Request permissions at runtime for Android 6.0+.

### Phase 5: Background Sync with WorkManager

#### Step 1: Add WorkManager Dependency

In `build.gradle`:

```gradle
dependencies {
    implementation "androidx.work:work-runtime-ktx:2.8.1"
}
```

#### Step 2: Create SyncWorker

Create `mobile/src/main/java/net/activitywatch/android/workers/SyncWorker.kt`:

```kotlin
package net.activitywatch.android.workers

import android.content.Context
import androidx.work.*
import net.activitywatch.android.SyncInterface
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    
    override fun doWork(): Result {
        return try {
            val syncInterface = SyncInterface(applicationContext)
            
            // Perform sync operation
            syncInterface.syncBothAsync { success, message ->
                if (success) {
                    Log.i("SyncWorker", "Background sync successful: $message")
                } else {
                    Log.w("SyncWorker", "Background sync failed: $message")
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync error", e)
            Result.retry()
        }
    }
    
    companion object {
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
                .setRequiresBatteryNotLow(true)
                .build()
            
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "periodic_sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
        }
    }
}
```

#### Step 3: Schedule Background Sync

In your Application class or MainActivity:

```kotlin
override fun onCreate() {
    super.onCreate()
    
    // Schedule periodic sync
    if (PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("enable_background_sync", false)) {
        SyncWorker.schedulePeriodic(this)
    }
}
```

### Testing

1. **Manual Sync Test**:
   - Trigger manual sync from UI
   - Check logs for success/error messages
   - Verify files created in sync directory

2. **Multi-Device Test**:
   - Set up Syncthing or similar to sync the directory
   - Generate events on device A
   - Sync push from device A
   - Sync pull on device B
   - Verify events appear on device B

3. **Background Sync Test**:
   - Enable background sync
   - Wait for scheduled execution
   - Check WorkManager logs

### Troubleshooting

**Library Not Loading**:
- Verify `libaw_sync.so` exists in all `jniLibs/` folders
- Check NDK build output for errors
- Ensure correct architecture for test device

**Sync Directory Not Found**:
- Check storage permissions granted
- Verify `AW_SYNC_DIR` set correctly
- Check external storage is available

**JNI Function Not Found**:
- Verify JNI function names match exactly
- Check package name in android.rs matches Kotlin
- Rebuild native libraries

### Next Steps

1. Build aw-server-rust with new sync support
2. Copy libraries to aw-android
3. Implement SyncInterface.kt
4. Add basic UI with sync buttons
5. Test manual sync operations
6. Add WorkManager for background sync
7. Polish UI and error handling
8. Update documentation
