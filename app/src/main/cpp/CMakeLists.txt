cmake_minimum_required(VERSION 3.22.1)

project("neon_image_pipeline")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(
    neon_image_pipeline
    SHARED
    neon_image_pipeline.cpp
)

target_compile_options(neon_image_pipeline PRIVATE -O3)

target_link_libraries(
    neon_image_pipeline
    ${log-lib}
)
