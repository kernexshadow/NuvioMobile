#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.6
engine_checksum=f556718ec0fd122a61bc5408307b19fcd54397595954c46c6ff8db1e5a179421
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"

if [[ ! -f "${repository_root}/MPVKit/Package.swift" ]]; then
    git -C "${repository_root}" submodule update --init --depth 1 MPVKit
fi

# The Usenet engine as a static library (iOS apps cannot start its Android sidecar process).
usenet_engine_tag=ios-library-37208cb
usenet_engine_checksum=bc7cde18c2821840c1e0ab41bc2c5466856f087756da60708199edb9813ceb58
usenet_engine_repository="${NUVIO_USENET_ENGINE_REPOSITORY:-kernexshadow/nuvio-usenet-engine-nuviomedia}"
usenet_framework="${repository_root}/../nuvio-usenet-engine/build/ios/NuvioUsenet.xcframework"

prepare_usenet_engine() {
    if [[ -f "${usenet_framework}/Info.plist" ]]; then
        return 0
    fi
    local temporary
    temporary="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-usenet-engine.XXXXXX")"
    local archive="${temporary}/NuvioUsenet.xcframework.zip"
    curl --fail --location --retry 5 --retry-all-errors --silent --show-error         --output "${archive}"         "https://github.com/${usenet_engine_repository}/releases/download/${usenet_engine_tag}/NuvioUsenet.xcframework.zip"
    if [[ "$(shasum -a 256 "${archive}" | awk '{print $1}')" != "${usenet_engine_checksum}" ]]; then
        echo "Usenet engine Apple package checksum mismatch." >&2
        rm -rf "${temporary}"
        return 1
    fi
    unzip -q "${archive}" -d "${temporary}/extracted"
    if [[ ! -f "${temporary}/extracted/NuvioUsenet.xcframework/ios-arm64/libnuvio_usenet.a" ]]; then
        echo "Usenet engine Apple package does not contain the iOS arm64 library." >&2
        rm -rf "${temporary}"
        return 1
    fi
    mkdir -p "$(dirname "${usenet_framework}")"
    ditto "${temporary}/extracted/NuvioUsenet.xcframework" "${usenet_framework}"
    rm -rf "${temporary}"
}

prepare_usenet_engine

if [[ -f "${engine_framework}/Info.plist" ]]; then
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-dependencies.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT

archive="${temporary_directory}/nuvio-engine-apple-${engine_version}.zip"
curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
    --output "${archive}" \
    "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"

actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
    echo "Nuvio Engine Apple package checksum mismatch." >&2
    exit 1
fi

extraction_root="${temporary_directory}/extracted"
mkdir -p "${extraction_root}"
unzip -q "${archive}" -d "${extraction_root}"
source_framework="$(find "${extraction_root}" -type d -name NuvioEngine.xcframework -print -quit)"
if [[ -z "${source_framework}" || ! -f "${source_framework}/Info.plist" ]]; then
    echo "Nuvio Engine Apple package does not contain NuvioEngine.xcframework." >&2
    exit 1
fi
if [[ ! -f "${source_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
    echo "Nuvio Engine Apple package does not contain the iOS arm64 library." >&2
    exit 1
fi

mkdir -p "$(dirname "${engine_framework}")"
ditto "${source_framework}" "${engine_framework}"
