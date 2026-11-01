@echo off
set "SCRIPT_DIR=%~dp0"
set "MAVEN_CMD=%SCRIPT_DIR%..\.tools\apache-maven-3.9.9\bin\mvn.cmd"
if exist "%MAVEN_CMD%" (
    call "%MAVEN_CMD%" %*
) else (
    mvn %*
)
