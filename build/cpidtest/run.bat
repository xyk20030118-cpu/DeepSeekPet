@echo off
rem ============================================================
rem  ChatProvider capability table + balance parsing assertions.
rem
rem  Why this suite exists:
rem    - ChatProvider decides which API parameters may be sent. If the
rem      table says FIXED but a request still carries temperature, Kimi
rem      returns: HTTP 400 invalid temperature: only 1 is allowed
rem      (a failure a real user already hit).
rem    - ChatBalance parses each provider's balance JSON by path. A wrong
rem      path never throws -- it just shows "no balance data", which the
rem      user cannot self-diagnose.
rem
rem  Both classes under test are pure Java (no android imports), so the
rem  REAL sources are compiled -- no slicing, no stand-ins.
rem
rem  JSON runtime: lib\json.jar (real org.json from Maven), NOT
rem  toolchain\android.jar. android.jar's org.json is a STUB whose method
rem  bodies are just `throw new RuntimeException("Stub!")` -- it compiles
rem  but cannot run. Using it looked fine until the first JSONObject was
rem  constructed at runtime.
rem
rem  NOTE: keep this file ASCII-only (cmd mis-decodes non-ASCII).
rem ============================================================
setlocal
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set JSON_JAR=lib\json.jar

if not exist "%JSON_JAR%" (
  echo [FAIL] missing %JSON_JAR% ^(real org.json, needed to RUN the tests^)
  echo        download: https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
  exit /b 1
)

python prepare.py
if errorlevel 1 exit /b 1

rem Compile against android.jar only if a stub symbol leaks in; normally the
rem pure sources need nothing but json.jar.
javac -encoding UTF-8 -cp "%JSON_JAR%" -d out gen\com\deepseek\pet\*.java
if errorlevel 1 exit /b 1

java -cp "out;%JSON_JAR%" com.deepseek.pet.ChatProviderTest
if errorlevel 1 exit /b 1

echo.
java -cp "out;%JSON_JAR%" com.deepseek.pet.BalanceTest
if errorlevel 1 exit /b 1

echo.
java -cp "out;%JSON_JAR%" com.deepseek.pet.BackNavTest
exit /b %errorlevel%
