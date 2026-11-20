@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "SRC=%~dp0."
if not exist "%SRC%\app\build.gradle" goto :missing
set "REMOTE=%~1"
if not defined REMOTE set /p "REMOTE=Existing GitHub repository URL: "
if not defined REMOTE exit /b 2
set "RELEASE_TAG=%~2"
if not defined RELEASE_TAG set "RELEASE_TAG=v2.1"
git check-ref-format "refs/tags/%RELEASE_TAG%" >nul || exit /b 2
if not "%RELEASE_TAG:~0,1%"=="v" (
  echo Release tag must start with v.
  exit /b 2
)
set "CLONE=%TEMP%\PerfMon-deploy-%RANDOM%-%RANDOM%"
if exist "%CLONE%" exit /b 2
git clone -- "%REMOTE%" "%CLONE%" || exit /b 1
pushd "%CLONE%" || exit /b 1
git rev-parse --verify HEAD >nul 2>&1 || goto :empty
for /f "delims=" %%B in ('git symbolic-ref --short HEAD') do set "BRANCH=%%B"
if not defined BRANCH goto :failed

rem Stage only the Android app, its build files and the release workflow.
rem The alternate working tree is the unpacked source. The user's own clone is untouched.
git --work-tree="%SRC%" add -A -f -- app/src/main app/src/test || goto :failed
git --work-tree="%SRC%" add -f -- app/build.gradle app/.gitignore build.gradle settings.gradle gradle.properties gradlew gradlew.bat gradle/wrapper .github/workflows/build.yml README.md CHANGELOG.md LICENSE .gitignore deploy.cmd kurulum.cmd || goto :failed
git --work-tree="%SRC%" update-index --chmod=+x gradlew || goto :failed
git diff --cached --quiet
if errorlevel 1 (
  git --no-pager diff --cached --stat
  git commit -m "Release PerfMon+ 2.1" || goto :failed
)
git show-ref --verify --quiet "refs/tags/%RELEASE_TAG%"
if errorlevel 1 (
  git tag "%RELEASE_TAG%" HEAD || goto :failed
) else (
  for /f %%H in ('git rev-parse HEAD') do set "HEAD_HASH=%%H"
  for /f %%H in ('git rev-list -1 "%RELEASE_TAG%"') do set "TAG_HASH=%%H"
  call :check_tag || goto :failed
)
git push --atomic origin "HEAD:refs/heads/%BRANCH%" "refs/tags/%RELEASE_TAG%" || goto :failed
echo Published %BRANCH% and %RELEASE_TAG%. Check Actions for the signed APK.
popd
exit /b 0

:check_tag
if "%HEAD_HASH%"=="%TAG_HASH%" exit /b 0
echo Tag %RELEASE_TAG% already points to another commit. Nothing was pushed.
echo Run deploy.cmd again with a free tag, e.g. deploy.cmd "repository-URL" v2.1-build2
exit /b 1

:empty
echo This script expects an existing repository with a default branch and at least one commit.
:failed
echo Stopped. Working copy: "%CLONE%"
popd
exit /b 1

:missing
echo Extract the complete source ZIP before running deploy.cmd.
exit /b 2
