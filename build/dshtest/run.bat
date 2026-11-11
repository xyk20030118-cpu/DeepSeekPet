@echo off
rem ============================================================
rem  DSH core offline assertions (pure javac + java, no Android).
rem
rem  Why this suite exists:
rem    The harness kernel port (com.deepseek.pet.dsh) is pure Java,
rem    so it can be compiled and RUN here on the PC with nothing but
rem    a JDK -- no device, no emulator, no android.jar. That makes the
rem    agent loop, the tool protocol and the sandbox gate testable
rem    for real instead of "asserted by grepping source".
rem
rem  NOTE: keep this file ASCII-only AND CRLF (cmd mis-parses LF and
rem  mangles non-ASCII).
rem ============================================================
setlocal
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist out mkdir out

rem -sourcepath lets javac pull the real sources (Json + the whole dsh
rem package) straight out of ..\..\src -- the same files that go into the APK.
javac -nowarn -encoding UTF-8 -source 8 -target 8 -sourcepath ..\..\src -d out DshCoreTest.java
if errorlevel 1 exit /b 1

java -cp out DshCoreTest
exit /b %errorlevel%
