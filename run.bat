@echo off
cd /d %~dp0
javac -encoding UTF-8 -d out src\forthparty\*.java && javaw -cp out forthparty.App data
