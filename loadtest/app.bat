@echo off
chcp 65001 >nul
cd /d %~dp0
python -c "import tkinter" 2>nul
if errorlevel 1 (
  echo tkinter 를 찾을 수 없습니다. Python 을 다시 설치하면서 "tcl/tk and IDLE" 항목을 선택하세요.
  pause
  exit /b 1
)
rem pythonw = 콘솔 창 없이 실행. 없으면 최소화된 콘솔로 실행한다.
where pythonw >nul 2>nul
if errorlevel 1 (
  start "" /min python app\jejari_desktop.py
) else (
  start "" pythonw app\jejari_desktop.py
)
