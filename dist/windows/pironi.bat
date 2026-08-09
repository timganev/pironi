@echo off
setlocal
set "PIRONI_DIR=%~dp0"
set "PIRONI_DEFAULT_WORKSPACE=%USERPROFILE%"
set "PIRONI_DEFAULT_SEARCH_ROOTS=%USERPROFILE%"
set "PIRONI_DEFAULT_HOME=%PIRONI_DIR%.pironi"
set "PIRONI_DEFAULT_PERSONAL_CONTEXT=allow"
set "PIRONI_DEFAULT_SHELL_SCOPE=user"

if not exist "%PIRONI_DIR%runtime\bin\java.exe" (
  echo Pironi runtime is missing: %PIRONI_DIR%runtime\bin\java.exe
  exit /b 1
)

"%PIRONI_DIR%runtime\bin\java.exe" -jar "%PIRONI_DIR%pironi.jar" %*
exit /b %ERRORLEVEL%
