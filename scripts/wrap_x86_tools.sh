#!/bin/bash
# x86_64 -> qemu-user wrapper generator for the Android SDK/NDK on an aarch64 host.
# binfmt_misc is unavailable in this container, so each x86_64 ELF is renamed to
# "<name>.bin" and replaced by a shell wrapper that execs qemu-x86_64-static.
set -u

SYSROOT=/opt/x86_64-sysroot
QEMU=/usr/bin/qemu-x86_64-static
WRAPPED_LOG=/tmp/opencode/wrapped_binaries.txt
mkdir -p /tmp/opencode
touch "$WRAPPED_LOG"

wrap_one() {
    local f="$1"
    # Resolve symlinks to the real ELF.
    if [ -L "$f" ]; then
        local target
        target=$(readlink -f "$f" 2>/dev/null) || return 0
        [ -f "$target" ] || return 0
        wrap_one "$target"
        return 0
    fi
    [ -f "$f" ] || return 0
    [ -x "$f" ] || return 0
    case "$f" in *.bin|*.sh|*.jar) return 0 ;; esac
    file -b "$f" 2>/dev/null | grep -q "ELF 64-bit LSB.*x86-64" || return 0
    # Already wrapped?
    [ -f "$f.bin" ] && return 0

    mv "$f" "$f.bin" || return 0
    cat > "$f" <<EOF
#!/bin/sh
exec $QEMU -L $SYSROOT "$f.bin" "\$@"
EOF
    chmod 0755 "$f"
    echo "$f" >> "$WRAPPED_LOG"
}

wrap_tree() {
    local root="$1"
    [ -e "$root" ] || return 0
    find "$root" -type f -perm -u+x 2>/dev/null | while read -r f; do
        wrap_one "$f"
    done
    # Also catch ELF files that lost their +x bit.
    find "$root" -type f 2>/dev/null | while read -r f; do
        file -b "$f" 2>/dev/null | grep -q "ELF 64-bit LSB.*x86-64" || continue
        [ -f "$f.bin" ] && continue
        mv "$f" "$f.bin" || continue
        cat > "$f" <<EOF
#!/bin/sh
exec $QEMU -L $SYSROOT "$f.bin" "\$@"
EOF
        chmod 0755 "$f"
        echo "$f" >> "$WRAPPED_LOG"
    done
}

NDK=/opt/android-sdk/ndk/26.3.11579264
echo "wrapping NDK toolchain..."
wrap_tree "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
wrap_tree "$NDK/toolchains/llvm/prebuilt/linux-x86_64/libexec"
wrap_tree "$NDK/prebuilt"

echo "wrapping SDK cmake..."
wrap_tree /opt/android-sdk/cmake/3.22.1/bin

echo "wrapping build-tools..."
wrap_tree /opt/android-sdk/build-tools/34.0.0

echo "wrapping platform-tools..."
wrap_tree /opt/android-sdk/platform-tools

echo "done. wrapped=$(wc -l < "$WRAPPED_LOG") binaries"
