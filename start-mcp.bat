@echo off
setlocal

set POCKETHIVE_BASE_URL=http://localhost:8088
set POCKETHIVE_AUTH_USERNAME=local-admin
set POCKETHIVE_ROOT=c:\Private\projects\PocketHiveClean
set BUNDLES_ROOT=C:\Users\tday\IdeaProjects\qa-nft-pockethive-bundles2\bundles
set PH_BUNDLES_ROOTS=["C:\\Users\\tday\\IdeaProjects\\qa-nft-pockethive-bundles2\\bundles"]
set PH_MCP_HTTP_PORT=3100

echo Starting PocketHive MCP server on http://localhost:3100/mcp ...
cd /d "%~dp0tools\pockethive-mcp"
node server.mjs
