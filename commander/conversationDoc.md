# Commander Conversations API Documentation

## Overview

The **Conversations API** is the recommended way to interact with Claude on the Commander server. It provides a unified, real-chat experience where:

- Every message is part of a conversation thread
- Context is automatically maintained across all previous messages
- Responses are naturally aware of the full conversation history
- No manual session management or context building required

Think of it like ChatGPT — create a conversation, send messages, and Claude understands the full context automatically.

---

## Quick Start

```bash
TOKEN=your-api-token

# 1. Create a conversation
CONV=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path":"/srv/myapp"}' | jq -r .id)

echo "Conversation: $CONV"

# 2. Send a message
MSG=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Create a hello world function"}' | jq -r .id)

echo "Message task: $MSG"

# 3. Stream the response
curl -N "http://127.0.0.1:8787/v1/conversations/$CONV/messages/$MSG/stream?token=$TOKEN" | jq .

# 4. Send another message (with automatic context!)
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Now add error handling"}'
```

---

## Authentication

All Conversations API endpoints require the API token. Send it as:

- **Header**: `X-API-Key: your-token`
- **Query param** (for SSE streams): `?token=your-token`

---

## API Endpoints

### Create a Conversation

**`POST /v1/conversations`**

Start a new conversation.

#### Request Body
```json
{
  "path": "/srv/myapp",               // Required: absolute working directory
  "model": "opus",                    // Optional: e.g., "opus", "sonnet", "haiku"
  "permission_mode": "acceptEdits"    // Optional: "default", "acceptEdits", "bypassPermissions", "plan"
}
```

#### Response
```json
{
  "id": "conv_a1b2c3d4e5",
  "path": "/srv/myapp",
  "model": "opus",
  "permission_mode": "acceptEdits",
  "created_at": 1718000000.123,
  "message_count": 0
}
```

#### Example
```bash
curl -X POST http://127.0.0.1:8787/v1/conversations \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/srv/myapp",
    "model": "opus",
    "permission_mode": "acceptEdits"
  }' | jq .
```

---

### List Conversations

**`GET /v1/conversations`**

List all active conversations (without full message history).

#### Response
```json
[
  {
    "id": "conv_a1b2c3d4e5",
    "path": "/srv/myapp",
    "model": "opus",
    "permission_mode": "acceptEdits",
    "created_at": 1718000000.123,
    "message_count": 5
  }
]
```

#### Example
```bash
curl -H "X-API-Key: $TOKEN" http://127.0.0.1:8787/v1/conversations | jq .
```

---

### Get Conversation with Full History

**`GET /v1/conversations/{conversation_id}`**

Retrieve a conversation including its complete message history.

#### Response
```json
{
  "id": "conv_a1b2c3d4e5",
  "path": "/srv/myapp",
  "model": "opus",
  "permission_mode": "acceptEdits",
  "created_at": 1718000000.123,
  "messages": [
    {
      "id": "msg_user_001",
      "role": "user",
      "content": "Create a hello world function",
      "created_at": 1718000010.111,
      "task_id": "abc123def456"
    },
    {
      "id": "msg_asst_001",
      "role": "assistant",
      "content": "Here's a simple hello world function:\n\n```python\ndef hello_world():\n    print('Hello, World!')\n```\n\nYou can call it with `hello_world()`.",
      "created_at": 1718000020.222,
      "task_id": "abc123def456"
    },
    {
      "id": "msg_user_002",
      "role": "user",
      "content": "Now add error handling",
      "created_at": 1718000030.333,
      "task_id": "def789ghi123"
    },
    {
      "id": "msg_asst_002",
      "role": "assistant",
      "content": "Here's the function with error handling:\n\n```python\ndef hello_world():\n    try:\n        print('Hello, World!')\n    except Exception as e:\n        print(f'Error: {e}')\n```",
      "created_at": 1718000040.444,
      "task_id": "def789ghi123"
    }
  ],
  "task_ids": ["abc123def456", "def789ghi123"]
}
```

#### Example
```bash
curl -H "X-API-Key: $TOKEN" http://127.0.0.1:8787/v1/conversations/conv_a1b2c3d4e5 | jq .messages
```

---

### Send a Message

**`POST /v1/conversations/{conversation_id}/messages`**

Send a message to a conversation. Claude's response will automatically include context from all previous messages.

#### Request Body
```json
{
  "message": "Your message here",     // Required: the message to send
  "model": "sonnet",                  // Optional: override conversation's model
  "permission_mode": "bypassPermissions" // Optional: override conversation's permission mode
}
```

#### Response
Returns a task that represents the processing of your message:

```json
{
  "id": "abc123def456",
  "status": "running",
  "path": "/srv/myapp",
  "prompt": "Previous user request: Create a hello world function\n\nYour previous response: Here's a simple hello world function...\n\nNew user request: Now add error handling",
  "model": "opus",
  "session_id": null,
  "created_at": 1718000030.333,
  "ended_at": null,
  "exit_code": null
}
```

The `id` returned is a task ID that you can use to:
- Stream the response live
- Poll for completion
- Get the final response

#### Examples

**Simple message:**
```bash
curl -s -X POST http://127.0.0.1:8787/v1/conversations/conv_a1b2c3d4e5/messages \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Create a function to add two numbers"}' | jq .
```

**Override model:**
```bash
curl -s -X POST http://127.0.0.1:8787/v1/conversations/conv_a1b2c3d4e5/messages \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message":"Create a function to add two numbers",
    "model":"sonnet"
  }' | jq .
```

---

### Stream Message Response

**`GET /v1/conversations/{conversation_id}/messages/{message_id}/stream`** (Server-Sent Events)

Stream the live response to a message.

#### Response Format

Server-Sent Events stream with JSON payloads:

```json
{
  "type": "assistant",
  "message": {
    "content": [
      {"type": "text", "text": "Here's a function to add two numbers:\n\n```python\ndef add(a, b):\n    return a + b\n```"}
    ]
  }
}
```

Stream ends with:
```
event: end
data: {}
```

#### Example

```bash
curl -N "http://127.0.0.1:8787/v1/conversations/conv_a1b2c3d4e5/messages/abc123def456/stream?token=$TOKEN" | jq .
```

Or without the query param (requires SSE-capable client):

```bash
curl -N \
  -H "X-API-Key: $TOKEN" \
  "http://127.0.0.1:8787/v1/conversations/conv_a1b2c3d4e5/messages/abc123def456/stream" | jq .
```

#### JavaScript Example

```javascript
const token = 'your-token';
const conversationId = 'conv_a1b2c3d4e5';
const messageId = 'abc123def456';

const eventSource = new EventSource(
  `http://127.0.0.1:8787/v1/conversations/${conversationId}/messages/${messageId}/stream?token=${token}`
);

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Response:', data);
};

eventSource.addEventListener('end', () => {
  console.log('Response complete');
  eventSource.close();
});
```

---

### Clear Conversation

**`POST /v1/conversations/{conversation_id}/clear`**

Clear the message history and start fresh. This resets the underlying Claude session, so the next message will not have context from previous messages.

#### Response
```json
{
  "id": "conv_a1b2c3d4e5",
  "path": "/srv/myapp",
  "model": "opus",
  "permission_mode": "acceptEdits",
  "created_at": 1718000000.123,
  "message_count": 0
}
```

#### Example
```bash
curl -X POST http://127.0.0.1:8787/v1/conversations/conv_a1b2c3d4e5/clear \
  -H "X-API-Key: $TOKEN"
```

---

## How Context Works

The Conversations API automatically builds context from your conversation history. When you send a message, here's what happens:

1. **Your message is recorded** as a user message in the conversation
2. **Previous messages are retrieved** from the conversation history
3. **A context-aware prompt is built** that includes:
   - Summaries of previous user requests
   - Summaries of previous responses
   - Your new message
4. **Claude is given the full context** via the `--resume` flag to maintain session continuity
5. **Claude's response is recorded** as an assistant message in the conversation

This means:
- Claude understands the full conversation flow
- Context is persisted across messages
- You don't need to manually manage or repeat context
- `/clear` lets you start fresh when needed

---

## Use Cases

### 1. Multi-step Development Workflow

```bash
CONV=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path":"/srv/myapp"}' | jq -r .id)

# Message 1: Create an API endpoint
MSG1=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"Create a GET /api/users endpoint"}' | jq -r .id)

curl -N "http://127.0.0.1:8787/v1/conversations/$CONV/messages/$MSG1/stream?token=$TOKEN"

# Message 2: Claude already knows about the endpoint from Message 1
MSG2=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"Add error handling to the endpoint"}' | jq -r .id)

# Message 3: Still has context from both previous messages
MSG3=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"Now add tests for this endpoint"}' | jq -r .id)
```

### 2. Code Review Loop

```bash
CONV=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations \
  -H "X-API-Key: $TOKEN" \
  -d '{"path":"/srv/myapp"}' | jq -r .id)

# Review the code
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"Review my implementation for bugs"}' | jq .id

# Ask for improvements (Claude remembers what was reviewed)
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"What could be more efficient?"}' | jq .id

# Ask about specific improvements (still in context)
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"Show me the most critical optimization"}' | jq .id
```

### 3. Interactive Debugging

```bash
CONV=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations \
  -H "X-API-Key: $TOKEN" \
  -d '{"path":"/srv/myapp"}' | jq -r .id)

# Describe the problem
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"The login endpoint returns 500. Here is the error: ..."}' | jq .id

# Ask what might be wrong (Claude has the error context)
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"What is the most likely cause?"}' | jq .id

# Get specific fix (Claude remembers the error and suspected cause)
curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"How should I fix the database connection?"}' | jq .id
```

---

## Comparison with Other APIs

### vs. Tasks API

**Tasks** are one-off runs — each task is independent:
```bash
# Task 1: No context
curl ... -d '{"prompt":"Create a function"}'

# Task 2: No knowledge of Task 1
curl ... -d '{"prompt":"Add error handling"}'  # Doesn't know about the function
```

**Conversations** automatically maintain context:
```bash
# Message 1: Create a function
curl ... -d '{"message":"Create a function"}'

# Message 2: Claude knows about the function from Message 1
curl ... -d '{"message":"Add error handling"}'  # Claude remembers the function!
```

### vs. Sessions API

**Sessions** require manual command handling:
```bash
# Create session
SID=$(curl ... POST /sessions | jq .id)

# Send command — you manage the flow
curl ... POST /sessions/$SID/commands -d '{"command":"..."}'
```

**Conversations** are simpler — just send messages:
```bash
# Create conversation
CONV=$(curl ... POST /conversations | jq .id)

# Send message — context is automatic
curl ... POST /conversations/$CONV/messages -d '{"message":"..."}'
```

---

## Error Handling

### Common HTTP Status Codes

| Status | Meaning |
|--------|---------|
| `200` | Success |
| `400` | Invalid request (bad JSON, missing field) |
| `401` | Unauthorized (missing/invalid token) |
| `404` | Conversation or message not found |
| `500` | Server error |

### Example Error Response

```bash
curl -X POST http://127.0.0.1:8787/v1/conversations/invalid_id/messages \
  -H "X-API-Key: $TOKEN" \
  -d '{"message":"test"}'

# Returns (404):
# {"detail": "conversation not found"}
```

---

## Best Practices

1. **Create one conversation per task** — keep related messages together
2. **Use meaningful models** — set the model when creating a conversation if you have a preference
3. **Stream for long responses** — use the stream endpoint to see responses in real-time
4. **Check message history** — use `GET /conversations/{id}` to see the full context
5. **Clear when needed** — use `/clear` to start fresh with a new context when pivoting topics
6. **Respect rate limits** — leave time between rapid fire messages for Claude to process
7. **Handle errors gracefully** — 404 means the conversation is gone, create a new one

---

## Integration Examples

### cURL Script

```bash
#!/bin/bash

TOKEN=your-token
CONV=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path":"/srv/myapp"}' | jq -r .id)

while true; do
  read -p "You: " message
  MSG=$(curl -s -X POST http://127.0.0.1:8787/v1/conversations/$CONV/messages \
    -H "X-API-Key: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"message\":\"$message\"}" | jq -r .id)
  
  echo "Claude:"
  curl -s "http://127.0.0.1:8787/v1/conversations/$CONV/messages/$MSG/stream?token=$TOKEN" | \
    jq -r 'select(.type=="assistant") | .message.content[0].text' 2>/dev/null
done
```

### Node.js Client

```javascript
const fetch = require('node-fetch');
const EventSource = require('eventsource');

class CommanderConversation {
  constructor(token, serverUrl = 'http://127.0.0.1:8787') {
    this.token = token;
    this.serverUrl = serverUrl;
    this.conversationId = null;
  }

  async create(path, model = null, permissionMode = 'acceptEdits') {
    const response = await fetch(`${this.serverUrl}/v1/conversations`, {
      method: 'POST',
      headers: {
        'X-API-Key': this.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ path, model, permission_mode: permissionMode }),
    });
    const data = await response.json();
    this.conversationId = data.id;
    return data;
  }

  async sendMessage(message) {
    const response = await fetch(
      `${this.serverUrl}/v1/conversations/${this.conversationId}/messages`,
      {
        method: 'POST',
        headers: {
          'X-API-Key': this.token,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ message }),
      }
    );
    const data = await response.json();
    return data;
  }

  async streamMessage(messageId) {
    const url =
      `${this.serverUrl}/v1/conversations/${this.conversationId}/messages/${messageId}/stream` +
      `?token=${this.token}`;
    
    return new Promise((resolve, reject) => {
      const es = new EventSource(url);
      let response = '';

      es.onmessage = (event) => {
        const data = JSON.parse(event.data);
        if (data.type === 'assistant') {
          response += data.message.content[0].text || '';
        }
      };

      es.addEventListener('end', () => {
        es.close();
        resolve(response);
      });

      es.onerror = reject;
    });
  }
}

// Usage
(async () => {
  const client = new CommanderConversation('your-token');
  await client.create('/srv/myapp', 'opus');
  
  const msg1 = await client.sendMessage('Create a greeting function');
  const response1 = await client.streamMessage(msg1.id);
  console.log('Claude:', response1);

  const msg2 = await client.sendMessage('Add error handling');
  const response2 = await client.streamMessage(msg2.id);
  console.log('Claude:', response2);
})();
```

---

## See Also

- **[Tasks API](README.md#tasks--one-off-runs)** — One-off Claude runs
- **[Sessions API](README.md#sessions--stateful-like-the-repl)** — Lower-level stateful API
- **[Shells API](shellDoc.md)** — Run shell commands on the server
- **[Terminals API](README.md#terminals--persistent-interactive-claude)** — Interactive Claude in a PTY
