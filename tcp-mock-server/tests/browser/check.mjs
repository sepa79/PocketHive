import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
const require = createRequire(new URL('../../../vscode-pockethive/package.json', import.meta.url));
const { chromium } = require('playwright');
const AxeBuilder = require('@axe-core/playwright').default;
const base = process.env.PH_UI_TEST_URL || 'http://localhost:18089';
const native = process.env.PH_NATIVE_TEST_URL || 'http://localhost:18090';
const output = process.env.PH_UI_TEST_OUTPUT || '/tmp/ph-tcp-playwright';
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const report = { steps: [], errors: [], accessibility: [], screenshots: [] };
let phase = '', page;
async function shot(name) { await page.locator('.notification, .toast').last().waitFor({state:'detached'}); await page.screenshot({path:`${output}/${name}.png`,fullPage:false,animations:'disabled'}); report.screenshots.push(name); }
async function step(name, action) {
 phase=name;
 try { await action(); report.steps.push({name,passed:true}); }
 catch(e) { report.steps.push({name,passed:false,error:e.message}); await shot(`failure-${report.steps.length}`); }
}
function watch(p) { p.setDefaultTimeout(6000); p.on('pageerror',e=>report.errors.push({phase,message:e.message})); }
async function dismissTour() {try{await page.getByRole('button',{name:'Skip',exact:true}).click({timeout:2500});}catch{}}
try {
 const context=await browser.newContext({viewport:{width:1920,height:1080}});
 page=await context.newPage(); watch(page);
 await step('PocketHive login and public-port redirect',async()=>{
  await page.goto(base+'/login');
  await page.getByPlaceholder('local-admin').fill('local-admin');
  await page.getByRole('button',{name:'Sign in (DEV)',exact:true}).click();
  await page.waitForURL(base+'/'); await page.goto(base+'/tcp-mock');
  assert.equal(page.url(),base+'/tcp-mock/');
  await page.locator('#loginModal').waitFor({state:'hidden'}); await dismissTour();
 });
 await step('Mappings load',async()=>{
  await page.locator('[data-tab="mappings"]').click();
  await page.locator('#mappingsTable tr').first().waitFor();
  assert.ok(await page.locator('#mappingsTable tr').count()>0); await shot('02-mappings-desktop');
 });
 await step('Mapping search and clear',async()=>{
  await page.locator('#mappingSearch').fill('^ECHO:');
  await page.waitForFunction(()=>document.querySelectorAll('#mappingsTable tr').length===1,{},{timeout:2000});
 });
 await page.locator('#mappingSearch').fill('');
 await step('Pattern column sorts both directions',async()=>{
  const patterns=()=>page.locator('#mappingsTable tr td:nth-child(3)').allTextContents();
  const expected=(await patterns()).map(p=>p.trim().toLowerCase()).sort();
  const header=page.getByRole('columnheader',{name:/^Pattern/});
  await header.click();
  assert.deepEqual((await patterns()).map(p=>p.trim().toLowerCase()),[...expected].reverse());
  await header.click();
  assert.deepEqual((await patterns()).map(p=>p.trim().toLowerCase()),expected);
 });
 const defaultWorkspaceName=await page.locator('#currentWorkspaceName').textContent();
 const workspaceName='Playwright catalogue '+Date.now(); let workspaceCreated=false;
 await step('Failed workspace creation preserves form',async()=>{
  await page.locator('#workspaceSwitcher').click();
  await page.locator('#workspaceDropdown').getByRole('button',{name:/New Workspace/}).click();
  await page.locator('#workspaceName').fill(workspaceName);
  const matcher='**/tcp-mock/api/workspaces';
  await page.route(matcher,route=>route.request().method()==='POST'?route.fulfill({status:503,contentType:'application/json',body:'{"error":"test failure"}'}):route.continue());
  try {
   await page.locator('#workspaceCreateModal').getByRole('button',{name:/Create/}).click();
   await page.locator('#workspaceCreateStatus').getByText(/Could not confirm/).waitFor();
   assert.equal(await page.locator('#workspaceName').inputValue(),workspaceName);await shot('03-workspace-failure');
  } finally {await page.unroute(matcher);}
 });
 await step('Workspace CRUD, reload and failed rename/delete preserve state',async()=>{
  await page.locator('#workspaceCreateModal').getByRole('button',{name:/Create/}).click();
  await page.locator('#workspaceCreateModal').waitFor({state:'hidden'});workspaceCreated=true;
  if(!await page.locator('#workspaceDropdown').isVisible())await page.locator('#workspaceSwitcher').click();
  await page.locator('#workspaceList').getByRole('button',{name:workspaceName,exact:true}).click();
  assert.equal(await page.locator('#currentWorkspaceName').textContent(),workspaceName);
  if(!await page.locator('#workspaceDropdown').isVisible())await page.locator('#workspaceSwitcher').click();
  let row=page.locator('#workspaceList > div').filter({has:page.getByRole('button',{name:workspaceName,exact:true})});
  const mutationMatcher='**/tcp-mock/api/workspaces/*';
  await page.route(mutationMatcher,route=>route.request().method()==='PUT'?route.fulfill({status:503,contentType:'application/json',body:'{"error":"test failure"}'}):route.continue());
  try {
   page.once('dialog',d=>d.accept(workspaceName+' rejected rename'));
   await row.getByRole('button',{name:'Rename',exact:true}).click();
   await page.locator('#workspaceStatus').getByText(/Request failed/).waitFor();
   assert.equal(await page.locator('#currentWorkspaceName').textContent(),workspaceName);
   await row.getByRole('button',{name:'Rename',exact:true}).waitFor();
  } finally {await page.unroute(mutationMatcher);}
  page.once('dialog',d=>d.accept(workspaceName+' renamed'));
  await row.getByRole('button',{name:'Rename',exact:true}).click();
  await page.locator('#workspaceList').getByRole('button',{name:workspaceName+' renamed',exact:true}).waitFor();
  await page.reload(); await page.locator('#loginModal').waitFor({state:'hidden'});
  await page.locator('#currentWorkspaceName').getByText(workspaceName+' renamed',{exact:true}).waitFor();
  await page.locator('#workspaceSwitcher').click();
  row=page.locator('#workspaceList > div').filter({has:page.getByRole('button',{name:workspaceName+' renamed',exact:true})});
  await page.route(mutationMatcher,route=>route.request().method()==='DELETE'?route.fulfill({status:503,contentType:'application/json',body:'{"error":"test failure"}'}):route.continue());
  try {
   page.once('dialog',d=>d.accept());
   await row.getByRole('button',{name:'Delete',exact:true}).click();
   await page.locator('#workspaceStatus').getByText(/Request failed/).waitFor();
   assert.equal(await page.locator('#currentWorkspaceName').textContent(),workspaceName+' renamed');
   await row.getByRole('button',{name:'Delete',exact:true}).waitFor();
  } finally {await page.unroute(mutationMatcher);}
  page.once('dialog',d=>d.accept()); await row.getByRole('button',{name:'Delete',exact:true}).click();
  await row.waitFor({state:'detached'});workspaceCreated=false;
  assert.equal(await page.locator('#currentWorkspaceName').textContent(),defaultWorkspaceName);
  await page.locator('#workspaceSwitcher').click();
 });
 await step('Mapping editor opens and cancels',async()=>{
  await page.locator('[data-tab="mappings"]').click();
  await page.locator('#addMappingBtn').click(); await page.locator('#mappingModal').waitFor({state:'visible'});
  await page.locator('#mappingPattern').fill('^PLAYWRIGHT_ONLY$');await shot('04-mapping-editor');
  await page.locator('#cancelMappingBtn').click(); await page.locator('#mappingModal').waitFor({state:'hidden'});
 });
 await step('Mapping create, edit and delete',async()=>{
  const id='playwright-mapping-'+Date.now();
  await page.locator('#addMappingBtn').click();
  await page.locator('#mappingId').fill(id);
  await page.locator('#mappingPattern').fill('^PLAYWRIGHT_ONLY$');
  await page.locator('#mappingResponse').fill('PLAYWRIGHT OK');
  await page.locator('#saveMappingBtn').click();
  await page.locator('#mappingModal').waitFor({state:'hidden'});
  const row=page.locator('#mappingsTable tr').filter({hasText:id});
  await row.waitFor();
  await row.getByRole('button',{name:'Edit mapping',exact:true}).click();
  assert.equal(await page.locator('#mappingResponse').inputValue(),'PLAYWRIGHT OK');
  await page.locator('#mappingResponse').fill('PLAYWRIGHT EDITED');
  await page.locator('#saveMappingBtn').click();
  await page.locator('#mappingModal').waitFor({state:'hidden'});
  await row.getByRole('button',{name:'Edit mapping',exact:true}).click();
  assert.equal(await page.locator('#mappingResponse').inputValue(),'PLAYWRIGHT EDITED');
  await page.locator('#cancelMappingBtn').click();
  page.once('dialog',d=>d.accept());await row.getByRole('button',{name:'Delete mapping',exact:true}).click();
  await row.waitFor({state:'detached'});
 });
 for(const tab of ['dashboard','scenarios','verification','test','settings','docs','mappings']) {
  await step(`Navigate ${tab}`,async()=>{await page.locator(`[data-tab="${tab}"]`).click();await page.locator(`#${tab}Tab`).waitFor({state:'visible'});});
 }
 for(const width of [1366,768,320]) {
  await step(`Layout ${width}`,async()=>{
   await page.setViewportSize({width,height:width===1366?768:900});await shot(`05-mappings-${width}`);
   const size=await page.evaluate(()=>({viewport:innerWidth,document:document.documentElement.scrollWidth}));
   assert.ok(size.document<=size.viewport,`Horizontal page overflow: ${size.document}px / ${size.viewport}px`);
   const title=await page.locator('header h1').boundingBox();
   const header=await page.locator('header').boundingBox();
   assert.ok(title.y>=header.y && title.y+title.height<=header.y+header.height,'Header title is clipped');
  });
 }
 await page.setViewportSize({width:1920,height:1080});
 await step('Dark theme',async()=>{await page.locator('#themeToggle').click();assert.ok(await page.locator('html').evaluate(e=>e.classList.contains('dark')));await shot('07-dark-mappings');await page.locator('#themeToggle').click();});
 await step('Accessibility scan',async()=>{
  await page.waitForFunction(()=>getComputedStyle(document.body).backgroundColor==='rgb(249, 250, 251)');
  const result=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
  report.accessibility=result.violations.map(v=>({id:v.id,impact:v.impact,description:v.description,nodes:v.nodes.map(n=>({target:n.target,summary:n.failureSummary}))}));
  assert.equal(report.accessibility.length,0,'Accessibility violations: '+report.accessibility.map(v=>v.id).join(', '));
 });
 if(workspaceCreated) report.errors.push({phase:'cleanup',message:'Test workspace remains: '+workspaceName});
 const standalone=await browser.newContext({viewport:{width:1366,height:900}});
 page=await standalone.newPage();watch(page);
 await step('Native login, mappings and logout',async()=>{
  await page.goto(native);
  await page.locator('#loginUsername').fill('admin');await page.locator('#loginPassword').fill('admin');
  await page.locator('#loginForm').getByRole('button',{name:'Sign in',exact:true}).click();
  await page.locator('#loginModal').waitFor({state:'hidden'});await dismissTour();
  await page.locator('[data-tab="mappings"]').click();await page.locator('#mappingsTable tr').first().waitFor();
  await shot('06-native-mappings');
  await page.locator('#userMenuBtn').click();await page.locator('#userMenuDropdown').getByRole('button',{name:/Logout|Sign out/i}).click();
  await page.locator('#loginModal').waitFor({state:'visible'});
 });
} finally {
 await browser.close();await writeFile(`${output}/report.json`,JSON.stringify(report,null,2));
 console.log(JSON.stringify({steps:report.steps,errors:report.errors,accessibility:report.accessibility.map(v=>({id:v.id,impact:v.impact,count:v.nodes.length}))},null,2));
}
process.exitCode=report.steps.some(s=>!s.passed)||report.errors.length?1:0;
