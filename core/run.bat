@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist out\classes mkdir out\classes
javac -encoding UTF-8 -d out\classes src\citygen\*.java
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -cp out\classes citygen.Main %*
