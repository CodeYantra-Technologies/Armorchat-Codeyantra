cmake_minimum_required(VERSION 3.22.1)
project("armorchat_vodozemac")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add prebuilt native libraries from cargo-ndk build
add_library(armorchat_vodozemac SHARED IMPORTED)

# Set library paths for different ABIs
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set_target_properties(armorchat_vodozemac PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libarmorchat_vodozemac.so)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set_target_properties(armorchat_vodozemac PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/x86_64/libarmorchat_vodozemac.so)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set_target_properties(armorchat_vodozemac PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/armeabi-v7a/libarmorchat_vodozemac.so)
endif()

find_library(log-lib log)
