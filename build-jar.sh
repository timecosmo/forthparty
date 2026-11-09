#!/bin/sh
# forthparty.jar 만들기 (JDK 17+ 필요). 만든 jar는 data 폴더와 같은 곳에 두고 더블클릭으로 실행
cd "$(dirname "$0")"
rm -rf out && javac -encoding UTF-8 --release 17 -d out src/forthparty/*.java \
  && jar --create --file forthparty.jar --main-class forthparty.App -C out . \
  && echo "forthparty.jar 생성 완료"
