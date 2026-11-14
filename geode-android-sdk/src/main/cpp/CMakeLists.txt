cmake_minimum_required(VERSION 3.21)

project(geode_android_sdk LANGUAGES CXX)

add_library(geode_android_sdk SHARED geode_android_sdk.cpp geode_android_graphics.cpp)

target_compile_features(geode_android_sdk PRIVATE cxx_std_20)
target_include_directories(geode_android_sdk PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

set_target_properties(geode_android_sdk PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

target_link_libraries(geode_android_sdk PRIVATE EGL GLESv3 log)
