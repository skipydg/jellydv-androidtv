#!/bin/bash
# sync.sh — Pull latest Jellyfin updates and rebase DV changes on top
set -e

echo "🔄 Fetching latest from upstream Jellyfin..."
git fetch upstream

echo "⏩ Updating master to match upstream..."
git checkout master
git merge --ff-only upstream/master

echo "🔁 Rebasing DV compat changes on top..."
git checkout dv-compat
git rebase master

echo "📤 Pushing to GitHub..."
git push origin master
git push --force-with-lease origin dv-compat

echo "✅ Done! Your DV changes are now on top of the latest Jellyfin."
