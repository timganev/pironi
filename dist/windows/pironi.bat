@echo off
setlocal
set "PIRONI_DIR=%~dp0"

if not exist "%PIRONI_DIR%runtime\bin\java.exe" (
  echo Pironi runtime is missing: %PIRONI_DIR%runtime\bin\java.exe
  exit /b 1
)

"%PIRONI_DIR%runtime\bin\java.exe" -jar "%PIRONI_DIR%pironi.jar" %*
exit /b %ERRORLEVEL%
