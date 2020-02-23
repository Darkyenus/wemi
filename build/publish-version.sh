#!/bin/sh

log() { echo "$@" 1>&2; }
fail() { log "$@"; exit 1; }

# Change directory to the main folder
cd "$(dirname "$0")/.." || fail "Failed initial dir change"

wemi_home="$(pwd)"
log "Working from home: $wemi_home"

wemi_version=$(./wemi --machine-readable-output=shell wemi/projectVersion) || fail "wemi ($?) (version)"
log "Wemi version: $wemi_version"

# Build the archive files in Wemi
log "TODO: Using hardcoded wemi_dist_dir, switch to dynamic version after 0.13 release"
#wemi_dist_dir=$(./wemi --machine-readable-output=shell wemi/distributionArchive) || fail "wemi ($?) (archive)"
./wemi wemi/distributionArchive || fail "Distribution archive build failed"
wemi_dist_dir="${wemi_home}/build/dist"

# Copy IDE plugins
intellij_plugin_path="${wemi_home}/ide-plugins/WemiForIntelliJ/WemiForIntelliJ.zip"
while true; do
	# TODO(jp): Build this from the script
	if [ ! -f "$intellij_plugin_path" ]; then
		log "Please build the IntelliJ plugin NOW, then press any key to continue"
	elif [ -z "$(find "$intellij_plugin_path" -type f -name "$(basename "$intellij_plugin_path")" -mtime -1 -print 2>/dev/null)" ]; then
		log "Please RE-build the IntelliJ plugin NOW, then press any key to continue"
	else
		break
	fi

	read -r
done

log "Found IntelliJ plugin distribution, using it"
cp "$intellij_plugin_path" "${wemi_dist_dir}/$(basename "$intellij_plugin_path")" || fail "cp IntelliJ plugin ($?)"


# Allow only reading - unless it's a snapshot, which can be overwritten
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
	put ${wemi_dist_dir}/* ./${wemi_version}/
	chmod ${chmod_mode} ./${wemi_version}/*
END_SFTP

log "Done publishing Wemi version $wemi_version"