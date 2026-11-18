cmake_minimum_required(VERSION 3.22.1)
project(aml_fel CXX)
# Vision's FEL native code (aml_fel_native.cpp), unchanged except the JNI package.
add_library(aml_fel SHARED aml_fel_native.cpp)
target_compile_options(aml_fel PRIVATE -Os -fvisibility=default)
target_link_libraries(aml_fel android log)
target_link_options(aml_fel PRIVATE -Wl,--gc-sections -s)
