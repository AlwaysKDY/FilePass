@echo off
set "GRADLE_USER_HOME=D:\gradle_home"
cd /d D:\FilePass\android-client
call gradlew.bat clean testDebugUnitTest --no-daemon --rerun-tasks
echo EXIT_CODE:%ERRORLEVEL%
