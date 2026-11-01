# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")

# Declares and names the project.
project(jamesdsp LANGUAGES C)
project(jamesdsp-wrapper LANGUAGES CXX)

include(libcrashlytics-connector/CMakeLists.txt)
include(libjdspimptoolbox/CMakeLists.txt)
include(libjamesdsp-wrapper/CMakeLists.txt)
include(libsofareader/CMakeLists.txt)

file(GLOB_RECURSE LIBJAMESDSP_SOURCE_FILES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.c
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.h)

# The Signalsmith methods of the pitch shifter: the one C++ file in the engine,
# a C face on the header-only Signalsmith Stretch library vendored (unmodified)
# in third_party/ at the repository root. It goes into both the app's engine
# and the rooted module's, so both targets get it and the include path.
set(SIGNALSMITH_SHIM
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/signalsmith.cpp)
set(SIGNALSMITH_INCLUDE ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
# Only its C functions are meant to be seen, and only from inside the library.
# Without this every template the library instantiates would be exported from
# libjamesdsp.so, where the wrapper's own copy of libc++ could bind to them.
set_source_files_properties(${SIGNALSMITH_SHIM} PROPERTIES
        COMPILE_OPTIONS "-fvisibility=hidden;-fvisibility-inlines-hidden")
list(APPEND LIBJAMESDSP_SOURCE_FILES ${SIGNALSMITH_SHIM})

add_library( # Sets the name of the library.
        jamesdsp

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        EELStdOutExtension.h)

# -O2 for every source, Debug builds included: Signalsmith Stretch is up to ten
# times slower unoptimised, which would make a debug build useless to listen to.
target_compile_options(jamesdsp PRIVATE -O2)
# C only. Handed to the C++ shim as well, clang++ refuses to start.
target_compile_options(jamesdsp PRIVATE $<$<COMPILE_LANGUAGE:C>:-std=gnu11>)
target_include_directories(jamesdsp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/)
target_include_directories(jamesdsp PRIVATE ${SIGNALSMITH_INCLUDE})
# The shim pulls in the static C++ runtime, and without this its symbols are
# exported from libjamesdsp.so (116 of them, where before there were none),
# for the wrapper's own copy of the runtime to resolve against.
target_link_options(jamesdsp PRIVATE -Wl,--exclude-libs,ALL)

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

target_link_libraries( # Specifies the target library.
        jamesdsp

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})


# ---------------------------------------------------------------------------
# System-wide effect library for the Magisk module.
#
# Not linked into the APK: this builds the same DSP core as a HAL audio effect
# so a Magisk module can install it into /system/lib*/soundfx, which is what
# makes root mode run this fork's engine rather than the stock one. Excluded
# from the default build so ordinary APK builds are unaffected.
# ---------------------------------------------------------------------------
add_library(
        jamesdsp_hal
        SHARED
        EXCLUDE_FROM_ALL
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        hal/JamesDspHalEffect.cpp)

# The engine sources predate current clang defaults, which promoted implicit
# declarations and mismatched function pointers to errors. The APK target gets
# past this with -std=gnu11; apply the same to the C sources only, since the
# wrapper itself is C++, and relax the specific checks upstream code trips.
target_compile_options(jamesdsp_hal PRIVATE -O2 -fvisibility=hidden
        $<$<COMPILE_LANGUAGE:C>:-std=gnu11>
        $<$<COMPILE_LANGUAGE:C>:-Wno-error=implicit-function-declaration>
        $<$<COMPILE_LANGUAGE:C>:-Wno-error=implicit-int>
        $<$<COMPILE_LANGUAGE:C>:-Wno-error=incompatible-function-pointer-types>)
target_include_directories(jamesdsp_hal PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/
        ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni/)
target_include_directories(jamesdsp_hal PRIVATE ${SIGNALSMITH_INCLUDE})
# Deliberately NOT named libjamesdsp.so here: that would collide with the APK's
# own target, which emits the same filename. The packaging step renames it when
# copying into the module, where the audio server expects libjamesdsp.so.
target_link_libraries(jamesdsp_hal ${log-lib})
# Loaded by the audio server, so the link has to satisfy what the platform
# refuses to load rather than what merely builds.
#  max-page-size: Android 15 devices with 16 KB pages will not map a library
#    aligned to 4 KB, and the NDK only passes this when a flag we do not set is
#    on. The failure is a silent skip in the effects factory, not a crash, so
#    nothing would tell us.
#  noexecstack: refused since Android 14.
# The file has to stay libjamesdsp_hal.so: the APK's own library is already
# libjamesdsp.so, and giving this target that output name made the impulse
# toolbox link against THIS library instead, which is built with hidden
# visibility and exports almost nothing. But it installs as libjamesdsp.so, and
# bionic registers a library under its soname rather than the path it was
# opened from, so the soname has to say so. CMake derives its own -soname from
# the output name and emits it after anything passed here, so the only way to
# win is to tell CMake not to emit one.
set_target_properties(jamesdsp_hal PROPERTIES NO_SONAME TRUE)
#  exclude-libs: the Signalsmith shim brings in the C++ runtime's exception
#    support, and without this 96 of the static runtime's symbols are exported
#    into the audio server next to the system's own copy. Before it there were
#    none, and the effect's three entry points are all that need to be seen.
target_link_options(jamesdsp_hal PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
        -Wl,-z,noexecstack
        -Wl,-soname,libjamesdsp.so)
