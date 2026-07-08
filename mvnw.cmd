@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAVA_HOME=%SCRIPT_DIR%.tools\jdk21"
set "M2_HOME=%SCRIPT_DIR%.tools\maven"
set "PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%"
call "%M2_HOME%\bin\mvn.cmd" %*
endlocal
