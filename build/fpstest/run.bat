@echo off
rem ============================================================
rem  FpsMath offline assertions (pure javac + java, no Android)
rem
rem  Unlike vidtest, no prepare.py step is needed: FpsMath (and the
rem  other pure classes compiled here: SysMetrics / LocalLines /
rem  SceneRegistry / ClipNaming / PackFormat / CharPicker / Appearance /
rem  ContentInset / WebSearch / WebFetch / WebPrompt / QueryClean /
rem  Weather / WeatherCities / Json / SearchProvider / WebCache /
rem  ClipList / Endpoint / ModelFile / LocalPrompt) were
rem  deliberately written with ZERO Android dependencies, so the files
rem  compiled here are exactly the ones shipped in the APK.
rem
rem  NOTE: keep this file ASCII-only (cmd mis-decodes non-ASCII).
rem ============================================================
setlocal
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist out mkdir out

javac -encoding UTF-8 -d out ..\..\src\com\deepseek\pet\FpsMath.java ..\..\src\com\deepseek\pet\SysMetrics.java ..\..\src\com\deepseek\pet\LocalLines.java ..\..\src\com\deepseek\pet\SceneRegistry.java ..\..\src\com\deepseek\pet\ClipNaming.java ..\..\src\com\deepseek\pet\PackFormat.java ..\..\src\com\deepseek\pet\PetCharacter.java ..\..\src\com\deepseek\pet\CharPicker.java ..\..\src\com\deepseek\pet\Appearance.java ..\..\src\com\deepseek\pet\ContentInset.java ..\..\src\com\deepseek\pet\WebSearch.java ..\..\src\com\deepseek\pet\WebFetch.java ..\..\src\com\deepseek\pet\WebPrompt.java ..\..\src\com\deepseek\pet\QueryClean.java ..\..\src\com\deepseek\pet\Weather.java ..\..\src\com\deepseek\pet\WeatherCities.java ..\..\src\com\deepseek\pet\Json.java ..\..\src\com\deepseek\pet\SearchProvider.java ..\..\src\com\deepseek\pet\WebCache.java ..\..\src\com\deepseek\pet\ClipList.java ..\..\src\com\deepseek\pet\Endpoint.java ..\..\src\com\deepseek\pet\ClickSound.java ..\..\src\com\deepseek\pet\ModelFile.java ..\..\src\com\deepseek\pet\LocalPrompt.java ..\..\src\com\deepseek\pet\PeekPose.java com\deepseek\pet\FpsTest.java
if errorlevel 1 exit /b 1

java -cp out com.deepseek.pet.FpsTest
exit /b %errorlevel%
