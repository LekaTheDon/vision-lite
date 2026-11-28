cmake_minimum_required(VERSION 3.22.1)
project(aml_fel C CXX)
# Vision's FEL native code (aml_fel_native.cpp), unchanged except the JNI package.
add_library(aml_fel SHARED aml_fel_native.cpp)
target_compile_options(aml_fel PRIVATE -Os -fvisibility=default)
target_link_libraries(aml_fel android log)
target_link_options(aml_fel PRIVATE -Wl,--gc-sections -s)

# Vision's Dolby Vision bridge (dovi_bridge.cpp, unchanged except the JNI package) over libdovi (MIT, prebuilt in
# DV7/libdovi per ABI): DV7 -> 8.1 and DV5 -> 8.1 RPU conversion for boxes without native FEL.
get_filename_component(_repo_root "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(_dovi_abi "android-arm64")
else()
    set(_dovi_abi "android-armeabi-v7a")
endif()
add_library(dovi_static STATIC IMPORTED)
set_target_properties(dovi_static PROPERTIES IMPORTED_LOCATION "${_repo_root}/DV7/libdovi/${_dovi_abi}/lib/libdovi.a")
add_library(dovi_bridge SHARED dovi_bridge.cpp)
target_compile_features(dovi_bridge PRIVATE cxx_std_17)
target_include_directories(dovi_bridge PRIVATE "${_repo_root}/DV7/libdovi/${_dovi_abi}/include" "${CMAKE_CURRENT_LIST_DIR}")
target_compile_definitions(dovi_bridge PRIVATE DOVI_REAL_LINKED=1)
target_compile_options(dovi_bridge PRIVATE -Os -Wno-unused-parameter)
target_link_options(dovi_bridge PRIVATE -Wl,--gc-sections -s "-Wl,-z,max-page-size=16384")
target_link_libraries(dovi_bridge dovi_static log)

# Big-core pinning for playback threads (vl_sched.cpp): only acts on boxes whose cores differ.
add_library(vl_sched SHARED vl_sched.cpp)
target_compile_options(vl_sched PRIVATE -Os)
target_link_options(vl_sched PRIVATE -Wl,--gc-sections -s "-Wl,-z,max-page-size=16384")
