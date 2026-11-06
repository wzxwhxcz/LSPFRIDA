cmake_minimum_required(VERSION 3.22.1)

project("target")

add_library(target SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(target ${log-lib})
