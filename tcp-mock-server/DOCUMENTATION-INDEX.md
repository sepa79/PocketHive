# TCP Mock Server - Documentation Index

## 📚 Complete Documentation Suite

This directory contains comprehensive documentation for the TCP Mock Server's WireMock parity implementation.

---

## 🎯 Start Here

### For Developers
👉 **[QUICK-REFERENCE.md](QUICK-REFERENCE.md)** - Quick reference card with syntax and examples

### For Architects
👉 **[TEAM-SUMMARY.md](TEAM-SUMMARY.md)** - Executive summary of what was done and why

### For Users
👉 **[WIREMOCK-PARITY-COMPLETE.md](WIREMOCK-PARITY-COMPLETE.md)** - Complete feature guide with examples

### For Migration
👉 **[MIGRATION-GUIDE.md](MIGRATION-GUIDE.md)** - How to adopt new features in existing mappings

---

## 📖 Documentation Files

### 1. TEAM-SUMMARY.md
**Audience:** Team leads, architects, project managers

**Contents:**
- Executive summary
- What was done and why
- Files created/modified
- Feature parity matrix
- Testing recommendations
- Next steps

**When to read:** First document to understand the scope of changes

---

### 2. WIREMOCK-PARITY-COMPLETE.md
**Audience:** Developers, QA engineers, users

**Contents:**
- Complete feature documentation
- Request matching capabilities
- Response templating syntax
- Fault injection guide
- Proxying configuration
- Stateful scenarios
- Usage examples for all features
- Feature comparison matrix

**When to read:** When implementing new mappings or using advanced features

---

### 3. MIGRATION-GUIDE.md
**Audience:** Developers with existing mappings

**Contents:**
- Backward compatibility notes
- How to adopt new features
- Before/after examples
- Best practices
- Testing your migrations
- Rollback plan

**When to read:** When updating existing mappings to use new features

---

### 4. QUICK-REFERENCE.md
**Audience:** Developers (daily reference)

**Contents:**
- Request matching syntax
- Template variable reference
- Field extraction syntax
- Fault injection types
- Admin API endpoints
- Complete mapping example

**When to read:** Daily reference while writing mappings

---

### 5. TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md
**Audience:** Technical leads, architects

**Contents:**
- Detailed implementation summary
- Critical architectural fixes
- Files created/modified
- Feature parity matrix
- Testing checklist
- Performance impact analysis

**When to read:** When reviewing implementation details or troubleshooting

---

### 6. README.md
**Audience:** All users

**Contents:**
- Feature overview
- Quick start guide
- API endpoints
- Configuration options
- Monitoring & observability

**When to read:** First-time setup and general reference

---

## 🗂️ Example Mappings

All example mappings are in the `mappings/` directory:

### Basic Examples
- `echo-colon-format.json` - Simple echo handler
- `json-request.json` - Basic JSON response
- `socket-test-request.json` - Socket testing

### Advanced Matching
- `json-advanced-matching.json` - JSONPath field matching
- `xml-soap-matching.json` - XML/SOAP field matching
- `regex-extraction.json` - Regex group extraction
- `length-based-matching.json` - Length-based matching

### Special Features
- `fault-injection.json` - Connection reset fault
- `fault-injection-all.json` - All fault types
- `proxy-example.json` - Proxy to real system
- `slow-response.json` - Fixed delay simulation

### Binary Protocols
- `iso8583-binary.json` - ISO-8583 binary messages
- `banking-stx-etx.json` - STX/ETX delimited messages

### Complex Scenarios
- `payment-request.json` - Payment processing
- `secure-message.json` - Secure messaging
- `payment-flow.yaml` - Multi-step payment flow

---

## 🔍 Quick Navigation

### I want to...

**...understand what changed**
→ Read [TEAM-SUMMARY.md](TEAM-SUMMARY.md)

**...write a new mapping**
→ Use [QUICK-REFERENCE.md](QUICK-REFERENCE.md) + examples in `mappings/`

**...update existing mappings**
→ Follow [MIGRATION-GUIDE.md](MIGRATION-GUIDE.md)

**...learn all features**
→ Read [WIREMOCK-PARITY-COMPLETE.md](WIREMOCK-PARITY-COMPLETE.md)

**...review implementation**
→ Read [TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md](TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md)

**...get started quickly**
→ Read [README.md](README.md) Quick Start section

---

## 🎓 Learning Path

### Beginner
1. Read [README.md](README.md) - Understand what the server does
2. Review examples in `mappings/` - See basic patterns
3. Use [QUICK-REFERENCE.md](QUICK-REFERENCE.md) - Learn syntax

### Intermediate
1. Read [WIREMOCK-PARITY-COMPLETE.md](WIREMOCK-PARITY-COMPLETE.md) - Learn all features
2. Follow [MIGRATION-GUIDE.md](MIGRATION-GUIDE.md) - Adopt advanced features
3. Experiment with fault injection and proxying

### Advanced
1. Read [TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md](TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md) - Understand architecture
2. Review source code in `src/main/java/`
3. Extend with custom handlers or matchers

---

## 📞 Support

### Documentation Issues
If documentation is unclear or incomplete, please update this index and the relevant document.

### Feature Requests
Review [WIREMOCK-PARITY-COMPLETE.md](WIREMOCK-PARITY-COMPLETE.md) to see if the feature already exists.

### Bug Reports
Include:
- Mapping configuration (JSON/YAML)
- Request sent
- Expected vs actual response
- Logs from `/__admin/requests`

---

## ✅ Documentation Checklist

- ✅ Executive summary for team leads
- ✅ Complete feature documentation
- ✅ Migration guide for existing users
- ✅ Quick reference for daily use
- ✅ Implementation details for architects
- ✅ Example mappings for all features
- ✅ This index document

**All documentation is complete and ready for use.**
