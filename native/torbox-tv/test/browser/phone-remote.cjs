const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
let browser,server,origin;
const pageSource=fs.readFileSync(path.resolve(__dirname,'../../app/src/main/assets/phone-remote.html'),'utf8');
const token='a'.repeat(48);
const received=[];
before(async()=>{
 server=http.createServer((request,response)=>{
  if(request.method==='GET'&&request.url==='/'){
   response.writeHead(200,{'Content-Type':'text/html','Content-Security-Policy':"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",'Cache-Control':'no-store'});response.end(pageSource);return;
  }
  if(request.method==='POST'&&request.url==='/api'){
   let body='';request.on('data',part=>body+=part);request.on('end',()=>{assert.equal(request.headers['x-torbox-remote'],token);assert.equal(request.headers.origin,origin);assert.equal(request.headers.authorization,undefined);assert.equal(request.headers.cookie,undefined);received.push(JSON.parse(body));response.writeHead(200,{'Content-Type':'application/json'});response.end('{"ok":true}');});return;
  }
  response.writeHead(404);response.end();
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+server.address().port;
 browser=await chromium.launch({headless:true,args:['--no-sandbox'],...(process.env.CHROME_EXECUTABLE?{executablePath:process.env.CHROME_EXECUTABLE}:{})});
});
after(async()=>{await browser?.close();await new Promise(resolve=>server?.close(resolve));});
async function phone(t,width=390){const context=await browser.newContext({viewport:{width,height:844},isMobile:true,hasTouch:true});const page=await context.newPage();const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>requests.push(r.url()));t.after(async()=>{await context.close();assert.deepEqual(errors,[]);assert.ok(requests.every(url=>url.startsWith(origin+'/')),'Remote must not contact external services');});return page;}
async function waitFor(action){for(let i=0;i<100;i++){if(action())return;await new Promise(r=>setTimeout(r,25));}assert.fail('Phone request did not arrive');}
test('QR URL connects without sending its secret in the document request',async t=>{const page=await phone(t);received.length=0;await page.goto(origin+'/#'+token);await page.getByText('Connected to TorBox TV',{exact:true}).waitFor();assert.equal(received[0].action,'hello');assert.equal(await page.locator('body').evaluate(e=>e.scrollWidth<=innerWidth),true);});
test('typing uses a debounced latest search with increasing sequence',async t=>{const page=await phone(t);await page.goto(origin+'/#'+token);await page.getByText('Connected to TorBox TV',{exact:true}).waitFor();received.length=0;await page.locator('#query').fill('Ju');await page.locator('#query').fill('Jumanji');await waitFor(()=>received.some(x=>x.action==='search'));const queries=received.filter(x=>x.action==='search');assert.equal(queries.at(-1).query,'Jumanji');assert.equal(queries.at(-1).scope,'movies');await page.locator('#scope').selectOption('sports');await waitFor(()=>received.some(x=>x.scope==='sports'));assert.ok(received.filter(x=>x.action==='search').at(-1).sequence>queries.at(-1).sequence);});
test('directional and playback buttons send only the intended native commands',async t=>{const page=await phone(t);await page.goto(origin+'/#'+token);await page.getByText('Connected to TorBox TV',{exact:true}).waitFor();received.length=0;for(const name of ['Up','Down','Left','Right','OK','Back','Rewind','Play / pause','Forward'])await page.getByRole('button',{name,exact:true}).click();await waitFor(()=>received.filter(x=>x.action==='key').length===9);assert.deepEqual(received.map(x=>x.key),['UP','DOWN','LEFT','RIGHT','OK','BACK','REWIND','TOGGLE','FORWARD']);});
test('unpaired page disables controls and sends no commands',async t=>{const page=await phone(t);received.length=0;await page.goto(origin+'/');await page.getByText('Open Phone remote in TorBox TV Settings and scan the QR code.',{exact:true}).waitFor();assert.equal(await page.locator('button:enabled,input:enabled,select:enabled').count(),0);assert.equal(received.length,0);});
test('disconnect disables the phone and never retries commands',async t=>{const page=await phone(t);await page.goto(origin+'/#'+token);await page.getByText('Connected to TorBox TV',{exact:true}).waitFor();received.length=0;await page.getByRole('button',{name:'Disconnect phone'}).click();await page.getByText('Disconnected. Scan the TV QR code to reconnect.',{exact:true}).waitFor();assert.equal(received.at(-1).action,'disconnect');assert.equal(await page.locator('button:enabled,input:enabled,select:enabled').count(),0);});
test('small phone viewport keeps every control within the page width',async t=>{const page=await phone(t,320);await page.goto(origin+'/#'+token);await page.getByText('Connected to TorBox TV',{exact:true}).waitFor();const overflow=await page.locator('button,input,select').evaluateAll(elements=>elements.filter(e=>{const r=e.getBoundingClientRect();return r.left<0||r.right>innerWidth;}));assert.equal(overflow.length,0);});
