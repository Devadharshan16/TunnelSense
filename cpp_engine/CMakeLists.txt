cmake_minimum_required(VERSION 3.14)
project(IDR_Engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Automatically download Eigen3 (Header-only math library)
include(FetchContent)
FetchContent_Declare(
  eigen
  GIT_REPOSITORY https://gitlab.com/libeigen/eigen.git
  GIT_TAG        3.4.0
)
FetchContent_GetProperties(eigen)
if(NOT eigen_POPULATED)
    FetchContent_Populate(eigen)
endif()

# Include directories
include_directories(include)
include_directories(${eigen_SOURCE_DIR})

# Only build the standalone PC executable if we are NOT compiling for Android
if(NOT ANDROID)
    add_executable(idr_engine 
        src/main.cpp 
        src/ekf.cpp
        src/preprocessor.cpp
    )
endif()

# JNI Shared Library (for Android NDK)
add_library(idr_jni SHARED
    src/jni_bridge.cpp
    src/ekf.cpp
    src/preprocessor.cpp
)

if(ANDROID)
    target_link_options(idr_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()

