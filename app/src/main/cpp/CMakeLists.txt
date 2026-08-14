cmake_minimum_required(VERSION 3.22)
project(fotolive_hd)

add_library(fotolive_hd SHARED
    native_hd.cpp
)

find_library(log-lib log)

target_link_libraries(fotolive_hd
    ${log-lib}
    jnigraphics
)
