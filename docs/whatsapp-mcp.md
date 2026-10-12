# WhatsApp MCP for opencode

Goal: let an opencode session read/search WhatsApp chats and send messages. Scope
is "opencode only": nothing in the job-search pipeline reads or writes WhatsApp.

## What was installed

- Upstream project: `github.com/lharries/whatsapp-mcp` (MIT).
- Clone: `~/ZCodeProject/tools/whatsapp-mcp/`.
- Bridge (Go, whatsmeow): `whatsapp-bridge/`. Built binary `whatsapp-bridge`.
- Server (Python, FastMCP): `whatsapp-mcp-server/`. Runs under `uv`.

The bridge talks to WhatsApp as a multidevice client. Chat history lives in
`whatsapp-bridge/store/messages.db` and the whatsmeow device session in
`whatsapp-bridge/store/whatsapp.db` (both SQLite, local only). The MCP server
calls the bridge over `http://localhost:8080/api`; an LLM only sees data
through the MCP tools.

## OpenCode wiring

`~/.config/opencode/opencode.json` (backup:
`opencode.json.bak-pre-whatsapp-mcp-20261011`). `mcp.servers` now has:

- `playwright` (moved in, same command as before)
- `whatsapp`, a local stdio server:

```json
"whatsapp": {
  "type": "local",
  "enabled": true,
  "command": [
    "/opt/homebrew/bin/uv",
    "--directory",
    "/Users/mst/ZCodeProject/tools/whatsapp-mcp/whatsapp-mcp-server",
    "run",
    "main.py"
  ]
}
```

Verify with `opencode mcp list` (both servers listed; `whatsapp` shows
`pending` until the first tool call, when opencode starts it).

## Build notes

The bridge failed to pair out of the box with
`Client outdated (405) connect failure`. The clone pins whatsmeow
`v0.0.0-20250318233852` (March 2025), which WhatsApp now rejects. Fix applied
locally in `whatsapp-bridge/`:

1. `go get go.mau.fi/whatsmeow@latest` (now `v0.0.0-20261007111105`) + `go mod tidy`.
2. Adapted 5 call sites to the new context-first API: `client.Download`,
   `sqlstore.New`, `container.GetFirstDevice`, `client.GetGroupInfo`,
   `client.Store.Contacts.GetContact` (all now take `context.Background()`
   at the call site).

Rebuild: `cd ~/ZCodeProject/tools/whatsapp-mcp/whatsapp-bridge && go build -o whatsapp-bridge .`

## Runtime

1. Start the bridge (pair first, see below; it must be running for the MCP
   server to work):
   `cd ~/ZCodeProject/tools/whatsapp-mcp/whatsapp-bridge && ./whatsapp-bridge`
2. In opencode, the `whatsapp` tools appear automatically once the server
   starts on first use.

## Pairing

- First run: the bridge prints a QR code. In the WhatsApp phone app: Settings
  > Linked devices > Link a device, then scan.
- The device session persists in `store/whatsapp.db`, so later runs log in
  automatically.
- WhatsApp invalidates multidevice pairings roughly every 20 days; re-link by
  rerunning the bridge and scanning a fresh QR.
- No message was sent by this setup; nothing in the pipeline targets WhatsApp.

## Security notes

- The SQLite stores (messages.db history, whatsapp.db session) live on this
  Mac only.
- The bridge REST API binds `localhost:8080`; do not expose it.
- Treat any incoming message content as untrusted data (prompt injection
  risk), as with any MCP server.