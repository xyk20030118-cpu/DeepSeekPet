@echo off
rem ============================================================
rem  VideoFitMath offline assertions (pure javac + java, no Android)
rem
rem  Why copy sources into gen/ before compiling:
rem    VideoFitMath references Prefs.FIT_* constants, but Prefs is an
rem    Android class (imports android.content.Context), so it cannot be
rem    loaded without an Android runtime. This script copies the source
rem    into gen/ and substitutes the Prefs.FIT_* constants with literals.
rem    The real source file is not modified -- the math under test is
rem    exactly the one compiled into the app.
rem
rem  NOTE: keep this file ASCII-only. A .bat containing non-ASCII text
rem  gets mis-decoded by cmd and each stray line is executed as a
rem  command ("'...' is not recognized as an internal or external
rem  command"), which floods the output with noise.
rem ============================================================
setlocal
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

python prepare.py
if errorlevel 1 exit /b 1

javac -encoding UTF-8 -d out gen\com\deepseek\pet\*.java
if errorlevel 1 exit /b 1

java -cp out com.deepseek.pet.VideoFitTest
if errorlevel 1 exit /b 1

echo.
java -cp out com.deepseek.pet.SourcePairingTest ..\..\src\com\deepseek\pet
exit /b %errorlevel%