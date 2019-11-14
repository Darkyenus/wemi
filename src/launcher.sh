#!/bin/sh
# Target platform: Reasonably POSIX compliant systems
# https://pubs.opengroup.org/onlinepubs/009695399/

# Wemi Launcher - This file is distributed under MIT License

# Copyright 2019 Jan Polák
#
# Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
# documentation files (the "Software"), to deal in the Software without restriction, including without limitation
# the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
# and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included
# in all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
# TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
# THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
# OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
# DEALINGS IN THE SOFTWARE.

log() { echo "$@" 1>&2; }
fail() { log "$@"; exit 1; }
fail_unsatisfied() { log "Unsatisfied software dependency - install this software and try again: $*"; exit 2; }

###################################
# Wemi settings
###################################
# Wemi version to use
readonly WEMI_VERSION=0.10-SNAPSHOT

###################################
# Java settings
# see https://api.adoptopenjdk.net/
###################################
readonly JDK_JAVA_MIN_VERSION=8
{ # Detect Java version (from --java=<version> argument, WEMI_JAVA env-var, or default)
	if echo "$1" | grep -q -x -e "--java=[0-9][0-9]*" ; then
		jdk_version_number="${1#--java=}"
		shift
		readonly JDK_JAVA_VERSION_FORCED=true
	elif [ -n "$WEMI_JAVA" ]; then
		jdk_version_number="${WEMI_JAVA}"
		readonly JDK_JAVA_VERSION_FORCED=true
	else
		jdk_version_number="$JDK_JAVA_MIN_VERSION"
		readonly JDK_JAVA_VERSION_FORCED=false
	fi

	if [ -z "$jdk_version_number" ] || [ "$jdk_version_number" -lt "$JDK_JAVA_MIN_VERSION" ]; then
		log "Requested version number '$jdk_version_number' is too low, defaulting to $JDK_JAVA_MIN_VERSION"
		jdk_version_number="$JDK_JAVA_MIN_VERSION"
	fi

	readonly JDK_JAVA_VERSION="${jdk_version_number}"
}
readonly JDK_VERSION="openjdk${JDK_JAVA_VERSION}" # openjdk8, openjdk9, openjdk10, openjdk11, openjdk12, openjdk13
readonly JDK_IMPLEMENTATION=hotspot # hotspot, openj9
readonly JDK_HEAP_SIZE=normal # normal, large
readonly JDK_RELEASE_TYPE=releases # releases, nightly
readonly JDK_RELEASE=latest
###################################
{ # Detect OS
	os_name=$(uname)
	os_name=${os_name:-${OSTYPE}}
	os_name=$(echo "$os_name" | tr '[:upper:]' '[:lower:]') # to lowercase

	case "$os_name" in
		*linux*) readonly JDK_OS='linux' ;;
		*darwin*) readonly JDK_OS='mac' ;;
		*windows* | *cygwin* | *mingw* | *msys*) readonly JDK_OS='windows' ;;
		*sunos*) readonly JDK_OS='solaris' ;;
		aix) readonly JDK_OS='aix' ;;
		*) fail "Unrecognized or unsupported operating system: $os_name" ;;
	esac
}
###################################
{ # Detect architecture
	arch_name=$(uname -m | tr '[:upper:]' '[:lower:]') # to lowercase

	case "$arch_name" in
		*x86_64* | *amd64* | *i[3456]86-64*) readonly JDK_ARCH='x64' ;;
		*x86* | *i[3456]86* | *i86pc*) readonly JDK_ARCH='x32' ;;
		*ppc64le*) readonly JDK_ARCH='ppc64le' ;; # I guess?
		*ppc64*) readonly JDK_ARCH='ppc64' ;;
		*power* | *ppc*) fail "32-bit PowerPC architecture is not supported" ;;
		*s390*) readonly JDK_ARCH='s390x' ;;
		*aarch64*) readonly JDK_ARCH='aarch64' ;; # 64-bit ARM
		*arm*) readonly JDK_ARCH='arm32' ;;
		*) fail "Unrecognized or unsupported CPU architecture: $arch_name" ;;
	esac
}
###################################

readonly WEMI_CACHE_DIR=${WEMI_HOME:-"${HOME}/.wemi"}
if [ ! -e "$WEMI_CACHE_DIR" ]; then
	log "Creating '$WEMI_CACHE_DIR' as a cache for Wemi related files"
	mkdir -p "$WEMI_CACHE_DIR" || fail "Wemi cache directory creation failed (mkdir returned $?)"
	cat <<EOF >"$WEMI_CACHE_DIR/readme.txt"
This directory is used by Wemi to store downloaded Wemi versions and JDKs.
Feel free do delete this directory to completely remove Wemi from your system.

To change the location of this directory, specify the new path in WEMI_HOME environment variable.
Then you can delete this directory or move it to the new location.

jdk/ contains downloaded OpenJDK Java distributions.
wemi/ contains downloaded Wemi distributions.
You can delete anything from these folders to reclaim disk space, as long as it is currently not used.
EOF
fi

# Contains all versions of this type of JDK
readonly WEMI_JDK_TYPE_FOLDER="${WEMI_CACHE_DIR}/jdk/${JDK_VERSION}-${JDK_IMPLEMENTATION}-${JDK_HEAP_SIZE}"
# Contains the Wemi classpath jars
readonly WEMI_LAUNCHER_FOLDER="${WEMI_CACHE_DIR}/wemi/${WEMI_VERSION}"

# $1: Command name
# $?: Whether or not the command exists
command_exists() {
	# Note: This is the most POSIX way to do it, but it is optional (the -v switch).
	command -v "$1" > /dev/null 2>&1
}

# Downloads the resource at given URL to given file. May support continuations.
# $1: URL
# $2: file
download_to_file() {
	mkdir -p "$(dirname "$2")"
	if command_exists curl; then
		curl -C - --fail --retry 3 --location --output "$2" --url "$1" || fail "Failed to fetch $1 to $2 (curl returned $?)"
	elif command_exists wget; then
		wget --tries=3 --continue --compression=auto --show-progress --output-document="$2" "$1" || fail "Failed to fetch $1 to $2 (wget returned $?)"
	else
		fail_unsatisfied "curl or wget"
	fi
}

# Downloads the resource at given URL to stdout.
# $1: URL
download_to_output() {
	if command_exists curl; then
		curl --fail --retry 3 --location --silent --show-error --url "$1" || fail "Failed to fetch $1 (curl returned $?)"
	elif command_exists wget; then
		wget --tries=3 --quiet --compression=auto --output-document=- "$1" || fail "Failed to fetch $1 (wget returned $?)"
	else
		fail_unsatisfied "curl or wget"
	fi
}

# URL encode parameter $1
# NOTE: Usually does not handle Unicode characters correctly
url_encode() {
	input="$1"; output=""
	while [ -n "$input" ]; do
		# Take first character from input
		tail="${input#?}"; head="${input%"$tail"}"; input="$tail"
		case "$head" in
			[-_.~a-zA-Z0-9]) output="$output$head";;
			*) output="${output}%$(printf '%02x' "'$head")";;
		esac
	done
	echo "$output"
}

# @1 .zip archive
# @2 directory with entries extracted
extract_zip() {
	if command_exists unzip; then
		unzip "$1" -d "$2" 1>/dev/null || fail "Failed to unzip '$1' (unzip returned $?)"
	else
		fail_unsatisfied "unzip"
	fi
}

# @1 .tar.gz archive
# @2 directory with entries extracted
extract_targz() {
	# If we have gzip, use it, if we don't then hope that tar/pax supports the -z switch
	# (gzip, tar and pax's -z switch are not POSIX compliant, so this tries things that are most likely to work first)
	if command_exists gzip; then
		if command_exists tar; then
			# shellcheck disable=SC2015
			mkdir "$2" 1>&2 &&
			gzip -d -c < "$1" | tar -x -C "$2" 1>/dev/null || fail "Failed to extract '$1' (gzip|tar returned $?)"
		elif command_exists pax; then
			# pax has two pecularities:
			# 1. It always extracts (relative) to the current working directory
			# 2. It permits absolute paths in archives and WILL extract to an absolute path
			# Both can be fixed by prefixing the paths by the target directory manually.
			gzip -d -c < "$1" | pax -r -s "#^#${2}/#" 1>/dev/null || fail "Failed to extract '$1' (gzip|pax returned $?)"
		else
			fail_unsatisfied "tar or pax"
		fi
	else
		if command_exists tar; then
			# shellcheck disable=SC2015
			mkdir "$2" &&
			tar -x -z -C "$2" -f "$1" 1>/dev/null || fail "Failed to extract '$1' (tar returned $?)"
		elif command_exists pax; then
			pax -r -z -s "#^#${2}/#" -f "$1" 1>/dev/null || fail "Failed to extract '$1' (pax returned $?)"
		else
			fail_unsatisfied "tar or pax"
		fi
	fi
}

# Download, extract and test a JDK distribution according to JDK_* constants and the JDK release argument.
# Returns immediately if already downloaded.
# $1: JDK release (not 'latest')
# echo: Path to the JDK home
fetch_jdk() {
	jdk_version_folder="${WEMI_JDK_TYPE_FOLDER}/$1"
	if [ -d "$jdk_version_folder" ]; then
		# This version already exists
		echo "$jdk_version_folder"
		return 0
	fi

	# Download the archive to a temporary directory
	download_file="${jdk_version_folder}.download"

	# Using the adoptopenjdk builds
	jdk_url="https://api.adoptopenjdk.net/v2/binary/${JDK_RELEASE_TYPE}/${JDK_VERSION}?openjdk_impl=${JDK_IMPLEMENTATION}&os=${JDK_OS}&arch=${JDK_ARCH}&release=$(url_encode "$1")&type=jdk&heap_size=${JDK_HEAP_SIZE}"
	log "Downloading ${JDK_VERSION}(${JDK_IMPLEMENTATION}) for ${JDK_OS} (${JDK_ARCH}): ${1}"
	download_to_file "$jdk_url" "$download_file" 1>&2
	# Now we need to decompress it
	# Windows binaries are packaged as zip, others as tar.gz.
	# This is a bit iffy, but getting the filename from curl/wget reliably is not easy.

	decompressed_folder="${jdk_version_folder}.decompressed"

	if [ ${JDK_OS} = 'windows' ]; then
		extract_zip "$download_file" "$decompressed_folder"
	else
		extract_targz "$download_file" "$decompressed_folder"
	fi

	# Unpack the folder (it is bundled in a different directory structure according to the platform)
	# 'release' is a file inside the directory which we want
	if downloaded_jdk_release="$(echo "${decompressed_folder}/"*"/release")" && [ -f "$downloaded_jdk_release" ]; then
		downloaded_jdk_home="${downloaded_jdk_release%/release}"
	elif [ "$JDK_OS" = 'mac' ] && downloaded_jdk_release="$(echo "${decompressed_folder}/"*"/Contents/Home/release")" && [ -f "$downloaded_jdk_release" ]; then
		downloaded_jdk_home="${downloaded_jdk_release%/release}"
	else
		fail "Failed to recognize downloaded JDK directory structure at '${decompressed_folder}'"
	fi

	# Test the downloaded JDK
	downloaded_jdk_java_exe="${downloaded_jdk_home}/bin/java"
	if [ ! -f "$downloaded_jdk_java_exe" ]; then
		fail "Downloaded JDK does not contain 'java' executable at the expected location (${downloaded_jdk_java_exe})"
	fi

	"$downloaded_jdk_java_exe" -version 2>/dev/null 1>&2 || fail "Downloaded JDK 'java' executable does not work"

	# Move home into the valid directory
	mv "$downloaded_jdk_home" "$jdk_version_folder" 1>&2 || fail "Failed to move downloaded JDK into a correct destination (mv returned $?)"

	# Delete original archive
	rm "$download_file" 1>&2 || log "warning: Failed to delete downloaded file ${download_file}"

	# Delete downloaded folder
	rm -rf "${decompressed_folder?:SAFEGUARD_AGAINST_EMPTY_PATH}" 1>&2 || log "Failed to delete leftover downloaded files (rm returned $?)"

	log "Downloaded a new JDK at: ${jdk_version_folder}"

	# Done
	echo "$jdk_version_folder"
}

fetch_latest_jdk_release_name() {
	jdk_latest_info_url="https://api.adoptopenjdk.net/v2/info/${JDK_RELEASE_TYPE}/${JDK_VERSION}?openjdk_impl=${JDK_IMPLEMENTATION}&os=${JDK_OS}&arch=${JDK_ARCH}&release=latest&type=jdk&heap_size=${JDK_HEAP_SIZE}"
	# Get the json, remove all linebreaks and spaces (for easier processing)
	latest_info_json="$(download_to_output "$jdk_latest_info_url" | tr -d '\n ')"
	# Delete everything except for the release name. Since the format is very simple, this should work, even if it is an abomination
	latest_release_name="$(echo "$latest_info_json" | sed 's/.*"release_name":"\([^"][^"]*\)".*/\1/')"
	echo "$latest_release_name"
}

fetch_latest_jdk() {
	# Check whether 'latest' symlink exists and is fresh enough. If it does and is, download it.
	# If it isn't, download and use the newest release.
	latest_version_folder="${WEMI_JDK_TYPE_FOLDER}/latest"

	if [ -e "$latest_version_folder" ] && [ ! -L "$latest_version_folder" ]; then
		fail "'${latest_version_folder}' should be a symlink, but isn't"
	fi

	# If the 'latest' exists and is less than 60 days old, no need to refresh it
	if [ -z "$(find "$latest_version_folder" -mtime -60 -print 2>/dev/null)" ]; then
		log "Checking for JDK update"
		# Get new latest version
		if latest_jdk_release_name="$(fetch_latest_jdk_release_name)"; then
			if latest_jdk_release_folder="$(fetch_jdk "$latest_jdk_release_name")"; then
				# Everything was successful, remove old symlink a create a new one
				if [ -L "$latest_version_folder" ]; then
					unlink "$latest_version_folder" || log "Failed to unlink previous 'latest' symlink (unlink returned $?)"
				fi
				ln -s "$latest_jdk_release_folder" "$latest_version_folder" || log "Failed to link ${latest_version_folder} to ${latest_jdk_release_folder}"
			else
				log "Failed to fetch latest JDK release (${latest_jdk_release_name})"
			fi
		else
			log "Failed to fetch latest JDK release name"
		fi
	fi

	echo "$latest_version_folder"
}

can_use_java_from_path() {
	if ! command_exists java; then
		return 1
	fi

	if [ "$JDK_JAVA_VERSION_FORCED" = 'true' ] && ! command_exists javac; then
		log "Not using java on PATH, bacause javac is not on PATH and explicit version has been specified"
		return 1
	fi

	if ! java_version="$(java -version 2>&1 | head -n 1)"; then
		log "Failed to get verion of java on PATH, using downloaded openjdk"
		return 1
	fi

	if echo "$java_version" | grep -q -x -e '.* version ".*"'; then
		# Matching standard versioning (java version "1.8.0", openjdk version "11.0.3", openjdk version "9", etc.)
		# https://www.oracle.com/technetwork/java/javase/versioning-naming-139433.html
		java_version="$(echo "$java_version" | sed 's/.* version "\(.*\)".*/\1/')"

		java_version_major="$(echo "$java_version" | sed 's/\([0-9]*\).*/\1/')"
		# Until java 9, the java version was in minor, from 9 onwards it was in major
		if [ "$java_version_major" -gt 1 ]; then
			java_version_number="$java_version_major"
		else
			java_version_number="$(echo "$java_version" | sed 's/[0-9]*\.\([0-9]*\).*/\1/')"
		fi

		# If the user specified a speficic version, use it no matter what.
		if [ "$JDK_JAVA_VERSION_FORCED" = 'true' ]; then
			if [ "$java_version_number" -eq "$JDK_JAVA_VERSION" ]; then
				return 0
			else
				return 1
			fi
		else # Otherwise: we can use it if the version is high enough
			if [ "$java_version_number" -ge "$JDK_JAVA_MIN_VERSION" ]; then
				return 0
			else
				log "java on PATH is too old ($java_version, at least $JDK_JAVA_MIN_VERSION required)"
				return 1
			fi
		fi
	else
		log "java on PATH has unrecognized version ($java_version)"
		if [ "$JDK_JAVA_VERSION_FORCED" = 'true' ]; then
			log "Using Wemi OpenJDK"
			return 1
		else
			log "Using java on PATH - to force Wemi OpenJDK, put '--java=$JDK_JAVA_MIN_VERSION' as the first argument or set 'export WEMI_JAVA=$JDK_JAVA_MIN_VERSION' (or higher version)"
			return 0
		fi
	fi
}

# Get command to execute 'java'
get_runtime_java_exe() {
	if can_use_java_from_path; then
		echo 'java'
		return 0
	fi

	if [ "$JDK_RELEASE" = 'latest' ]; then
		jdk_home="$(fetch_latest_jdk)"
	else
		jdk_home="$(fetch_jdk "$JDK_RELEASE")"
	fi

	if [ ! -d "${jdk_home?:EMPTY_JDK_HOME}" ]; then
		fail "Failed to obtain JDK (release: ${JDK_RELEASE})"
	fi

	echo "${jdk_home}/bin/java"
}

# Launch as a Wemi launcher
launch_wemi() {
	# Retrieve it here, so that if it fails, it is now
	readonly JAVA_EXE="$(get_runtime_java_exe)"

	# Fetch Wemi if this version does not exist
	if [ ! -e "$WEMI_LAUNCHER_FOLDER" ]; then
		wemi_launcher_download_file="${WEMI_LAUNCHER_FOLDER}.downloading"
		log "Downloading Wemi launcher (version ${WEMI_VERSION})"
		download_to_file "https://github.com/Darkyenus/wemi/releases/download/v${WEMI_VERSION}/wemi.tar.gz" "$wemi_launcher_download_file"
		extract_targz "$wemi_launcher_download_file" "${WEMI_LAUNCHER_FOLDER}/"
		rm "$wemi_launcher_download_file" || log "Failed to remove downloaded Wemi archive (mv returned $?)"
	fi

	# Parse --debug, --debug-suspend, --debug=<port> and --debug-suspend=<port> flags, which are mutually exclusive and
	# must be the first flag to appear.
	if echo "$1" | grep -E -q -x -e '--debug(-suspend|=[0-9]+|-suspend=[0-9]+)?'; then
		echo "$1" | grep -q -e 'suspend' && java_debug_suspend='y'
		java_debug_port="$(echo "$1" | sed 's/[^0-9]*\([0-9]*\)[^0-9]*/\1/')"
		WEMI_JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=${java_debug_suspend:-n},address=${java_debug_port:-5005}"
		shift
	fi

	readonly WEMI_RELOAD_CODE=6
	while true; do
		# shellcheck disable=SC2086
		"$JAVA_EXE" $WEMI_JAVA_OPTS -classpath "${WEMI_LAUNCHER_FOLDER}/*" 'wemi.boot.Main' --root="$(dirname "$0")" --reload-supported "$@"
		wemi_exit_code="$?"
		if [ "$?" -ne "$WEMI_RELOAD_CODE" ]; then
			exit "$wemi_exit_code"
		fi
	done
}

launch_wemi "$@"