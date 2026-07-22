#!/bin/bash

set -e
trap 'echo "[ERROR] Error in line $LINENO when executing: $BASH_COMMAND"' ERR

VERSION="$(cat version | cut -d'.' -f1).$(cat version | cut -d'.' -f2).$(( $(cat version | cut -d'.' -f3) + 1 ))"
echo "$VERSION" > version
git add version

# use this and the actual changelog from the main directory
cat - changelog > debian/changelog <<EOF
readsb ($VERSION) UNRELEASED; urgency=medium

  * In development

 -- Matthias Wirth <matthias.wirth@gmail.com>  $(date -R)

EOF

git add debian/changelog
git commit -m "incrementing version: $VERSION"

git tag "v$VERSION"
git push
git push --tag


# notes for a release (done manually due to changelog)
#
# put version in version file
# modify changelog in main directory
# copy changelog from main directory to debian
# git tag -a v3.16
# git push
# git push --tags
