# TCP Mock Server - Cleanup Notes

## Files to Remove (Redundant)

### Static UI Files (src/main/resources/static/)
- ❌ `index.html` - Old basic UI
- ❌ `index-advanced.html` - Intermediate UI
- ❌ `index-enterprise.html` - Intermediate UI
- ❌ `app.js` - Old basic UI script
- ❌ `app-complete.js` - Intermediate UI script
- ❌ `app-enterprise.js` - Intermediate UI script
- ❌ `analytics-dashboard.html` - Unused feature
- ❌ `analytics-dashboard.js` - Unused feature
- ❌ `user-guide.html` - Outdated guide

### Documentation Files (Root)
- ❌ `COMPLETE-EVALUATION.md` - Interim evaluation
- ❌ `TCP-GAPS-PLUGGED.md` - Interim notes
- ❌ `TCP-SUPPORT-ANALYSIS.md` - Interim analysis
- ❌ `TEAM-SUMMARY.md` - Interim summary
- ❌ `UI-COMPLETE.md` - Interim UI docs
- ❌ `UI-ENHANCEMENT-PLAN.md` - Planning doc
- ❌ `UI-ENHANCEMENTS.md` - Interim enhancements
- ❌ `UI-EVALUATION.md` - Interim evaluation
- ❌ `UI-ULTIMATE.md` - Interim ultimate docs
- ❌ `UI-VISUAL-GUIDE.md` - Interim guide
- ❌ `WIREMOCK-COMPARISON.md` - Interim comparison
- ❌ `WIREMOCK-CONTRACTS-MAINTAINED.md` - Interim notes
- ❌ `WIREMOCK-PARITY-COMPLETE.md` - Interim parity
- ❌ `TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md` - Interim summary

## Files to Keep (Production)

### Static UI Files
- ✅ `index-complete.html` - Production UI
- ✅ `app-ultimate.js` - Production UI controller
- ✅ All module files (*.js) - Required functionality

### Documentation Files
- ✅ `README.md` - Main documentation
- ✅ `WIREMOCK-PARITY.md` - Feature documentation
- ✅ `POLISH-FEATURES.md` - Polish features documentation
- ✅ `DEPLOYMENT-CHECKLIST.md` - Deployment guide
- ✅ `MIGRATION-GUIDE.md` - Migration guide
- ✅ `QUICK-REFERENCE.md` - Quick reference
- ✅ `SCENARIO-SETUP.md` - Scenario setup guide
- ✅ `DOCUMENTATION-INDEX.md` - Documentation index
- ✅ `FINAL-SUMMARY.md` - Final summary

## Manual Cleanup Required

Run these commands in WSL:
```bash
cd /home/tday/myprojects/PocketHiveD/tcp-mock-server

# Remove redundant UI files
rm -f src/main/resources/static/index.html
rm -f src/main/resources/static/index-advanced.html
rm -f src/main/resources/static/index-enterprise.html
rm -f src/main/resources/static/app.js
rm -f src/main/resources/static/app-complete.js
rm -f src/main/resources/static/app-enterprise.js
rm -f src/main/resources/static/analytics-dashboard.html
rm -f src/main/resources/static/analytics-dashboard.js
rm -f src/main/resources/static/user-guide.html

# Remove redundant documentation
rm -f COMPLETE-EVALUATION.md
rm -f TCP-GAPS-PLUGGED.md
rm -f TCP-SUPPORT-ANALYSIS.md
rm -f TEAM-SUMMARY.md
rm -f UI-COMPLETE.md
rm -f UI-ENHANCEMENT-PLAN.md
rm -f UI-ENHANCEMENTS.md
rm -f UI-EVALUATION.md
rm -f UI-ULTIMATE.md
rm -f UI-VISUAL-GUIDE.md
rm -f WIREMOCK-COMPARISON.md
rm -f WIREMOCK-CONTRACTS-MAINTAINED.md
rm -f WIREMOCK-PARITY-COMPLETE.md
rm -f TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md
```

## Rename Production Files

```bash
# Rename production UI to standard names
mv src/main/resources/static/index-complete.html src/main/resources/static/index.html
mv src/main/resources/static/app-ultimate.js src/main/resources/static/app.js
```

## Update WebController

After renaming, update WebController.java:
```java
@GetMapping("/")
public String index() {
    return "forward:/index.html";  // Changed from index-complete.html
}
```
