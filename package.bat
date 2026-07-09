@echo off
setlocal enabledelayedexpansion
echo === MAA Knowledge Builder - Package Script ===

REM --- Find JavaFX SDK ---
if not "%JAVAFX_SDK%"=="" goto FOUND
if exist "D:\javafx-sdk-21.0.11\lib" (set "JAVAFX_SDK=D:\javafx-sdk-21.0.11" & goto FOUND)
if exist "C:\javafx-sdk-21.0.11\lib" (set "JAVAFX_SDK=C:\javafx-sdk-21.0.11" & goto FOUND)

echo [ERROR] JAVAFX_SDK is not set.
pause
exit /b 1

:FOUND
echo JAVAFX_SDK = %JAVAFX_SDK%

REM --- Clean ---
if exist "target\dist" rmdir /s /q "target\dist"
if exist "target\jpackage-staging" rmdir /s /q "target\jpackage-staging"
if exist "build\package" rmdir /s /q "build\package"

echo [1/2] Building project...
call mvnw clean package -DskipTests -q
if errorlevel 1 (
    echo [ERROR] Build failed!
    pause
    exit /b 1
)

echo [2/2] Creating portable package with jpackage...

REM --- Staging dir: ONLY the shaded JAR (no JavaFX) ---
REM jpackage puts everything in --input onto classpath.
REM JavaFX must stay OFF classpath to avoid module conflicts.
mkdir "target\jpackage-staging"
copy /y "target\MAA-Knowledge-Builder-shaded.jar" "target\jpackage-staging\" >nul

REM --- Run jpackage ---
jpackage ^
  --type app-image ^
  --name "MAA-Knowledge-Builder" ^
  --description "MAA Agentic RAG Knowledge Builder" ^
  --vendor "Yagen" ^
  --app-version "1.0.0" ^
  --input target\jpackage-staging ^
  --main-jar MAA-Knowledge-Builder-shaded.jar ^
  --main-class yagen.waitmydawn.kb.MaaKnowledgeBuilderApp ^
  --java-options "-Xmx1g" ^
  --java-options "-Djava.library.path=$APPDIR\javafx" ^
  --java-options "-Dprism.verbose=true" ^
  --java-options "--module-path=$APPDIR\javafx" ^
  --java-options "--add-modules=javafx.controls,javafx.fxml,javafx.graphics" ^
  --dest build\package

if errorlevel 1 (
    echo [ERROR] jpackage failed!
    pause
    exit /b 1
)

REM --- Manually copy JavaFX JARs + DLLs into app\javafx\ ---
REM They go on --module-path, NOT on classpath.
echo Bundling JavaFX runtime...
set "APP_DIR=build\package\MAA-Knowledge-Builder\app"
mkdir "%APP_DIR%\javafx"
copy /y "%JAVAFX_SDK%\lib\*.jar" "%APP_DIR%\javafx\" >nul
if exist "%JAVAFX_SDK%\bin\*.dll" (
    copy /y "%JAVAFX_SDK%\bin\*.dll" "%APP_DIR%\javafx\" >nul
)

echo.
echo === Done ===
echo Output: build\package\MAA-Knowledge-Builder\
echo Run:   build\package\MAA-Knowledge-Builder\MAA-Knowledge-Builder.exe
pause
