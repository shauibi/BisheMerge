cmake_minimum_required(VERSION 3.22.1)

project("llm_infer")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MNN paths
set(MNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../MNN")
set(MNN_INCLUDE_DIR "${MNN_ROOT}/include")
set(MNN_LLM_INCLUDE_DIR "${MNN_ROOT}/transformers/llm/engine/include")
set(MNN_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a")

# Include directories
include_directories(${MNN_INCLUDE_DIR})
include_directories(${MNN_LLM_INCLUDE_DIR})
include_directories("${MNN_ROOT}/transformers/llm/engine/src")
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/llm)
include_directories("${MNN_ROOT}/3rd_party/flatbuffers/include")
include_directories("${MNN_ROOT}/3rd_party/rapidjson")
include_directories("${MNN_ROOT}/3rd_party")

# Create shared library
add_library(
    llm_infer
    SHARED
    llm_infer_jni.cpp
    ${MNN_ROOT}/transformers/llm/engine/src/tokenizer.cpp
)

# Import MNN library
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_LIB_DIR}/libMNN.so")

# Link against Android logging library
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    llm_infer
    MNN
    ${log-lib}
    ${android-lib}
)
