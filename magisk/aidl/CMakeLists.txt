# Builds the rooted engine as an AIDL effect plugin: librv4a_aidl_v<N>.so.
#
#   generate.sh <N>
#   cmake -S magisk/aidl -B <dir> -G Ninja -DRV4A_HAL_VERSION=<N> \
#         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static
#
# Not part of the app build. The library is loaded by the device's own effect
# factory, inside the audio HAL process, so the rules here are the factory's,
# not ours:
#
#  - One build per effect HAL version (2, 3, 4), from that version's frozen
#    interfaces and AOSP's effect base from the matching Android release. The
#    factory and the plugin pass C++ objects to each other, so the layouts
#    have to be identical.
#  - Only the three plugin entry points are exported (exports.map). The
#    generated interface code, AOSP's base classes, libc++ and the legacy
#    effect all stay private, so nothing can bind to the factory's own copies.
#  - The only runtime dependencies are platform libraries every vendor process
#    can load (libbinder_ndk, liblog, libc, libm, libdl). CI checks this.
#  - Linked strictly: an unresolved symbol fails the build here instead of
#    failing dlopen inside the audio HAL.
cmake_minimum_required(VERSION 3.22)
project(rv4a_aidl_effect CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT RV4A_HAL_VERSION MATCHES "^[234]$")
    message(FATAL_ERROR "Set RV4A_HAL_VERSION to 2, 3 or 4 (got '${RV4A_HAL_VERSION}')")
endif()
# AOSP's effect base, taken from the release that froze each version.
set(AOSP_TAG_2 android-15.0.0_r1)
set(AOSP_TAG_3 android-16.0.0_r1)
set(AOSP_TAG_4 android-17.0.0_r1)
set(AOSP ${CMAKE_CURRENT_SOURCE_DIR}/aosp/${AOSP_TAG_${RV4A_HAL_VERSION}})

set(GEN ${CMAKE_CURRENT_SOURCE_DIR}/generated/v${RV4A_HAL_VERSION})
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
set(JDSP ${CPP}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp)
set(LEGACY_HEADERS ${CPP}/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni)

if(NOT EXISTS ${GEN}/include)
    message(FATAL_ERROR "Run magisk/aidl/generate.sh ${RV4A_HAL_VERSION} first: ${GEN} is empty")
endif()

file(GLOB_RECURSE AIDL_SRC ${GEN}/src/*.cpp)
# The same engine sources, found the same way, as the app's jamesdsp_hal target.
file(GLOB_RECURSE ENGINE_SRC ${JDSP}/*.c)
list(LENGTH ENGINE_SRC ENGINE_COUNT)
message(STATUS "effect HAL V${RV4A_HAL_VERSION}, AOSP base ${AOSP_TAG_${RV4A_HAL_VERSION}}, ${ENGINE_COUNT} engine sources")
if(ENGINE_COUNT LESS 40)
    message(FATAL_ERROR "Only ${ENGINE_COUNT} engine sources under ${JDSP}")
endif()

set(LIB rv4a_aidl_v${RV4A_HAL_VERSION})

# What the plugin and the test harness both compile: the generated interface
# code, libfmq's pieces, the legacy effect and the engine. The harness gets its
# own copy built with identical flags, so its legacy reference path and the
# plugin run the same machine code.
set(SHARED_SRC
        fmq/EventFlag.cpp
        fmq/compat/FmqSupport.cpp
        src/LegacyBridge.cpp
        ${AIDL_SRC}
        ${CPP}/hal/JamesDspHalEffect.cpp
        ${CPP}/EELStdOutExtension.c
        ${ENGINE_SRC})

# The legacy audio headers are only for the two files that speak the legacy
# effect interface. Kept off everything else, their C macros cannot meet the
# generated interface types.
set_source_files_properties(src/LegacyBridge.cpp ${CPP}/hal/JamesDspHalEffect.cpp
        PROPERTIES INCLUDE_DIRECTORIES ${LEGACY_HEADERS})

function(rv4a_common target)
    target_compile_definitions(${target} PRIVATE RV4A_HAL_VERSION=${RV4A_HAL_VERSION})
    # Order matters: our stand-ins for platform headers come before anything
    # that could also provide them.
    target_include_directories(${target} PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/compat
            ${CMAKE_CURRENT_SOURCE_DIR}/fmq/compat
            ${CMAKE_CURRENT_SOURCE_DIR}/fmq/include
            ${CMAKE_CURRENT_SOURCE_DIR}/fmq/base
            ${GEN}/include
            ${AOSP}/include
            ${AOSP}
            ${CMAKE_CURRENT_SOURCE_DIR}/src
            ${JDSP}
            ${CPP}/hal)
    # The engine sources predate current clang defaults; the same relaxations
    # as the app's jamesdsp_hal target, applied to C only.
    target_compile_options(${target} PRIVATE -O2 -ffunction-sections -fdata-sections
            $<$<COMPILE_LANGUAGE:C>:-std=gnu11>
            $<$<COMPILE_LANGUAGE:C>:-Wno-error=implicit-function-declaration>
            $<$<COMPILE_LANGUAGE:C>:-Wno-error=implicit-int>
            $<$<COMPILE_LANGUAGE:C>:-Wno-error=incompatible-function-pointer-types>
            $<$<COMPILE_LANGUAGE:CXX>:-Wno-thread-safety-analysis>)
endfunction()

add_library(${LIB} SHARED
        src/Rv4aAidlEffect.cpp
        ${AOSP}/EffectImpl.cpp
        ${AOSP}/EffectContext.cpp
        ${AOSP}/EffectThread.cpp
        ${SHARED_SRC})
rv4a_common(${LIB})

find_library(log-lib log)
find_library(binder-lib binder_ndk)
target_link_libraries(${LIB} ${binder-lib} ${log-lib} m dl)

# max-page-size: 16 KB page devices refuse a library aligned to 4 KB, and the
#   factory reports that as a skipped library, not a crash.
# noexecstack: refused since Android 14.
# no-undefined / defs: every symbol must resolve against the libraries above
#   at link time.
# gc-sections: drops the unused parts of the generated interface code.
set_target_properties(${LIB} PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map)
target_link_options(${LIB} PRIVATE
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map
        -Wl,-z,max-page-size=16384
        -Wl,-z,noexecstack
        -Wl,-z,defs
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--build-id=sha1)

# The emulator harness (tools/aidl_plugin_harness.cpp). Not built by default
# and never packaged.
option(RV4A_HARNESS "Build the AIDL plugin test harness" OFF)
if(RV4A_HARNESS)
    add_executable(aidl_plugin_harness
            ${CMAKE_CURRENT_SOURCE_DIR}/../../tools/aidl_plugin_harness.cpp
            ${SHARED_SRC})
    rv4a_common(aidl_plugin_harness)
    target_include_directories(aidl_plugin_harness PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../tools)
    target_link_libraries(aidl_plugin_harness ${binder-lib} ${log-lib} m dl)
    target_link_options(aidl_plugin_harness PRIVATE -Wl,-z,max-page-size=16384)
endif()
