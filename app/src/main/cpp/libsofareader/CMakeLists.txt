# The SOFA reader for the Binaural upmix: the vendored part of libmysofa plus
# the extraction and JNI code here. Its own library, loaded only by the
# isolated helper process that reads a set, so the engine and the app's main
# process never load it. It must not sit under libjdspimptoolbox, whose
# sources are picked up with a recursive glob.
set(LIBMYSOFA_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/libmysofa)

add_library(rv4asofa SHARED
        ${CMAKE_CURRENT_LIST_DIR}/sofa_extract.c
        ${CMAKE_CURRENT_LIST_DIR}/sofa_jni.c
        ${LIBMYSOFA_DIR}/src/hdf/btree.c
        ${LIBMYSOFA_DIR}/src/hdf/dataobject.c
        ${LIBMYSOFA_DIR}/src/hdf/fractalhead.c
        ${LIBMYSOFA_DIR}/src/hdf/gcol.c
        ${LIBMYSOFA_DIR}/src/hdf/gunzip.c
        ${LIBMYSOFA_DIR}/src/hdf/superblock.c
        ${LIBMYSOFA_DIR}/src/hrtf/check.c
        ${LIBMYSOFA_DIR}/src/hrtf/reader.c
        ${LIBMYSOFA_DIR}/src/hrtf/spherical.c
        ${LIBMYSOFA_DIR}/src/hrtf/tools.c)

target_include_directories(rv4asofa PRIVATE
        ${LIBMYSOFA_DIR}/src
        ${LIBMYSOFA_DIR}/src/hrtf)
# Upstream builds with its own warnings relaxed; the defaults of current clang
# turn some of what it does into errors.
target_compile_options(rv4asofa PRIVATE -O2 -std=gnu11 -fvisibility=hidden
        -Wno-error=implicit-function-declaration
        -Wno-unused-parameter)

find_library(sofa-z-lib z)
find_library(sofa-log-lib log)
target_link_libraries(rv4asofa ${sofa-z-lib} ${sofa-log-lib})
target_link_options(rv4asofa PRIVATE -Wl,-z,max-page-size=16384)
