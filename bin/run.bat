@echo off
setlocal
cd /d "%~dp0..\ruoyi-admin\target"
set "JAVA_OPTS=-Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m"
java %JAVA_OPTS% -jar ruoyi-admin.jar
endlocal
