@echo off
setlocal
set "PIRONI_DIR=%~dp0"

rem Defaults, not decisions: anything already in the environment wins. These were set
rem unconditionally before, so a user who exported PIRONI_DEFAULT_HOME had it overwritten by the
rem launcher and never found out why.
if not defined PIRONI_DEFAULT_WORKSPACE set "PIRONI_DEFAULT_WORKSPACE=%USERPROFILE%"
if not defined PIRONI_DEFAULT_SEARCH_ROOTS set "PIRONI_DEFAULT_SEARCH_ROOTS=%USERPROFILE%"
if not defined PIRONI_DEFAULT_HOME set "PIRONI_DEFAULT_HOME=%PIRONI_DIR%.pironi"
if not defined PIRONI_DEFAULT_PERSONAL_CONTEXT set "PIRONI_DEFAULT_PERSONAL_CONTEXT=allow"
if not defined PIRONI_DEFAULT_SHELL_SCOPE set "PIRONI_DEFAULT_SHELL_SCOPE=user"

if not exist "%PIRONI_DIR%runtime\bin\java.exe" (
  echo Pironi runtime is missing: %PIRONI_DIR%runtime\bin\java.exe
  exit /b 1
)

"%PIRONI_DIR%runtime\bin\java.exe" -jar "%PIRONI_DIR%pironi.jar" %*
exit /b %ERRORLEVEL%
