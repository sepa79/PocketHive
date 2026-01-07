# TCP Mock Server - UI User Guide

Complete guide to using the TCP Mock Server web interface.

## Table of Contents
1. [Getting Started](#getting-started)
2. [Requests Tab](#requests-tab)
3. [Mappings Tab](#mappings-tab)
4. [Scenarios Tab](#scenarios-tab)
5. [Test Console](#test-console)
6. [Verification Tab](#verification-tab)
7. [Settings](#settings)
8. [Tips & Tricks](#tips--tricks)

---

## Getting Started

### Accessing the UI
Open your browser and navigate to:
- **Local**: `http://localhost:8080`
- **Docker**: `http://localhost:8088/tcp-mock/`

### UI Overview
The interface has 7 main tabs:
- **Requests** - View incoming TCP requests and responses
- **Mappings** - Configure request/response mappings
- **Scenarios** - Manage stateful scenarios
- **Verification** - Verify expected request patterns
- **Test** - Send test messages to the server
- **Settings** - Configure global settings
- **Documentation** - Access help docs

### Dark Mode
Click the sun/moon icon in the top-right to toggle dark mode.

---

## Requests Tab

View and analyze all TCP requests received by the server.

### Features

#### Request Table
- **Status** - Green badge for matched, red for unmatched
- **Time** - When the request was received
- **Message** - The incoming TCP message (truncated)
- **Response** - The server's response (truncated)
- **Actions** - View details, create mapping, compare

#### Filtering
1. **Search** - Type text to filter by message or response content
2. **Date Range** - Select start and end dates
3. **Match Status** - Filter by matched/unmatched/all
4. **Pagination** - Navigate through pages (50 per page)

#### Actions
- **View Details** (eye icon) - See full request and response
- **Create Mapping** (plus icon) - Convert request to mapping
- **Compare** (compare icon) - Diff with previous request
- **Export JSON** - Download requests as JSON
- **Export CSV** - Download requests as CSV
- **Clear** - Remove all requests
- **Refresh** - Reload request list

### Recording Mode
Click the **REC** button in the header to start/stop recording:
- **Gray** - Recording disabled
- **Red (pulsing)** - Recording active
- Hover to see recorded count

When recording is active, all requests are captured for easy mapping creation.

---

## Mappings Tab

Create and manage request/response mappings.

### Creating a Mapping

1. Click **Add Mapping** button
2. Fill in the form:
   - **ID** - Unique identifier (auto-generated if blank)
   - **Priority** - Higher numbers match first (1-100)
   - **Request Pattern** - Regex pattern to match requests
   - **Response Template** - Response to send back

3. Click **Save**

### Quick Start Templates
Use the template dropdown to load pre-configured mappings:
- **Echo Handler** - Echo back the request
- **JSON API Mock** - Mock JSON REST API
- **SOAP Service Mock** - Mock SOAP web service
- **ISO-8583 Payment** - Financial transaction mock
- **Fault Injection** - Simulate connection errors
- **Delayed Response** - Add response delay
- **Proxy to Backend** - Forward to real server
- **Stateful Scenario** - Multi-step flow

### Advanced Matching

Beyond regex patterns, you can add:

#### JSON Path Matching
```
Type: jsonPath
Expression: $.transaction.type
Value: PAYMENT
```
Only matches if JSON path equals the value.

#### XML Path Matching
```
Type: xmlPath
Expression: //transaction/@type
Value: AUTH
```
Only matches if XPath equals the value.

#### Length Matching
```
Type: length
Value: 100
```
Only matches if message length > 100 bytes.

#### Contains Matching
```
Type: contains
Value: URGENT
```
Only matches if message contains "URGENT".

### Response Builder

#### Normal Response
Enter a template with variables:
```
{{message}}           - Echo the request
{{now}}              - Current timestamp
{{uuid}}             - Random UUID
{{randInt(1,100)}}   - Random integer
{{eval(#md5_hex(message))}} - MD5 hash
```

**Format Helpers:**
- **JSON** - Beautify JSON response
- **XML** - Prettify XML response
- **Hex** - Show hex representation
- **Vars** - List available variables

#### Fault Injection
Simulate network failures:
- **Connection Reset** - Close connection abruptly
- **Empty Response** - Send nothing
- **Malformed Response** - Send garbage data
- **Random Data** - Send random bytes

#### Proxy Mode
Forward requests to a real backend:
```
Proxy Target: backend-server:9090
```

### Response Configuration
- **Delimiter** - Message terminator (default `\n`)
- **Delay (ms)** - Add artificial latency

### Scenarios (Optional)
Create stateful multi-step flows:
- **Scenario Name** - e.g., "payment-flow"
- **Required State** - State needed to match (e.g., "Started")
- **New State** - State to set after match (e.g., "Authenticated")

### Bulk Operations

1. **Select Mappings** - Check boxes next to mappings
2. **Bulk Actions** appear:
   - **Delete** - Remove selected mappings
   - **Set Priority** - Change priority for all selected
   - **Clear Selection** - Uncheck all

### Filtering Mappings
- **Search** - Filter by ID or pattern
- **Priority Range** - Min/max priority filter
- **Features** - Filter by delay/scenario/advanced matching
- **Sort** - Click column headers to sort

### Import/Export
- **Export All** - Download all mappings as JSON
- **Export Selected** - Download selected mappings
- **Import** - Upload JSON/YAML mapping files (supports multiple files)

### Priority Conflicts
Yellow warning banner appears when mappings have overlapping patterns:
- **High Severity** - Same priority, ambiguous matching
- **Medium Severity** - Different priorities, may need adjustment

---

## Scenarios Tab

Manage stateful scenarios for multi-step flows.

### What are Scenarios?
Scenarios allow mappings to behave differently based on state:
1. Request arrives
2. Check current scenario state
3. Match mapping only if state matches
4. Send response
5. Update state for next request

### Example: Payment Flow
```
Scenario: payment-flow

Mapping 1:
  Pattern: ^AUTH.*
  Required State: Started
  Response: AUTH_OK
  New State: Authenticated

Mapping 2:
  Pattern: ^CAPTURE.*
  Required State: Authenticated
  Response: CAPTURE_OK
  New State: Completed
```

### Managing Scenarios
- **Add Scenario** - Create new scenario with initial state
- **Edit State** - Change current state
- **Reset** - Return to initial state
- **Delete** - Remove scenario (mappings remain)
- **Reset All** - Reset all scenarios at once

### Viewing Scenarios
Table shows:
- **Name** - Scenario identifier
- **Current State** - Active state
- **Mappings** - Count of related mappings

---

## Test Console

Send test messages to the TCP server and see responses.

### Message Types
Select from dropdown:
- **Plain Text** - Simple text messages
- **JSON** - JSON payloads
- **XML/SOAP** - XML documents
- **ISO-8583** - Financial messages
- **Hex String** - Hexadecimal data
- **Binary (Base64)** - Base64-encoded binary

### Code Editor
Full-featured editor with:
- **Syntax Highlighting** - Automatic based on message type
- **Line Numbers** - Easy reference
- **Auto-Complete** - Bracket matching
- **Theme Support** - Matches UI theme (light/dark)

### Configuration
- **Delimiter** - Message terminator (default `\n`)
- **Encoding** - UTF-8, ASCII, or ISO-8859-1

### Sending Tests
1. Select message type
2. Click **Load Template** to populate example
3. Edit message as needed
4. Click **Send Test**
5. View response in right panel

### Response Display
Shows:
- Response text
- Duration (ms)
- Delimiter used
- Any delays applied

---

## Verification Tab

Verify that expected requests were received.

### Adding Verifications
1. Enter **Pattern** - Regex to match requests
2. Select **Count Type**:
   - **Exactly** - Must match exact count
   - **At Least** - Must match minimum count
   - **At Most** - Must not exceed count
3. Enter **Count** - Expected number
4. Click **Add Verification**

### Running Verifications
1. Add one or more verifications
2. Click **Run** button
3. Results show:
   - **Green badge** - Verification passed
   - **Red badge** - Verification failed
   - **Actual count** - How many matched

### Example
```
Pattern: ^PAYMENT.*
Count Type: At Least
Count: 10
```
Verifies at least 10 payment requests were received.

---

## Settings

Configure global server behavior.

### Global Headers
Add headers to all responses:
1. Enter header name and value
2. Click **+** button
3. Headers apply to all mappings

### Server Configuration
- **Default Delay (ms)** - Latency for all responses
- **Default Timeout (ms)** - Connection timeout
- **Log Level** - DEBUG, INFO, WARN, ERROR
- **Enable CORS** - Allow cross-origin requests

### Persistence
- **Save** - Store settings to browser localStorage
- **Reset** - Restore default settings

---

## Tips & Tricks

### Keyboard Shortcuts
- `Ctrl+N` - New mapping
- `Ctrl+S` - Save mapping
- `Ctrl+F` - Focus search
- `Ctrl+K` - Command palette
- `Ctrl+D` - Duplicate mapping
- `Ctrl+Z` - Undo
- `Ctrl+Shift+Z` - Redo
- `Escape` - Close modal

### Best Practices

#### Priority Strategy
- **100** - Exact matches (specific IDs)
- **50** - Partial matches (prefixes)
- **10** - Pattern matches (regex)
- **1** - Catch-all (default)

#### Pattern Tips
- Use `^` and `$` for exact matches: `^ECHO$`
- Use `.*` for wildcards: `^PAYMENT.*`
- Escape special chars: `\\.` for literal dot
- Test patterns with online regex tools

#### Performance
- Keep priority conflicts to minimum
- Use specific patterns over broad ones
- Limit recording when not needed
- Export/clear old requests periodically

#### Debugging
1. Enable recording
2. Send test request
3. Check if matched (green badge)
4. If unmatched, check pattern in mapping
5. Use "Create Mapping" to auto-generate

### Common Patterns

#### Echo Server
```
Pattern: .*
Response: {{message}}
Priority: 1
```

#### JSON API
```
Pattern: ^\{.*"type":".*"\}$
Response: {"status":"success","data":{{message}}}
Priority: 10
```

#### SOAP Service
```
Pattern: <soap:Envelope.*
Response: <soap:Envelope><soap:Body><Response>OK</Response></soap:Body></soap:Envelope>
Priority: 20
```

#### ISO-8583
```
Pattern: ^0200.*
Response: 0210{{message:4}}00
Priority: 50
```

### Troubleshooting

#### Request Not Matching
1. Check pattern syntax (valid regex?)
2. Verify priority (higher priority mapping blocking?)
3. Check advanced matching criteria
4. Test pattern with online regex tool

#### Response Not Showing
1. Check delimiter setting
2. Verify response template syntax
3. Look for template variable errors
3. Check server logs

#### Slow Performance
1. Clear old requests (thousands slow UI)
2. Reduce mapping count if possible
3. Disable recording when not needed
4. Use pagination for large datasets

---

## Next Steps

- Read [QUICK-REFERENCE.md](QUICK-REFERENCE.md) for command reference
- See [SCENARIO-SETUP.md](SCENARIO-SETUP.md) for scenario examples
- Check [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md) for production setup
- Review [WIREMOCK-PARITY.md](WIREMOCK-PARITY.md) for WireMock migration

## Support

For issues or questions:
1. Check documentation in the **Documentation** tab
2. Review examples in the `mappings/` folder
3. Test with the **Test Console**
4. Enable DEBUG logging in **Settings**
