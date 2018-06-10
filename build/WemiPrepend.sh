#!/usr/bin/env bash

WEMI_RELOAD_CODE="6"
WEMI_EXIT_CODE=${WEMI_RELOAD_CODE}
while [[ ${WEMI_EXIT_CODE} == ${WEMI_RELOAD_CODE} ]]; do
	java -jar "$0" --root="$(dirname "$0")" --reload-supported "$@"
	WEMI_EXIT_CODE="$?"
done

exit ${WEMI_EXIT_CODE}
#WEMI>