#!/bin/sh
# 컴파일 후 실행 (JDK 17+ 필요)
cd "$(dirname "$0")"
javac -encoding UTF-8 -d out src/forthparty/*.java && java -cp out forthparty.App data
