#!/bin/sh

log() { echo "$@" 1>&2; }
fail() { log "$@"; exit 1; }

# Change directory to the main folder
cd "$(dirname "$0")/.." || fail "Failed initial dir change"

wemi_home="$(pwd)"
log "Working from home: $wemi_home"

# Build the archive files in Wemi
./wemi 'clean; distributionArchive'

# Create empty output directory
dist_dir="${wemi_home}/build/dist"
if [ -d "$dist_dir" ]; then
	rm -rf "$dist_dir"
fi
mkdir -p "$dist_dir" || fail "mkdir build/dist"

# Package launcher script
cp "${wemi_home}/src/launcher.sh" "${wemi_home}/build/dist/wemi"
chmod +x "${wemi_home}/build/dist/wemi"

# Package the files
cd "${wemi_home}/build/cache/-distribution-archive" || fail "cd to distribution archive"
tar -c -v -z -f "${wemi_home}/build/dist/wemi.tar.gz" ./* || fail "tar ($?)"

log "Done"
echo "${wemi_home}/build/dist"
