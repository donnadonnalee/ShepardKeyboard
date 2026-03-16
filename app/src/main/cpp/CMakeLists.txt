cmake_minimum_required(VERSION 3.22.1)
project("shepard_engine")

find_package(oboe REQUIRED CONFIG)

add_library(shepard_engine SHARED
    NativeAudioEngine.cpp
    ShepardSynthesizer.cpp
)

target_link_libraries(shepard_engine
    oboe::oboe
    log
    android
)
