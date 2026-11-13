@echo off
setlocal
cd /d "%~dp0"

if "%~1"=="" (
  echo.
  echo Usage: push-to-github.bat https://github.com/YOUR_NAME/REPO.git
  echo.
  echo First create an EMPTY repository on GitHub:
  echo   do NOT tick README, license or .gitignore
  echo Then pass its clone URL as the parameter.
  echo.
  pause
  exit /b 1
)

if not exist ".git" git init -q
git add .
git commit -m "Initial commit: cast-phone screen casting" 2>nul
git branch -M main
git remote remove origin 2>nul
git remote add origin "%~1"
echo.
echo Pushing to %~1 ...
git push -u origin main
if errorlevel 1 (
  echo.
  echo Push FAILED. Sign in to GitHub when prompted, or if the repo already has
  echo files, run: git pull --rebase origin main   then run this script again.
) else (
  echo.
  echo Pushed. Open the Actions tab of your repository to watch the APK build.
)
pause
