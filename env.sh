#!/usr/bin/env bash
# Local dev environment: JDK 21 + Gradle pinned outside the repo (see README).
export JAVA_HOME="$HOME/.local/jdks/temurin-21"
export PATH="$JAVA_HOME/bin:$PATH"
