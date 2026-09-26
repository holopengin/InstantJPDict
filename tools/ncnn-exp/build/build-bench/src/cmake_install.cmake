# Install script for directory: /tmp/opencode/ncnn_build/src/src

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/install")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Release")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "FALSE")
endif()

# Set path to fallback-tool for dependency-resolution.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "/nix/store/788mx070y81zjlg5ipcl0cra3afviw9k-gcc-wrapper-15.2.0/bin/objdump")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib64" TYPE STATIC_LIBRARY FILES "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/libncnn.a")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/ncnn" TYPE FILE FILES
    "/tmp/opencode/ncnn_build/src/src/allocator.h"
    "/tmp/opencode/ncnn_build/src/src/benchmark.h"
    "/tmp/opencode/ncnn_build/src/src/blob.h"
    "/tmp/opencode/ncnn_build/src/src/c_api.h"
    "/tmp/opencode/ncnn_build/src/src/command.h"
    "/tmp/opencode/ncnn_build/src/src/cpu.h"
    "/tmp/opencode/ncnn_build/src/src/datareader.h"
    "/tmp/opencode/ncnn_build/src/src/expression.h"
    "/tmp/opencode/ncnn_build/src/src/gpu.h"
    "/tmp/opencode/ncnn_build/src/src/layer.h"
    "/tmp/opencode/ncnn_build/src/src/layer_shader_type.h"
    "/tmp/opencode/ncnn_build/src/src/layer_type.h"
    "/tmp/opencode/ncnn_build/src/src/mat.h"
    "/tmp/opencode/ncnn_build/src/src/modelbin.h"
    "/tmp/opencode/ncnn_build/src/src/net.h"
    "/tmp/opencode/ncnn_build/src/src/option.h"
    "/tmp/opencode/ncnn_build/src/src/paramdict.h"
    "/tmp/opencode/ncnn_build/src/src/pipeline.h"
    "/tmp/opencode/ncnn_build/src/src/pipelinecache.h"
    "/tmp/opencode/ncnn_build/src/src/simpleocv.h"
    "/tmp/opencode/ncnn_build/src/src/simpleomp.h"
    "/tmp/opencode/ncnn_build/src/src/simplestl.h"
    "/tmp/opencode/ncnn_build/src/src/simplemath.h"
    "/tmp/opencode/ncnn_build/src/src/simplevk.h"
    "/tmp/opencode/ncnn_build/src/src/vulkan_header_fix.h"
    "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/ncnn_export.h"
    "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/layer_shader_type_enum.h"
    "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/layer_type_enum.h"
    "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/platform.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn/ncnn.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn/ncnn.cmake"
         "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/CMakeFiles/Export/dff4b137d6df5f9abf62b477e25a87c6/ncnn.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn/ncnn-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn/ncnn.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn" TYPE FILE FILES "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/CMakeFiles/Export/dff4b137d6df5f9abf62b477e25a87c6/ncnn.cmake")
  if(CMAKE_INSTALL_CONFIG_NAME MATCHES "^([Rr][Ee][Ll][Ee][Aa][Ss][Ee])$")
    file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn" TYPE FILE FILES "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/CMakeFiles/Export/dff4b137d6df5f9abf62b477e25a87c6/ncnn-release.cmake")
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib64/cmake/ncnn" TYPE FILE FILES
    "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/ncnnConfig.cmake"
    "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/ncnnConfigVersion.cmake"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib64/pkgconfig" TYPE FILE FILES "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/ncnn.pc")
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "/home/holopengin/Projects/ijp-ncnn-exp/tools/ncnn-exp/build/build-bench/src/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
