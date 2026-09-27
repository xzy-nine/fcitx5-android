# custom: 离线流式 zipformer2 ASR 原生库（libasr_jni.so）
#
# 源码移植自 Xime（https://github.com/ximeiorg/xime，GPL-3.0-or-later），
# 见仓库根 NOTICE.md 与各源文件头部的 SPDX 标注。
#
# 第三方：
#   * kaldi-native-fbank 1.22.3 —— Apache-2.0，随源码收录于 third_party/knf/
#   * kissfft 131.2.0           —— BSD-3-Clause，随源码收录于 third_party/knf/
#   * ONNX Runtime 1.28.0       —— MIT，C++ 头文件随源码收录于 onnxruntime/include/，
#                                  libonnxruntime.so 由 Gradle 从 Maven Central 抽取
#                                  （com.microsoft.onnxruntime:onnxruntime-android:1.28.0）
#
# 本目录由 app/src/main/cpp/CMakeLists.txt 在所有 fcitx5 子目录之后 add_subdirectory，
# 因此这里 set(BUILD_SHARED_LIBS OFF) 不会影响 fcitx5 侧目标。

set(KNF_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/knf")
set(KNF_DIR "${KNF_ROOT}/kaldi-native-fbank-1.22.3")

set(ONNXRUNTIME_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime/include")

if(NOT DEFINED ONNXRUNTIME_LIB_DIR)
    message(FATAL_ERROR
            "ONNXRUNTIME_LIB_DIR is not set. It must point to a directory containing "
            "<abi>/libonnxruntime.so (e.g. build/onnxruntime/aar/jni). "
            "See extractOnnxRuntime in app/build.gradle.kts.")
endif()
set(_onnxruntime_lib "${ONNXRUNTIME_LIB_DIR}/${ANDROID_ABI}/libonnxruntime.so")
if(NOT EXISTS "${_onnxruntime_lib}")
    message(FATAL_ERROR "libonnxruntime.so not found at ${_onnxruntime_lib}")
endif()

# ---------------------------------------------------------------------------
# kaldi-native-fbank（静态；kissfft 由 FETCHCONTENT_SOURCE_DIR_KISSFFT 指向随源码收录的副本）
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF)
set(FETCHCONTENT_SOURCE_DIR_KISSFFT "${KNF_ROOT}/kissfft")
add_subdirectory(${KNF_DIR} knf_build EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# ONNX Runtime（imported shared）
# ---------------------------------------------------------------------------
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
        IMPORTED_LOCATION "${_onnxruntime_lib}"
        INTERFACE_INCLUDE_DIRECTORIES "${ONNXRUNTIME_INCLUDE_DIR}")

# ---------------------------------------------------------------------------
# asr_jni
#
# 原 Xime 把 onnx_env 单独做成 SHARED（因为 onnx_jni / handwriting_jni 也复用）；
# 本移植只有 ASR 一个使用者，故直接编进 asr_jni，避免多打包一个 libonnx_env.so。
# ---------------------------------------------------------------------------
add_library(asr_jni SHARED
        asr_jni.cc
        feature_extractor.cc
        streaming_recognizer.cc
        zipformer2_model.cc
        onnx_env.cc
        )
target_include_directories(asr_jni PRIVATE
        "${ONNXRUNTIME_INCLUDE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${KNF_DIR}"
        )
target_link_libraries(asr_jni
        onnxruntime
        kaldi-native-fbank-core
        log
        )
# Android 15+ 16 KB page size
target_link_options(asr_jni PRIVATE "-Wl,-z,max-page-size=16384")
