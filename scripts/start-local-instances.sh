#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_VALUE="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
BOOT_PLUGIN="org.springframework.boot:spring-boot-maven-plugin:3.0.7:run"
LOG_DIR="${ROOT_DIR}/logs/local-instances"

mkdir -p "${LOG_DIR}"

run_service() {
  local module="$1"
  local port="$2"
  local log_file="${LOG_DIR}/${module}-${port}.log"

  echo "Starting ${module} on port ${port}; log: ${log_file}"
  (
    cd "${ROOT_DIR}"
    export JAVA_HOME="${JAVA_HOME_VALUE}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    mvn -pl "${module}" "${BOOT_PLUGIN}" \
      -Dspring-boot.run.arguments="--server.port=${port}"
  ) >"${log_file}" 2>&1 &
}

# Gateway remains the single public entry. Admin and project can be scaled out.
run_service project 8001
run_service project 8101
run_service admin 8002
run_service admin 8102
run_service gateway 8000

echo "All local instances are starting. Check Nacos: http://localhost:8848/nacos"
