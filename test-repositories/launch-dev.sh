#!/bin/sh

# Tests should symlink their `wemi` launchers to this file to run on development versions of Wemi

wemi_root="$(dirname "$0")"
wemi_home="$(dirname "$0")/../.."
wemi_launcher="${wemi_home}/src/launcher.sh"
wemi_dist="${wemi_home}/build/cache/-distribution-archive"

if [ ! -d "$wemi_dist" ]; then
	"${wemi_home}/wemi" 'distributionArchive'
fi

export WEMI_DIST="$wemi_dist"
export WEMI_ROOT="$wemi_root"

exec "$wemi_launcher" "$@"