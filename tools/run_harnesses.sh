#!/usr/bin/env bash
# Builds and runs the native harnesses in tools/ on a Linux host (x86_64),
# with the system clang instead of the NDK, so they can run in CI-like
# containers without a phone or emulator.
#
#   tools/run_harnesses.sh                # build + run all
#   tools/run_harnesses.sh vinyl dbbnan   # only these (name without _harness)
#
# Each harness carries its own NDK build line in its header comment. This
# script reads that line, swaps the NDK clang for the host clang, expands
# $J/$N and {a,b} lists, and links "<engine objects>" against a static
# archive of every jdsp/ source. tools/hostshim stands in for
# <android/log.h>. Output goes to tools/host_build (git-ignored).
#
# Env: JOBS (parallel compile, default nproc), TIMEOUT (per run, default 300s),
#      KNOWN_FAIL (space separated names expected to fail for a reason already
#      written down; they are reported but do not fail the run)
#
# Needs clang with its AddressSanitizer runtime (for sofa) and zlib headers;
# on Ubuntu: clang, libclang-rt-dev, zlib1g-dev.
set -u
cd "$(dirname "$0")/.."
ROOT=$PWD
J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
N=$J/Effects/eel2/numericSys
HALJNI=app/src/main/cpp/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni
OUT=tools/host_build
# SANITIZE=1: the engine and every harness under AddressSanitizer and UBSan,
# built into their own directory. A harness passes only if it also prints no
# sanitizer report.
SAN=""
if [ -n "${SANITIZE:-}" ]; then
    SAN="-fsanitize=address,undefined -fno-omit-frame-pointer -g"
    OUT=tools/host_build_san
    export ASAN_OPTIONS="detect_leaks=0:abort_on_error=0:halt_on_error=1"
    export UBSAN_OPTIONS="print_stacktrace=1"
fi
SHIM="-I tools/hostshim"
COMMONC="-Wno-error=implicit-function-declaration -Wno-error=incompatible-function-pointer-types -Wno-error=implicit-int -Wno-error=int-conversion"
TIMEOUT=${TIMEOUT:-300}
mkdir -p $OUT/objs

# --- engine archive -------------------------------------------------------
ENGINE=$OUT/libengine.a
if [ ! -f $ENGINE ] || [ -n "$(find $J \( -name '*.c' -o -name '*.cpp' \) -newer $ENGINE | head -1)" ]; then
    echo "== building engine archive"
    rm -f $OUT/objs/*.o
    find $J -name '*.c' | xargs -P "${JOBS:-$(nproc)}" -I{} sh -c \
        'o=$(echo "{}" | sed "s|/|_|g; s|\.c$|.o|"); clang -O2 -w -ffp-contract=off -fPIC -std=gnu11 '"$SAN $COMMONC $SHIM"' -I '"$J"' -c "{}" -o '"$OUT"'/objs/$o' \
        || { echo "engine build failed"; exit 1; }
    # The engine's one C++ file (the Signalsmith shim) needs the vendored
    # header-only library from third_party.
    for f in $(find $J -name '*.cpp'); do
        o=$(echo "$f" | sed "s|/|_|g; s|\.cpp$|.o|")
        clang++ -O2 -w -std=c++17 -fPIC $SAN -I third_party -I $J -c "$f" -o $OUT/objs/$o \
            || { echo "engine C++ build failed: $f"; exit 1; }
    done
    ar rcs $ENGINE $OUT/objs/*.o
fi

# --- pull the build line out of a harness header ---------------------------
# Joins backslash-continued comment lines into commands and prints the one
# that compiles this harness file.
build_line() {
    awk -v f="tools/$1" '
        /^\/\// || /^ \*/ { sub(/^(\/\/| \*)[ \t]*/, "");
            if (cont) { cmd = cmd " " $0 } else if ($0 ~ /clang/) { cmd = $0 } else { next }
            if ($0 ~ /\\[ \t]*$/) { sub(/\\[ \t]*$/, "", cmd); cont = 1 } else { cont = 0; if (index(cmd, f)) { print cmd; exit } cmd = "" }
            next }
        { exit }' "tools/$1"
}

names=("$@")
if [ ${#names[@]} -eq 0 ]; then
    for f in tools/*_harness.c tools/*_harness.cpp; do b=${f##*/}; names+=("${b%_harness.*}"); done
fi

# Harnesses whose header build is more than one compiler line. Each recipe
# follows that header; keep them in step if a header changes.
recipe() {
    case $1 in
        signalsmith)
            # C objects, the C++ shim, then one link with the wraps it counts.
            local d=$OUT/signalsmith.objs
            rm -rf $d && mkdir -p $d && (
                cd $d &&
                clang -O2 -w -ffp-contract=off $SAN -I $ROOT/$J -I $ROOT/tools/hostshim -c \
                    $ROOT/tools/signalsmith_harness.c $ROOT/$J/Effects/pitchshift.c \
                    $ROOT/$J/Effects/rubberband.c &&
                clang++ -O2 -std=c++17 -w $SAN -I $ROOT/third_party -I $ROOT/$J -c \
                    $ROOT/$J/Effects/signalsmith.cpp &&
                clang++ -Wl,--wrap=calloc -Wl,--wrap=free \
                    -Wl,--wrap=SignalsmithCreate -Wl,--wrap=SignalsmithDestroy \
                    *.o $SAN -lm -ldl -lpthread -o $ROOT/$OUT/signalsmith ) ;;
        sofa)
            # The SOFA reader and libmysofa, under AddressSanitizer as on the
            # emulator; it reads the bundled KEMAR set.
            local S=third_party/libmysofa/src
            clang -O1 -g -w -fsanitize=address $( [ -n "$SAN" ] && echo -fsanitize=undefined ) -I $S -I $S/hrtf -I app/src/main/cpp/libsofareader \
                tools/sofa_harness.c app/src/main/cpp/libsofareader/sofa_extract.c \
                $S/hdf/*.c $S/hrtf/check.c $S/hrtf/reader.c $S/hrtf/spherical.c \
                $S/hrtf/tools.c -lz -lm -o $OUT/sofa ;;
        *) return 2 ;;
    esac
}
run_args() {
    case $1 in
        sofa) echo "app/src/main/assets/hrtf/mit_kemar_normal_pinna.sofa" ;;
    esac
}
# Need the app itself on a device, so there is nothing to run here.
#   convadv: dlopens the app's libjamesdsp.so through JNI.
DEVICE_ONLY=" convadv "

pass=(); fail=(); nobuild=(); noline=(); skipped=(); known=()
verdict() {
    local n=$1 rc=$2 t=$3
    if [ $rc -eq 0 ] && grep -qE "runtime error:|ERROR: AddressSanitizer" "$OUT/$n.log"; then
        rc=99
    fi
    if [ $rc -eq 0 ]; then
        pass+=("$n"); echo "ok $n (${t}s)"
        case " ${KNOWN_FAIL:-} " in *" $n "*) echo "   ($n is listed in KNOWN_FAIL but passed)" ;; esac
    else
        case " ${KNOWN_FAIL:-} " in
            *" $n "*) known+=("$n"); echo "~~ $n failed, as listed in KNOWN_FAIL" ;;
            *) fail+=("$n"); echo "!! $n exit $rc (${t}s): $(tail -2 "$OUT/$n.log" | tr '\n' ' ')" ;;
        esac
    fi
}
for n in "${names[@]}"; do
    src=$(ls tools/${n}_harness.c tools/${n}_harness.cpp 2>/dev/null | head -1)
    [ -z "$src" ] && { echo "?? no harness $n"; continue; }
    case $DEVICE_ONLY in *" $n "*) skipped+=("$n"); echo "-- $n: device only, skipped"; continue ;; esac
    log=$OUT/$n.log
    recipe $n > $OUT/$n.build.log 2>&1; rc=$?
    if [ $rc -ne 2 ]; then
        if [ $rc -ne 0 ]; then
            nobuild+=("$n"); echo "XX $n: build failed (see $OUT/$n.build.log)"; continue
        fi
        start=$(date +%s)
        timeout "$TIMEOUT" ./$OUT/$n $(run_args $n) > "$log" 2>&1; rc=$?
        t=$(( $(date +%s) - start ))
        verdict $n $rc $t
        continue
    fi
    line=$(build_line "${src##*/}")
    if [ -z "$line" ]; then
        # No build line: link against the whole engine (as build_param_harness.ps1 does)
        noline+=("$n")
        case $src in
            *.cpp) line="clang++ -O1 -w -std=c++17 -I $J -I $HALJNI $src <engine objects> -lm" ;;
            *)     line="clang -O2 -w -ffp-contract=off -I $J $src <engine objects> -lm" ;;
        esac
    fi
    # NDK clang -> host clang; drop -o target; engine objects -> archive
    cmd=$(echo "$line" | sed -E \
        -e 's#\$NDK/[a-z0-9_]+-linux-android[0-9]*-clang\+\+#clang++#' \
        -e 's#\$NDK/[a-z0-9_]+-linux-android[0-9]*-clang#clang#' \
        -e 's#-static-libstdc\+\+##' \
        -e 's#-llog##g' \
        -e 's#-o [^ ]+##' \
        -e 's#<engine objects>#'"$ENGINE"'#')
    # A harness that brings tools/lockstub.c and the whole engine gets the
    # lock from the stub (linked first), not from jdspController.
    case $cmd in *lockstub.c*$ENGINE*) cmd="$cmd -Wl,--allow-multiple-definition" ;; esac
    # Same for harnesses that stub Rubber Band out: the engine archive still
    # carries rubberband.c (the controller calls into it), and the stubs,
    # linked first, are the ones meant to win.
    case $cmd in *$ENGINE*) grep -q '^int RubberBandPrepare' "$src" &&
        cmd="$cmd -Wl,--allow-multiple-definition" ;; esac
    # The host's libstdc++ <cmath> breaks if the engine's min/max macros are
    # already defined when it is first included; include it up front.
    case $cmd in clang++*) cmd="$cmd -include cmath" ;; *) cmd="$cmd $COMMONC" ;; esac
    # The archive carries C++ now, so every harness links its runtime.
    cmd="$cmd $SAN $SHIM -lpthread -lstdc++ -o $OUT/$n"
    if ! (eval "$cmd") > $OUT/$n.build.log 2>&1; then
        nobuild+=("$n"); echo "XX $n: build failed (see $OUT/$n.build.log)"; continue
    fi
    start=$(date +%s)
    timeout "$TIMEOUT" ./$OUT/$n > "$log" 2>&1; rc=$?
    t=$(( $(date +%s) - start ))
    verdict $n $rc $t
done

echo
echo "pass ${#pass[@]}, fail ${#fail[@]}, build failed ${#nobuild[@]}, known failures ${#known[@]}"
[ ${#fail[@]} -gt 0 ] && echo "failed: ${fail[*]}"
[ ${#nobuild[@]} -gt 0 ] && echo "build failed: ${nobuild[*]}"
[ ${#noline[@]} -gt 0 ] && echo "no build line (linked whole engine): ${noline[*]}"
[ ${#skipped[@]} -gt 0 ] && echo "device only, not run: ${skipped[*]}"
[ ${#known[@]} -gt 0 ] && echo "known failures: ${known[*]}"
[ ${#fail[@]} -eq 0 ] && [ ${#nobuild[@]} -eq 0 ]
