cmake_minimum_required(VERSION 3.22.1)
project("gme_jni")

include(FetchContent)

FetchContent_Declare(
    libgme
    GIT_REPOSITORY https://github.com/libgme/game-music-emu.git
    GIT_TAG        0.6.3
    GIT_SHALLOW    TRUE
)
set(GME_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_UBSAN OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(libgme)

add_library(gme_jni SHARED gme_jni.cpp)

target_include_directories(gme_jni PRIVATE ${libgme_SOURCE_DIR})

target_link_libraries(gme_jni
    gme
    android
    log
)
