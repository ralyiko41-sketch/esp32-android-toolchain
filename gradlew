#!/bin/sh
# Gradle wrapper script
APP_HOME=$(dirname "$(readlink -f "$0")")
exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@" 2>/dev/null || \
  gradle "$@"
