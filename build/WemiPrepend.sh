#!/usr/bin/env bash

WEMI_JAVA_OPTS=""

if [[ $1 =~ ^--debug$|^--debug-suspend$|^--debug=([0-9]+)$|^--debug-suspend=([0-9]+)$ ]]; then
    if [[ "${BASH_REMATCH[2]}" != "" ]]; then
        WEMI_JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=${BASH_REMATCH[2]}"
    elif [[ "${BASH_REMATCH[1]}" != "" ]]; then
        WEMI_JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${BASH_REMATCH[1]}"
    elif [[ ${BASH_REMATCH[0]} == "--debug" ]]; then
        WEMI_JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
    else
        WEMI_JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
    fi

    shift
fi

WEMI_RELOAD_CODE="6"
WEMI_EXIT_CODE=${WEMI_RELOAD_CODE}
while [[ ${WEMI_EXIT_CODE} == ${WEMI_RELOAD_CODE} ]]; do
	java ${WEMI_JAVA_OPTS} -jar "$0" --root="$(dirname "$0")" --reload-supported "$@"
	WEMI_EXIT_CODE="$?"
done

exit ${WEMI_EXIT_CODE}
#WEMI>