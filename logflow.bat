@echo off
rem Kullanim: logflow.bat --config configs\error-report.yaml
set "CLASSES=%~dp0build\classes"
if not exist "%CLASSES%" mkdir "%CLASSES%"
pushd "%~dp0src\main\java"
javac -encoding UTF-8 -d "%CLASSES%" logflow\*.java logflow\config\*.java logflow\plugins\*.java
if errorlevel 1 (popd & exit /b 1)
popd
xcopy /E /I /Y /Q "%~dp0src\main\resources" "%CLASSES%" >nul
java -cp "%CLASSES%" logflow.Main %*
