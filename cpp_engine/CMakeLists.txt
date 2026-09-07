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
FetchContent_MakeAvailable(eigen)

# Include directories
include_directories(include)
include_directories(${eigen_SOURCE_DIR})

# Main executable (for testing the engine)
add_executable(idr_engine 
    src/main.cpp 
    src/ekf.cpp
)

# Link Eigen
target_link_libraries(idr_engine PRIVATE eigen)
