cmake_minimum_required(VERSION 3.10)
project(typst_module)

set(CMAKE_CXX_STANDARD 17)

add_library(typst_module SHARED
    TypstModule.cpp
)

target_include_directories(typst_module PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a
)

find_package(ReactNative REQUIRED)
target_link_libraries(typst_module
    ReactNative::ReactNative
)
