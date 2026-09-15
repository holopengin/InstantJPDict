#!/usr/bin/env bash
set -euo pipefail
# Build nav_graph_core Rust library for Android aarch64 and generate Kotlin bindings
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CARGO_DIR="$SCRIPT_DIR"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
JAVA_SRC_DIR="$PROJECT_DIR/app/src/main/java"

# 1. Determine Android NDK path (pinned 28.2.13676358 per app/build.gradle.kts ndkVersion).
PINNED_NDK="28.2.13676358"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Candidate SDK roots: the env vars Gradle/Android Studio set, the SDK the
    # project's local.properties points at (this script runs from Gradle's
    # buildNavGraphCore, which exports neither), then the usual locations.
    # Pinned version first, else newest installed (never oldest).
    sdk_roots=()
    if [ -n "${ANDROID_HOME:-}" ]; then sdk_roots+=("$ANDROID_HOME"); fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then sdk_roots+=("$ANDROID_SDK_ROOT"); fi
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$PROJECT_DIR/local.properties" 2>/dev/null | head -1)
    if [ -n "$sdk_dir" ]; then sdk_roots+=("$sdk_dir"); fi
    sdk_roots+=("$HOME/Android/Sdk" "$HOME/.local/share/android-sdk" "/opt/android-sdk")
    for root in "${sdk_roots[@]}"; do
        if [ -d "$root/ndk/$PINNED_NDK" ]; then
            ANDROID_NDK_HOME="$root/ndk/$PINNED_NDK"
            break
        fi
    done
    if [ -z "${ANDROID_NDK_HOME:-}" ]; then
        for root in "${sdk_roots[@]}"; do
            if [ -d "$root/ndk" ]; then
                newest=$(ls "$root/ndk" 2>/dev/null | sort -V | tail -1)
                if [ -n "$newest" ]; then ANDROID_NDK_HOME="$root/ndk/$newest"; break; fi
            fi
        done
    fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set. Install Android NDK $PINNED_NDK via Android Studio SDK Manager." >&2
    exit 1
fi
echo "NDK: $ANDROID_NDK_HOME"

# 2. Set up cargo config for the NDK toolchain
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android21-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android21-clang"

# 3. Build the Rust library for aarch64-linux-android. The 16 KB page alignment
# comes from .cargo/config.toml's rustflags; the check in 3b holds it even when
# an environment RUSTFLAGS overrides that file.
echo "Building nav_graph_core for aarch64-linux-android..."
cd "$CARGO_DIR"
cargo build --release --target aarch64-linux-android

# 3b. Fail fast unless every LOAD segment is 16 KB-aligned (#86/F1). Android
# 15/16 devices can run 16 KB pages and the loader refuses a library whose
# segments are aligned below the page size. The check uses the pinned NDK's
# llvm-readelf, i.e. the same toolchain that produced the file.
SO="$CARGO_DIR/target/aarch64-linux-android/release/libnav_graph_core.so"
load_count=$("$TOOLCHAIN/bin/llvm-readelf" -lW "$SO" | awk '$1 == "LOAD"' | wc -l)
if [ "$load_count" -eq 0 ]; then
    echo "ERROR: no LOAD segments found in libnav_graph_core.so" >&2
    exit 1
fi
while IFS= read -r align; do
    case "$align" in
        0x*) ;;
        *) echo "ERROR: cannot read a LOAD alignment from llvm-readelf: '$align'" >&2; exit 1 ;;
    esac
    if [ "$((align))" -lt 16384 ]; then
        echo "ERROR: libnav_graph_core.so has a LOAD segment aligned at $align bytes;" >&2
        echo "       16 KB (0x4000) is required for Android 16 KB-page devices (#86/F1)." >&2
        echo "       Check nav_graph_core/.cargo/config.toml's rustflags and that RUSTFLAGS" >&2
        echo "       in the environment is not overriding them." >&2
        exit 1
    fi
done < <("$TOOLCHAIN/bin/llvm-readelf" -lW "$SO" | awk '$1 == "LOAD" { print $NF }')
echo "16 KB alignment: OK ($load_count LOAD segments checked)"

# 4. Copy the .so to jniLibs
mkdir -p "$JNILIBS_DIR"
cp "$SO" "$JNILIBS_DIR/"
echo "Copied libnav_graph_core.so to $JNILIBS_DIR"

# 5. Generate Kotlin bindings using uniffi-bindgen
# Generate Kotlin bindings using uniffi-bindgen
echo "Generating Kotlin bindings..."
cargo run --bin uniffi-bindgen generate --library \
    "$CARGO_DIR/target/aarch64-linux-android/release/libnav_graph_core.so" \
    --language kotlin --out-dir "$JAVA_SRC_DIR" 2>/dev/null || \
cargo run --features "uniffi/cli" --bin uniffi-bindgen generate --library \
    "$CARGO_DIR/target/aarch64-linux-android/release/libnav_graph_core.so" \
    --language kotlin --out-dir "$JAVA_SRC_DIR"

echo "Done. .so in $JNILIBS_DIR, bindings in $JAVA_SRC_DIR/uniffi/"
