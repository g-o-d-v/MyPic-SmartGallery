# 1. 提升最低版本要求，与你 app/build.gradle 中指定的版本匹配
cmake_minimum_required(VERSION 3.22.1)

# 2. 声明项目名称 (非必须，但有助于规范)
project(MyPicNative)

# 3. 寻找当前目录下所有的 .cpp 源码文件
aux_source_directory(. SOURCES)

# 4. 优化编译参数 (保持官方的高效 C++ 编译选项)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -ffast-math -Ofast -Os -fvisibility=hidden -fvisibility-inlines-hidden -fdata-sections -ffunction-sections")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,-z,nocopyreloc")

# ==========================================================
# 5. 【重要】配置头文件包含路径 (Include Directories)
# 你需要手动在 app/src/main/cpp 下创建一个 include 文件夹，
# 并把 PaddleLite 和 OpenCV 的头文件放进去。
# ==========================================================
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/PaddleLite)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/OpenCV)


# ==========================================================
# 6. 【重要】导入你已经放在 jniLibs 里的预编译库
# 我们不再用 find_package 这种不可靠的方式，而是直接精准定位
# ==========================================================
# 导入 Paddle Lite 动态库
add_library(paddle_light_api_shared SHARED IMPORTED)
set_target_properties(paddle_light_api_shared PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpaddle_light_api_shared.so)

# 导入 OpenCV 动态库 (你需要把对应的 OpenCV .so 放到 jniLibs 下)
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_java4.so)


# 7. 生成你自己的本地库 libNative.so
add_library(Native SHARED ${SOURCES})

# 8. 查找 Android 系统的日志库
find_library(log-lib log)

# 9. 链接所有的库到你的 Native 库中
target_link_libraries(Native
        paddle_light_api_shared
        opencv_java4
        GLESv2
        EGL
        jnigraphics
        ${log-lib}
)