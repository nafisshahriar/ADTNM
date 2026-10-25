@echo off
REM ====================================================================
REM  Windows 1-Click Installer for Autonomic DTNMA Framework
REM ====================================================================
python "%~dp0install.py" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Installation failed with error code %ERRORLEVEL%.
)
pause
