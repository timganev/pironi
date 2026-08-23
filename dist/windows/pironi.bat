@echo off
setlocal
set "PIRONI_DIR=%~dp0"

rem Only a launcher knows where its own bundle is. Pironi plants the skills carried in
rem %PIRONI_DIR%skills into the store it reads, and --portable uses it to keep everything here.
set "PIRONI_BUNDLE_DIR=%PIRONI_DIR:~0,-1%"

rem Launched by double-click the working directory is the bundle itself, and an agent whose
rem workspace is its own program folder will write into it. Started from a shell the directory
rem the person stood in is the one they meant, which is how Pironi behaves everywhere else.
if /i "%CD%\"=="%PIRONI_DIR%" (
  if not defined PIRONI_DEFAULT_WORKSPACE set "PIRONI_DEFAULT_WORKSPACE=%USERPROFILE%"
)

if not exist "%PIRONI_DIR%runtime\bin\java.exe" (
  echo Pironi runtime is missing: %PIRONI_DIR%runtime\bin\java.exe
  exit /b 1
)

rem Pironi writes UTF-8 bytes to stdout whatever the console is set to, so a console left on the
rem legacy OEM page renders every non-ASCII answer as mojibake - correct bytes, unreadable screen.
rem Windows Terminal hides this; plain cmd.exe does not, which is where it was found. The previous
rem page is restored on the way out, because the console belongs to the person, not to this run.
for /f "tokens=2 delims=:" %%a in ('chcp') do set "PIRONI_CP=%%a"
set "PIRONI_CP=%PIRONI_CP: =%"
set "PIRONI_CP=%PIRONI_CP:.=%"
chcp 65001 >nul 2>&1

"%PIRONI_DIR%runtime\bin\java.exe" -jar "%PIRONI_DIR%pironi.jar" %*
rem Read before restoring the page: chcp overwrites ERRORLEVEL with its own.
set "PIRONI_EXIT=%ERRORLEVEL%"
if defined PIRONI_CP chcp %PIRONI_CP% >nul 2>&1
exit /b %PIRONI_EXIT%
