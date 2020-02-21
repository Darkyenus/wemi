#!/bin/sh

# Tests should symlink their `wemi` launchers to this file to run on development versions of Wemi

wemi_root="$(dirname "$0")"
wemi_home="$(dirname "$0")/../.."
# It does not matter that it is a template - only version is templated, and we sidestep that
wemi_launcher="${wemi_home}/src/launcher-template.sh"
wemi_dist="${wemi_home}/build/cache/-distribution-archive"

if [ ! -d "$wemi_dist" ]; then
	"${wemi_home}/wemi" 'distributionArchive'
fi

export WEMI_DIST="$wemi_dist"
export WEMI_ROOT="$wemi_root"
export WEMI_JAVA_OPTS="-ea"

exec "$wemi_launcher" "$@"