# TCP Mock Server - File Inventory for Cleanup

## ✅ KEEP - Production Files

### Source Code (All Keep)
```
src/main/java/io/pockethive/tcpmock/
├── controller/
│   ├── MetricsController.java ✅
│   ├── WebController.java ✅
│   └── WireMockCompatController.java ✅
├── handler/
│   ├── FaultInjectionHandler.java ✅
│   ├── Iso8583Handler.java ✅
│   ├── TcpProxyHandler.java ✅
│   └── UnifiedTcpRequestHandler.java ✅
├── model/
│   ├── MessageTypeMapping.java ✅
│   ├── ProcessedResponse.java ✅
│   ├── TcpRequest.java ✅
│   └── ... (all models) ✅
├── service/
│   ├── EnhancedTemplateEngine.java ✅
│   ├── MessageTypeRegistry.java ✅
│   ├── RecordingMode.java ✅
│   ├── RequestStore.java ✅
│   ├── ScenarioManager.java ✅
│   └── ... (all services) ✅
└── util/
    ├── AdvancedRequestMatcher.java ✅
    ├── TcpMetrics.java ✅
    └── ... (all utils) ✅
```

### UI Files (Keep These)
```
src/main/resources/static/
├── index-complete.html ✅ (rename to index.html)
├── app-ultimate.js ✅ (rename to app.js)
├── bulk-operations.js ✅
├── diff-viewer.js ✅
├── global-settings.js ✅
├── import-export.js ✅
├── mapping-filter.js ✅
├── priority-manager.js ✅
├── recording.js ✅
├── request-journal.js ✅
├── response-builder.js ✅
├── shortcuts.js ✅
├── templates.js ✅
├── ui-modules.js ✅
├── undo-redo.js ✅
└── validator.js ✅
```

### Documentation (Keep These 10)
```
Root directory:
├── README-PRODUCTION.md ✅ (rename to README.md)
├── EXECUTIVE-SUMMARY.md ✅
├── HANDOVER.md ✅
├── DOCUMENTATION-INDEX-FINAL.md ✅ (rename to DOCUMENTATION-INDEX.md)
├── WIREMOCK-PARITY.md ✅
├── POLISH-FEATURES.md ✅
├── DEPLOYMENT-CHECKLIST.md ✅
├── MIGRATION-GUIDE.md ✅
├── QUICK-REFERENCE.md ✅
├── SCENARIO-SETUP.md ✅
├── FINAL-SUMMARY.md ✅
└── CLEANUP-NOTES.md ✅ (delete after cleanup)
```

### Configuration (Keep All)
```
Root directory:
├── pom.xml ✅
├── Dockerfile ✅
├── docker-compose.tcp-mock.yml ✅
├── build.sh ✅
└── mappings/ (all 18 files) ✅
```

## ❌ DELETE - Redundant Files

### Old UI Files (Delete 9)
```
src/main/resources/static/
├── index.html ❌
├── index-advanced.html ❌
├── index-enterprise.html ❌
├── app.js ❌
├── app-complete.js ❌
├── app-enterprise.js ❌
├── analytics-dashboard.html ❌
├── analytics-dashboard.js ❌
└── user-guide.html ❌
```

### Interim Documentation (Delete 14)
```
Root directory:
├── COMPLETE-EVALUATION.md ❌
├── TCP-GAPS-PLUGGED.md ❌
├── TCP-SUPPORT-ANALYSIS.md ❌
├── TEAM-SUMMARY.md ❌
├── UI-COMPLETE.md ❌
├── UI-ENHANCEMENT-PLAN.md ❌
├── UI-ENHANCEMENTS.md ❌
├── UI-EVALUATION.md ❌
├── UI-ULTIMATE.md ❌
├── UI-VISUAL-GUIDE.md ❌
├── WIREMOCK-COMPARISON.md ❌
├── WIREMOCK-CONTRACTS-MAINTAINED.md ❌
├── WIREMOCK-PARITY-COMPLETE.md ❌
├── TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md ❌
└── README.md ❌ (old version, will be replaced)
```

## 🔄 RENAME - Production Files

### After Cleanup
```bash
# Rename UI files
mv src/main/resources/static/index-complete.html src/main/resources/static/index.html
mv src/main/resources/static/app-ultimate.js src/main/resources/static/app.js

# Rename documentation
mv README-PRODUCTION.md README.md
mv DOCUMENTATION-INDEX-FINAL.md DOCUMENTATION-INDEX.md
```

## 📝 CODE CHANGES Required

### WebController.java (Line 26)
```java
// BEFORE
return "forward:/index-complete.html";

// AFTER
return "forward:/index.html";
```

## 🚀 Cleanup Script

```bash
#!/bin/bash
# Run this in WSL from project root

echo "Starting cleanup..."

# Delete old UI files
cd src/main/resources/static
rm -f index.html index-advanced.html index-enterprise.html
rm -f app.js app-complete.js app-enterprise.js
rm -f analytics-dashboard.html analytics-dashboard.js user-guide.html
echo "✅ Deleted 9 old UI files"

# Rename production UI files
mv index-complete.html index.html
mv app-ultimate.js app.js
echo "✅ Renamed production UI files"

# Go back to root
cd ../../../../..

# Delete interim documentation
rm -f COMPLETE-EVALUATION.md TCP-GAPS-PLUGGED.md TCP-SUPPORT-ANALYSIS.md
rm -f TEAM-SUMMARY.md UI-COMPLETE.md UI-ENHANCEMENT-PLAN.md
rm -f UI-ENHANCEMENTS.md UI-EVALUATION.md UI-ULTIMATE.md UI-VISUAL-GUIDE.md
rm -f WIREMOCK-COMPARISON.md WIREMOCK-CONTRACTS-MAINTAINED.md
rm -f WIREMOCK-PARITY-COMPLETE.md TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md
rm -f README.md DOCUMENTATION-INDEX.md
echo "✅ Deleted 14 interim documentation files"

# Rename production documentation
mv README-PRODUCTION.md README.md
mv DOCUMENTATION-INDEX-FINAL.md DOCUMENTATION-INDEX.md
echo "✅ Renamed production documentation"

# Update WebController.java
sed -i 's/index-complete.html/index.html/g' src/main/java/io/pockethive/tcpmock/controller/WebController.java
echo "✅ Updated WebController.java"

# Delete cleanup notes (this file)
rm -f CLEANUP-NOTES.md
echo "✅ Deleted cleanup notes"

echo ""
echo "🎉 Cleanup complete!"
echo ""
echo "Next steps:"
echo "1. Review changes: git status"
echo "2. Rebuild: mvn clean package"
echo "3. Test: java -jar target/tcp-mock-server-1.0.0.jar"
echo "4. Verify UI: http://localhost:8080"
```

## 📊 File Count Summary

### Before Cleanup
- UI Files: 24 (9 redundant)
- Documentation: 25 (14 redundant)
- Total Redundant: 23 files

### After Cleanup
- UI Files: 15 (production only)
- Documentation: 11 (production only)
- Total Files: 26 (all production)

## ✅ Verification Checklist

After cleanup, verify:
- [ ] UI loads at http://localhost:8080
- [ ] All 15 JavaScript modules load
- [ ] No 404 errors in browser console
- [ ] All tabs work (Requests, Mappings, Scenarios, Verification, Test, Settings)
- [ ] Documentation links work
- [ ] Maven build succeeds
- [ ] Tests pass (mvn test)

## 🎯 Final State

### Production Files Only
- ✅ 1 HTML file (index.html)
- ✅ 15 JavaScript modules
- ✅ 11 documentation files
- ✅ 18 example mappings
- ✅ All Java source code
- ✅ Build configurations

### Zero Redundancy
- ❌ No old UI versions
- ❌ No interim documentation
- ❌ No placeholder code
- ❌ No TODO comments
- ❌ No deprecated files

---

**Execute**: Run cleanup script above  
**Verify**: Check verification checklist  
**Deploy**: Follow DEPLOYMENT-CHECKLIST.md
