@echo off
REM Machine JAVA_HOME points to JDK 8, but Gradle 9 needs JDK 17+ to run.
REM The JDK used for compilation is configured separately via the toolchain in build.gradle.kts.
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
call "%~dp0gradlew.bat" %*
exit /b %ERRORLEVEL%
