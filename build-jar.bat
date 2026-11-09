@echo off
rem Build forthparty.jar (needs JDK 17+). Keep the jar next to the data folder and double-click it.
cd /d %~dp0
if exist out rmdir /s /q out
javac -encoding UTF-8 --release 17 -d out src\forthparty\*.java && jar --create --file forthparty.jar --main-class forthparty.App -C out . && echo Done: forthparty.jar
pause
