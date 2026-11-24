cmake_minimum_required(VERSION 3.22.1)
project(cadfield_dwg C)

# LibreDWG precompilado por cpp/build-libredwg.sh (CI) en prebuilt/<ABI>/
set(PRE ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

if(EXISTS ${PRE}/lib/libredwg.a)
  add_library(cadfield_dwg SHARED dwg_bridge.c)
  target_include_directories(cadfield_dwg PRIVATE ${PRE}/include)
  target_link_libraries(cadfield_dwg ${PRE}/lib/libredwg.a m log)
  target_link_options(cadfield_dwg PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
  message(WARNING "LibreDWG no precompilado para ${ANDROID_ABI}: soporte DWG deshabilitado")
  add_library(cadfield_dwg SHARED dwg_stub.c)
endif()
