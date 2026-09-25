const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const context=vm.createContext({});
for(const file of ['mapping-filter.js','priority-manager.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../../src/main/resources/static',file),'utf8'),context);
vm.runInContext('this.Filter=MappingFilterModule;this.Priority=PriorityManagerModule;',context);
const mappings=[{id:'one',requestPattern:'^ECHO.*',priority:10},{id:'two',requestPattern:'^PAYMENT.*',priority:10}];
test('search uses the canonical mapping requestPattern',()=>{
 const filter=new context.Filter();filter.filters.search='echo';
 assert.deepEqual(Array.from(filter.filter(mappings),m=>m.id),['one']);
});
test('priority warnings compare actual patterns instead of absent UI aliases',()=>{
 const manager=new context.Priority();
 assert.equal(manager.detectConflicts(mappings).length,0);
 assert.equal(manager.detectConflicts([...mappings,{id:'three',requestPattern:'^ECHO.*',priority:10}]).length,1);
});

test('request pattern sorting toggles descending and ascending without mutating mappings',()=>{
 const filter=new context.Filter();
 filter.setSortBy('requestPattern');
 assert.deepEqual(Array.from(filter.filter(mappings),m=>m.id),['two','one']);
 filter.setSortBy('requestPattern');
 assert.deepEqual(Array.from(filter.filter(mappings),m=>m.id),['one','two']);
 assert.deepEqual(mappings.map(m=>m.id),['one','two']);
});
