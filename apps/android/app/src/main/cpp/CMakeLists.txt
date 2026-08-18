cmake_minimum_required(VERSION 3.22.1)
project(glmocr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MNN 3.6.1 官方预编译库（arm64-v8a），随 jniLibs 打包进 APK。
set(MNN_JNI_LIBS ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES IMPORTED_LOCATION ${MNN_JNI_LIBS}/libMNN.so)

add_library(mnn_express SHARED IMPORTED)
set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION ${MNN_JNI_LIBS}/libMNN_Express.so)

add_library(mnn_llm SHARED IMPORTED)
set_target_properties(mnn_llm PROPERTIES IMPORTED_LOCATION ${MNN_JNI_LIBS}/libllm.so)

# 薄 JNI wrapper：只封装 Llm 的 load / submit(流式) / reset / release / config。
add_library(glmocr SHARED glm_ocr_jni.cpp)
target_include_directories(glmocr PRIVATE ${CMAKE_SOURCE_DIR}/include)

find_library(log-lib log)

# 链接顺序：wrapper → llm → express → mnn。16KB 页对齐（Android 15+ 要求）。
target_link_libraries(glmocr mnn_llm mnn_express mnn ${log-lib})
target_link_options(glmocr PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384)
