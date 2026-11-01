/* Written for this project in place of the config.h that libmysofa's CMake
   build generates from src/config.h.in. Only reader.c uses it: the install
   prefix for a default file this app never asks for, and the version that
   mysofa_getversion reports. */
#if !defined _CONFIG_H
#define _CONFIG_H

#define CMAKE_INSTALL_PREFIX "/nonexistent"
#define CPACK_PACKAGE_VERSION_MAJOR 1
#define CPACK_PACKAGE_VERSION_MINOR 3
#define CPACK_PACKAGE_VERSION_PATCH 5

#endif
