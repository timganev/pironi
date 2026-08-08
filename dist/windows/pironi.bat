@echo off
setlocal
set "PIRONI_DIR=%~dp0"

if not exist "%PIRONI_DIR%runtime\bin\java.exe" (
  echo Pironi runtime is missing: %PIRONI_DIR%runtime\bin\java.exe
  exit /b 1
)

"%PIRONI_DIR%runtime\bin\java.exe" -jar "%PIRONI_DIR%pironi.jar" ^
  --workspace "%USERPROFILE%\Documents\PironiWorkspace" ^
  --search-roots "%USERPROFILE%" ^
  --pironi-home "%PIRONI_DIR%.pironi" ^
  --personal-context allow ^
  %*
exit /b %ERRORLEVEL%
