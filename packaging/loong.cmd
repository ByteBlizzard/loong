@echo off
rem Loong single-command entry (Windows).
rem
rem   loong                  start/reuse the daemon of the current directory (= loong up)
rem   loong up               start foreground (Ctrl+C to stop); reuse if already running
rem   loong stop             stop the daemon of the current directory
rem   loong mcp              MCP stdio bridge (spawned by the coding agent)
rem   loong list             list all running Loong daemons on this machine
rem   loong check^|build^|run^|test ^<file^>   compiler commands
rem   loong help             full usage
rem
rem Project dir defaults to the CURRENT WORKING DIRECTORY; override with
rem --project ^<dir^> or the LOONG_PROJECT environment variable.
rem Always runs java -jar loong.jar next to this script.
rem NOTE: keep this file ASCII-only: cmd may run under a legacy OEM codepage (e.g.
rem GBK) where UTF-8 comment bytes can swallow newlines and execute comment text.
setlocal
set "DIR=%~dp0"
if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java"
)
"%JAVA%" -jar "%DIR%loong.jar" %*
endlocal & exit /b %ERRORLEVEL%
