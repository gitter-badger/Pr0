#!/bin/sh

cd app/src/main/jni
./build.sh clean
./build.sh build_all
./build.sh clean
