cmake_minimum_required(VERSION 3.22.1)
project(vl_native C CXX)
# Amlogic dual-layer Dolby Vision port driver (vl_fel.cpp) for the FEL path (player/fel).
add_library(vl_fel SHARED vl_fel.cpp)
target_compile_options(vl_fel PRIVATE -Os)
target_link_libraries(vl_fel android log)
target_link_options(vl_fel PRIVATE -Wl,--gc-sections -s "-Wl,-z,max-page-size=16384")

# Dolby Vision sample rewriter: native/vldovi (own Rust over the MIT dolby_vision crate), built by CI per ABI into
# native/vldovi/out/<abi>/libvldovi.a, plus the JNI shim vl_dovi_jni.cpp.
get_filename_component(_repo_root "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(_vldovi "${_repo_root}/native/vldovi/out/${ANDROID_ABI}/libvldovi.a")
if (EXISTS "${_vldovi}")
    add_library(vldovi_static STATIC IMPORTED)
    set_target_properties(vldovi_static PROPERTIES IMPORTED_LOCATION "${_vldovi}")
    add_library(vl_dovi SHARED vl_dovi_jni.cpp)
    target_compile_options(vl_dovi PRIVATE -Os)
    target_link_options(vl_dovi PRIVATE -Wl,--gc-sections -s "-Wl,-z,max-page-size=16384")
    target_link_libraries(vl_dovi vldovi_static log dl m)
else()
    message(WARNING "libvldovi.a missing for ${ANDROID_ABI}: Dolby Vision conversion off in this build")
endif()

# Big-core pinning for playback threads (vl_sched.cpp): only acts on boxes whose cores differ.
add_library(vl_sched SHARED vl_sched.cpp)
target_compile_options(vl_sched PRIVATE -Os)
target_link_options(vl_sched PRIVATE -Wl,--gc-sections -s "-Wl,-z,max-page-size=16384")
