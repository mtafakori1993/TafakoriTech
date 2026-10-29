# Commander Shell API Documentation

## Overview

The Shell API provides two ways to run shell commands on the server:

1. **Interactive Shells** — Launch a persistent PTY (pseudo-terminal) running any command (usually the server's shell), send it commands/keystrokes live, and watch the output stream in real-time via Server-Sent Events.
2. **One-off Exec** — Run a single shell command and capture its output synchronously, returning stdout, stderr, and exit code.

⚠️ **Security Notice**: A shell runs commands with the server process's full privileges. Use `COMMANDER_API_KEY` and `COMMANDER_ALLOWED_ROOTS` environment variables to restrict who can reach the API and where commands may run.

---

## Authentication

All Shell API endpoints require authentication via the **API token** (generated on server startup and shown as a QR code).

Send the token as either:
- **`X-API-Key` header** (for regular requests):
  ```bash
  curl -H "X-API-Key: your-token" ...
  ```
- **`?token=` query parameter** (for SSE streams, which cannot set headers):
  ```bash
  curl "...?token=your-token"
  ```

---

## Interactive Shells

An interactive shell is a long-lived PTY running a shell (or any command) where you can send input and receive live output.

### Create a Shell

**`POST /v1/shells`**

Launch an interactive shell (or any command) in a PTY at the given directory.

#### Request Body
```json
{
  "path": "/srv/myapp",           // Required: absolute path (must exist)
  "command": ["bash", "-i"],      // Optional: command argv. Empty → default shell
  "rows": 40,                     // Optional: PTY height (default: 40)
  "cols": 120                     // Optional: PTY width (default: 120)
}
```

#### Response
```json
{
  "id": "sh_abc123def456",
  "path": "/srv/myapp",
  "command": ["bash", "-i"],
  "rows": 40,
  "cols": 120,
  "status": "on",
  "created_at": 1623456789.123
}
```

#### Examples

**Bash shell:**
```bash
TOKEN=your-token

SID=$(curl -s -X POST http://127.0.0.1:8787/v1/shells \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path":"/srv/myapp"}' | jq -r .id)

echo "Shell ID: $SID"
```

**Custom command (e.g., Python REPL):**
```bash
curl -s -X POST http://127.0.0.1:8787/v1/shells \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/srv/myapp",
    "command": ["python3", "-i"]
  }' | jq .
```

**Larger PTY (useful for TUIs):**
```bash
curl -s -X POST http://127.0.0.1:8787/v1/shells \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/srv/myapp",
    "rows": 50,
    "cols": 180
  }' | jq .
```

---

### List Shells

**`GET /v1/shells`**

List all active shells.

#### Response
```json
[
  {
    "id": "sh_abc123def456",
    "path": "/srv/myapp",
    "command": ["bash", "-i"],
    "status": "on",
    "rows": 40,
    "cols": 120,
    "created_at": 1623456789.123
  },
  {
    "id": "sh_xyz789uv1011",
    "path": "/home/user",
    "command": ["bash", "-i"],
    "status": "off",
    "rows": 40,
    "cols": 120,
    "created_at": 1623456799.456
  }
]
```

#### Example
```bash
curl -H "X-API-Key: $TOKEN" http://127.0.0.1:8787/v1/shells | jq .
```

---

### Get Shell Detail & Snapshot

**`GET /v1/shells/{shell_id}`**

Fetch the current state of a shell, including a text snapshot of the screen and ANSI-rendered output.

#### Response
```json
{
  "id": "sh_abc123def456",
  "path": "/srv/myapp",
  "command": ["bash", "-i"],
  "status": "on",
  "rows": 40,
  "cols": 120,
  "created_at": 1623456789.123,
  "snapshot": "$ ls -la\ntotal 48\ndrwxr-xr-x  5 user user 4096 Jun 10 10:00 .\n...",
  "text": "$ ls -la\ntotal 48\ndrwxr-xr-x  5 user user 4096 Jun 10 10:00 .\n...",
  "ansi": "[1;32m$[0m ls -la\ntotal 48\n..."
}
```

Fields:
- **`snapshot`**: Plain text rendering of the terminal screen
- **`text`**: Same as snapshot (human-readable terminal output)
- **`ansi`**: ANSI-escaped rendering (colors, styles); use with xterm.js or similar for faithful display

#### Example
```bash
curl -H "X-API-Key: $TOKEN" http://127.0.0.1:8787/v1/shells/sh_abc123def456 | jq .
```

---

### Send Input (Commands & Keystrokes)

**`POST /v1/shells/{shell_id}/input`**

Send text and optionally submit it (append Enter). Use this to send commands, keystrokes, or special keys.

#### Request Body
```json
{
  "data": "ls -la",              // Text to send
  "submit": true                 // Optional: append Enter? (default: true)
}
```

#### Response
```json
{
  "ok": true,
  "status": "on"
}
```

#### Examples

**Send a command and submit (Enter):**
```bash
curl -X POST http://127.0.0.1:8787/v1/shells/sh_abc123def456/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data": "echo hello world", "submit": true}'
```

**Send text without submitting (useful for multi-line input or waiting for a prompt):**
```bash
curl -X POST http://127.0.0.1:8787/v1/shells/sh_abc123def456/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data": "some text", "submit": false}'
```

**Send an Escape key (interrupt running command):**
```bash
curl -X POST http://127.0.0.1:8787/v1/shells/sh_abc123def456/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data": "", "submit": false}'  # Escape = ESC
```

**Send Ctrl+C (SIGINT):**
```bash
# In bash:
curl -X POST http://127.0.0.1:8787/v1/shells/sh_abc123def456/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data": "", "submit": false}'  # ^C
```

---

### Stream Live Output

**`GET /v1/shells/{shell_id}/stream`** (Server-Sent Events)

Subscribe to live terminal output as the shell runs. This is a persistent SSE stream that yields events until the shell ends or disconnects.

#### Response Format

Each event is a JSON object:
```json
{
  "chunk": "$ echo hello\n",       // Raw terminal output since last event
  "screen": "$ echo hello\nhello\n$ ",  // Full current screen text
  "ansi": "[1;32m$[0m echo hello\nhello\n..."  // ANSI-escaped screen
}
```

The stream ends with:
```json
{"event": "end", "data": "{}"}
```

#### Example (using curl with SSE support)

```bash
# Watch the live shell output
curl -N "http://127.0.0.1:8787/v1/shells/sh_abc123def456/stream?token=$TOKEN"
```

Or with jq to pretty-print:
```bash
curl -N "http://127.0.0.1:8787/v1/shells/sh_abc123def456/stream?token=$TOKEN" | jq .
```

#### Example (JavaScript/Node.js)

```javascript
const token = 'your-token';
const shellId = 'sh_abc123def456';

const eventSource = new EventSource(
  `http://127.0.0.1:8787/v1/shells/${shellId}/stream?token=${token}`
);

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Output:', data.chunk);
  console.log('Full screen:', data.screen);
};

eventSource.addEventListener('end', () => {
  console.log('Shell stream ended');
  eventSource.close();
});

eventSource.onerror = (error) => {
  console.error('Stream error:', error);
  eventSource.close();
};
```

#### Example (Python)

```python
import requests
import json

token = 'your-token'
shell_id = 'sh_abc123def456'
url = f'http://127.0.0.1:8787/v1/shells/{shell_id}/stream?token={token}'

with requests.get(url, stream=True) as response:
    for line in response.iter_lines():
        if line:
            data_str = line.decode('utf-8').replace('data: ', '')
            try:
                data = json.loads(data_str)
                print(f"Output: {data.get('chunk', '')}")
            except json.JSONDecodeError:
                pass
```

---

### Control Shell State

#### Turn ON (Start/Restart)

**`POST /v1/shells/{shell_id}/on`**

Start or restart a shell that is currently off.

#### Response
```json
{
  "id": "sh_abc123def456",
  "status": "on",
  ...
}
```

#### Example
```bash
curl -X POST http://127.0.0.1:8787/v1/shells/sh_abc123def456/on \
  -H "X-API-Key: $TOKEN"
```

#### Turn OFF (Stop)

**`POST /v1/shells/{shell_id}/off`**

Stop a running shell (does not delete it; use DELETE to remove).

#### Response
```json
{
  "turned_off": true,
  "id": "sh_abc123def456",
  "status": "off",
  ...
}
```

#### Example
```bash
curl -X POST http://127.0.0.1:8787/v1/shells/sh_abc123def456/off \
  -H "X-API-Key: $TOKEN"
```

---

### Delete Shell

**`DELETE /v1/shells/{shell_id}`**

Shut down and permanently remove a shell.

#### Response
```json
{
  "deleted": true
}
```

#### Example
```bash
curl -X DELETE http://127.0.0.1:8787/v1/shells/sh_abc123def456 \
  -H "X-API-Key: $TOKEN"
```

---

## One-off Exec

Run a single shell command, wait for completion, and capture output.

### Execute a Command

**`POST /v1/exec`**

Run a shell command on the server and return its stdout, stderr, and exit code. This is synchronous — the API waits for the command to finish (or time out) before responding.

#### Request Body
```json
{
  "command": "ls -la && git status",  // Required: shell command line
  "path": "/srv/myapp",               // Required: absolute working directory
  "timeout": 60                       // Optional: seconds before kill (default: 60, 0 = no timeout)
}
```

#### Response
```json
{
  "command": "ls -la && git status",
  "path": "/srv/myapp",
  "exit_code": 0,
  "stdout": "total 48\ndrwxr-xr-x  5 user user 4096 Jun 10 10:00 .\n... On branch main\nYour branch is up to date...",
  "stderr": "",
  "timed_out": false,
  "duration": 0.142
}
```

#### Examples

**Simple command:**
```bash
curl -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "command": "echo hello world",
    "path": "/srv/myapp"
  }'
```

**Command with pipes:**
```bash
curl -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "command": "git log --oneline | head -5",
    "path": "/srv/myapp"
  }' | jq .stdout
```

**Long-running command with timeout:**
```bash
curl -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "command": "npm run build",
    "path": "/srv/myapp",
    "timeout": 300
  }' | jq '{"exit": .exit_code, "out": .stdout}'
```

**Check if timed out:**
```bash
RESPONSE=$(curl -s -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "command": "sleep 10",
    "path": "/srv/myapp",
    "timeout": 2
  }')

echo "$RESPONSE" | jq '.timed_out'  # true
```

---

## Use Cases

### 1. Deploy & Monitor

Run a deployment script and monitor its output in real-time:

```bash
TOKEN=your-token

# Create shell
SID=$(curl -s -X POST http://127.0.0.1:8787/v1/shells \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path":"/opt/app"}' | jq -r .id)

# Start deployment in background
curl -X POST http://127.0.0.1:8787/v1/shells/$SID/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data":"./deploy.sh", "submit":true}'

# Stream output (in another terminal or thread)
curl -N "http://127.0.0.1:8787/v1/shells/$SID/stream?token=$TOKEN" | jq .chunk
```

### 2. Remote Git Workflow

Run git commands from a client:

```bash
# Check status
curl -s -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"command":"git status","path":"/srv/repo"}' | jq .stdout

# Fetch and rebase
curl -s -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"command":"git fetch origin && git rebase origin/main","path":"/srv/repo"}' | jq .
```

### 3. Interactive Build

Start an interactive shell for a long build:

```bash
SID=$(curl -s -X POST http://127.0.0.1:8787/v1/shells \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path":"/srv/app"}' | jq -r .id)

# Send build command
curl -X POST http://127.0.0.1:8787/v1/shells/$SID/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data":"make clean && make -j4", "submit":true}'

# Watch in real-time
curl -N "http://127.0.0.1:8787/v1/shells/$SID/stream?token=$TOKEN" | jq .chunk
```

### 4. Run Tests & Capture Results

```bash
curl -s -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "command":"npm test 2>&1 | tee test-results.log",
    "path":"/srv/myapp",
    "timeout":300
  }' | jq '{
    "passed": (.exit_code == 0),
    "output": .stdout[-2000:],
    "duration": .duration
  }'
```

### 5. Quick Health Check

Use exec for lightweight checks:

```bash
curl -s -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "command":"curl -s http://127.0.0.1:3000/health | jq .",
    "path":"/srv/app",
    "timeout":5
  }' | jq '.stdout | fromjson'
```

---

## Error Handling

### Common HTTP Status Codes

| Status | Meaning | Example |
|--------|---------|---------|
| `200` | Success | Command completed or shell created |
| `400` | Bad request | Invalid path or malformed JSON |
| `401` | Unauthorized | Missing/invalid API token |
| `404` | Not found | Shell ID doesn't exist |
| `409` | Conflict | Cannot send input to a shell that's off |
| `500` | Server error | Unexpected error (check server logs) |

### Example Error Response

```bash
curl -X POST http://127.0.0.1:8787/v1/shells/invalid_id/input \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data":"echo test", "submit":true}'

# Returns:
# {"detail": "shell not found"}
# (HTTP 404)
```

### Handling Timeouts in Exec

```bash
RESPONSE=$(curl -s -X POST http://127.0.0.1:8787/v1/exec \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"command":"sleep 10","path":"/srv/app","timeout":2}')

if echo "$RESPONSE" | jq -e '.timed_out' > /dev/null; then
  echo "Command timed out"
  exit 1
fi
```

---

## Best Practices

1. **Always validate paths**: Ensure `path` is an absolute directory that exists on the server.
2. **Use timeouts**: Set reasonable `timeout` values in exec to prevent hanging.
3. **Stream output carefully**: SSE streams are persistent; close them when done to free resources.
4. **Clean up shells**: Delete shells with `DELETE` when no longer needed; don't just leave them running.
5. **Handle shell state**: Check `status` before sending input; if `"off"`, turn it `on` first.
6. **Use interactive shells for long operations**: For long-running or multi-step workflows, use interactive shells and stream output.
7. **Use exec for quick commands**: For one-off commands that don't need user interaction, use exec.
8. **Monitor permissions**: Shells run with full server privileges. Restrict access via `COMMANDER_API_KEY` and `COMMANDER_ALLOWED_ROOTS`.

---

## Security Considerations

- **Token protection**: Treat the API token like a password; anyone with it can execute arbitrary commands on the server.
- **Path restrictions**: Use `COMMANDER_ALLOWED_ROOTS` to limit which directories shells can run in.
- **Privilege**: Shells run with the server process's privileges—usually the user running Commander.
- **Input validation**: Always validate user-supplied paths and commands before sending to the API.
- **Network security**: If exposing Commander beyond 127.0.0.1, use TLS (HTTPS) and strong authentication.
- **Command logging**: Consider logging all exec commands for audit purposes.

---

## Related APIs

- **[Tasks](README.md#tasks--one-off-runs)**: One-off Claude AI runs (different from shell exec).
- **[Terminals](README.md#terminals--persistent-interactive-claude)**: Interactive Claude sessions (not shell commands).
- **[Sessions](README.md#sessions--stateful-like-the-repl)**: Stateful Claude conversations.
- **[Auth](README.md#auth--is-claude-logged-in)**: Check Claude login status.

---

## More Information

- **OpenAPI / Swagger**: Visit `/docs` on the running server for interactive API documentation.
- **OpenAPI schema**: Fetch `/openapi.json` for machine-readable spec.
- **Main README**: See [README.md](README.md) for server setup and overview.
