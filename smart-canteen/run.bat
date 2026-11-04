@echo off
REM Compile and run Smart Canteen (Windows). Run from the project folder.
if not exist out mkdir out
javac -cp "lib/*" -d out src\*.java
if errorlevel 1 exit /b 1
java -cp "out;lib/*" Main
