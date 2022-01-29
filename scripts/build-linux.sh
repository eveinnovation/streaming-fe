#!/usr/bin/env bash
echo 'Build streaming front-end...'
npm install
echo 'Starting Vue server...'
kill -9 `lsof -t -i:8080`
npm run serve
