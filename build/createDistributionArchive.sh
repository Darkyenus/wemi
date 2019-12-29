#!/bin/sh

log() { echo "$@" 1>&2; }
fail() { log "$@"; exit 1; }

# Change directory to the main folder
cd "$(dirname "$0")/.." || fail "Failed initial dir change"

wemi_home="$(pwd)"
log "Working from home: $wemi_home"

# Wemi version tags always begin with 'v'
last_wemi_version_tag="$(git describe --tags --match='v*' --abbrev=0)" || fail "Could not find Wemi version"
version_commit=$(git rev-list --max-count=1 "$last_wemi_version_tag") || fail "Could not get version_commit"
latest_commit=$(git rev-list --max-count=1 master) || fail "Could not get latest commit"

last_wemi_version_major=$(echo "$last_wemi_version_tag" | sed 's/v\([0-9]*\).*/\1/')
last_wemi_version_minor=$(echo "$last_wemi_version_tag" | sed 's/v[0-9]*\.\([0-9]*\).*/\1/')

if [ "$version_commit" = "$latest_commit" ]; then
	# We are at a release commit
	wemi_version="${last_wemi_version_major}.${last_wemi_version_minor}"
else
	# We are at a SNAPSHOT commit
	wemi_version="${last_wemi_version_major}.$((last_wemi_version_minor + 1))-SNAPSHOT"
fi

log "Wemi version: $wemi_version"

export BUILT_WEMI_VERSION="$wemi_version"

# Build the archive files in Wemi
./wemi 'clean; test; clean; distributionArchive' || fail "wemi ($?)"

# Create empty output directory
dist_dir="${wemi_home}/build/dist"
if [ -d "$dist_dir" ]; then
	rm -rf "$dist_dir"
fi
mkdir -p "$dist_dir" || fail "mkdir build/dist"

# Package launcher script
sed "s/<<<WEMI_VERSION>>>/${wemi_version}/" < "${wemi_home}/src/launcher-template.sh" > "${wemi_home}/build/dist/wemi"
chmod +x "${wemi_home}/build/dist/wemi"

# Package the files
cd "${wemi_home}/build/cache/-distribution-archive" || fail "cd to distribution archive"
tar -c -v -z -f "${wemi_home}/build/dist/wemi.tar.gz" ./* || fail "tar ($?)"
cd "$wemi_home" || fail "cd back home"

# Copy IDE plugins
# TODO(jp): Ensure that the plugin is fresh by building it ourselves
intellij_plugin_path="${wemi_home}/ide-plugins/WemiForIntelliJ/WemiForIntelliJ.zip"
got_all_aux_files="true"
if [ -f "$intellij_plugin_path" ]; then
	if [ -z "$(find "$intellij_plugin_path" -type f -name "$(basename "$intellij_plugin_path")" -mtime -1 -print 2>/dev/null)" ]; then
		fail "IntelliJ plugin at '$intellij_plugin_path' found, but it is too old (>1h). Delete it or rebuild it."
	fi

	log "Found IntelliJ plugin distribution, using it"
	cp "$intellij_plugin_path" "${wemi_home}/build/dist/$(basename "$intellij_plugin_path")" || fail "cp IntelliJ plugin ($?)"
else
	log "Missing IntelliJ IDE plugin, skipping"
	got_all_aux_files="false"
fi

# Create build info document
build_info_file="${wemi_home}/build/dist/build-info.txt"
echo "Wemi $wemi_version">"$build_info_file"
echo "Git: $(git rev-parse HEAD)">>"$build_info_file"
echo "Date: $(date -u "+%Y-%m-%d %H:%M:%S")">>"$build_info_file"

# Publish to the mirrors
if [ "$1" = "--publish" ]; then
	if [ "$got_all_aux_files" != "true" ]; then
		fail "Can't publish, some auxiliary files are missing"
	fi

	# Allow only reading, unless snapshot, which can be overwritten by us
	chmod_mode="0444"
	if [ "${wemi_version%-SNAPSHOT}" != "${wemi_version}" ]; then
		# Snapshot
		chmod_mode="0644"
		sftp -b - -f "sftp://wemi@darkyen.com/wemi-versions/" <<-END_SFTP || log "Snapshot version directory already exists"
		mkdir ./${wemi_version}
END_SFTP
	else
		# Normal release
		sftp -b - -f "sftp://wemi@darkyen.com/wemi-versions/" <<-END_SFTP || fail "Version directory must not exist"
		mkdir ./${wemi_version}
END_SFTP
	fi

	sftp -b - -f "sftp://wemi@darkyen.com/wemi-versions/" <<-END_SFTP || fail "Failed to upload new Wemi version"
		put ${wemi_home}/build/dist/* ./${wemi_version}/
		chmod ${chmod_mode} ./${wemi_version}/*
END_SFTP

	log "Done publishing Wemi version $wemi_version"
else
	log "Done"
	echo "${wemi_home}/build/dist"
fi