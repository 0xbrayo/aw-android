# aw-sync Android Implementation Investigation

**Branch**: `investigate-aw-sync`
**Date**: 2025-10-16
**Status**: Investigation Complete

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
