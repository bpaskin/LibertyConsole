#!/usr/bin/env node
// Run with node scripts/test-remote-monitoring.cjs. No external dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../console-bundle/src/main/webapp/js/remote-monitoring.js'), 'utf8');
const one = {id:'one', name:'same-name', host:'host-one', userDir:'/wlp/usr', state:'STARTED'};
const two = {...one, id:'two', host:'host-two'};
const applications = [{name:'app', contextRoots:['/app'], serverId:'one', state:'STARTED'}];
function deferred() { let resolve,reject; const promise=new Promise((a,b)=>{resolve=a;reject=b;}); return {promise,resolve,reject}; }
function same(actual, expected) { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected); }
function runtime() {
    const nodes = new Map(); const timers = new Map(); let timerId=0;
    const document = {addEventListener(){}, getElementById(id) {
        if(!nodes.has(id)) nodes.set(id,{textContent:'', checked:true, disabled:false, className:'', replaceChildren(){}, add(){}});
        return nodes.get(id);
    }};
    const context=vm.createContext({document,window:{addEventListener(){}},AbortSignal,AbortController,URLSearchParams,console,
        currentPage:'remote-monitoring',collectiveAvailable:true,collectiveData:{servers:[one,two],applications},
        setTimeout(fn,delay){timers.set(++timerId,{fn,delay});return timerId;},clearTimeout(id){timers.delete(id);}});
    vm.runInContext(source,context);
    const state=vm.runInContext('remoteMonitoring',context);
    context.renderRemoteMonitoringSample=(server,sample,values,restarted)=>{context.rendered={server,sample,values,restarted};};
    context.readRemoteMonitoringTarget=async id=>({server:id==='one'?one:two,applications:applications.filter(app=>app.serverId===id)});
    return {context,state,nodes,timers};
}
function sample(options={}) {
    return {serverId:'one',sampledAt:10000,startTime:1000,uptime:10000,heap:{used:'104857600',committed:'209715200',max:'1073741824'},
        cpuPercent:25,threads:{ThreadCount:'30'},gcTimeMs:100,gcCount:2,collectors:[],
        applications:[{name:'app',requestCount:100}],...options};
}
function platform(context,{cpu='0.125',http=[],servlet=[]}={}) {
    const values={
        'java.lang:type=Runtime':{StartTime:'1000',Uptime:'10000'},
        'java.lang:type=Memory':{HeapMemoryUsage:{used:'104857600',committed:'209715200',max:'1073741824'},NonHeapMemoryUsage:{used:'1000'}},
        'java.lang:type=Threading':{ThreadCount:'30',DaemonThreadCount:'12',PeakThreadCount:'40',TotalStartedThreadCount:'80'},
        'java.lang:type=OperatingSystem':{ProcessCpuLoad:cpu,AvailableProcessors:'8'},
        'java.lang:type=ClassLoading':{LoadedClassCount:'2000'},
        'java.lang:type=GarbageCollector,name=collector':{Name:'collector',CollectionCount:'2',CollectionTime:'100'}
    };
    context.collectiveRequest=async (server,url,options)=>{
        assert.equal(server.id,'one');assert.equal(options.timeoutMs,8000);
        if(url.startsWith('/mbeans?')) {
            const pattern=new URLSearchParams(url.split('?')[1]).get('objectName');
            const beans=pattern.startsWith('java.lang:')?[{objectName:'java.lang:type=GarbageCollector,name=collector'}]:
                pattern.includes('HttpServerStats')?http.map((_,i)=>({objectName:'http'+i})):servlet.map((_,i)=>({objectName:'servlet'+i}));
            return {json:async()=>beans};
        }
        const match=url.match(/^\/mbeans\/([^/]+)\/attributes(?:\/([^/]+))?$/);assert(match,url);
        const bean=decodeURIComponent(match[1]);
        if(match[2]) return {json:async()=>({value:values[bean]?.[decodeURIComponent(match[2])]})};
        const stat=bean.startsWith('http')?http[Number(bean.slice(4))]:servlet[Number(bean.slice(7))];
        return {json:async()=>Object.entries(stat).map(([name,value])=>({name,value:{value}}))};
    };
}

test('numeric decoding accepts Liberty strings without turning unavailable values into zero',()=>{
    const {context:r}=runtime();
    for(const value of [null,undefined,'','  ','NaN',Infinity,-1,'-1',false,{},[]]) assert.equal(r.remoteNumber(value),null);
    assert.equal(r.remoteNumber('0'),0);assert.equal(r.remoteNumber('12.5'),12.5);
});

test('first sample keeps CPU and GC unavailable when the JVM cannot report them yet',async()=>{
    const {context:r}=runtime();platform(r,{cpu:'-1'});
    const s=await r.collectRemoteMonitoringSample(one,applications);
    assert.equal(s.cpuPercent,null);assert.equal(r.remoteMonitoringValues(s,null).gc,null);
    assert.equal(s.threads.ThreadCount,'30');assert.equal(s.gcTimeMs,100);
});

test('JVM metrics work when monitor-1.0 publishes no application counters',async()=>{
    const {context:r}=runtime();platform(r);
    const s=await r.collectRemoteMonitoringSample(one,applications);
    assert.equal(s.cpuPercent,12.5);assert.equal(s.applications[0].requestCount,null);
    assert.match(s.applicationNote,/monitor-1.0/);
    assert.equal(r.remoteMonitoringValues(s,null).memory,100);
});

test('HTTP metrics aggregate total duration in nanoseconds and count errors',()=>{
    const {context:r}=runtime();
    const rows=r.remoteAggregateApplications(applications,[
        {HttpRoute:'/app/a',Count:'8',Duration:'8000000',ResponseStatus:'200',ErrorType:''},
        {HttpRoute:'/app/b',Count:'2',Duration:'4000000',ResponseStatus:'500',ErrorType:''}
    ],'http');
    assert.equal(rows[0].requestCount,10);assert.equal(rows[0].averageResponseMs,1.2);assert.equal(rows[0].errorCount,2);
});

test('request routes match the longest application context, including a root application',()=>{
    const {context:r}=runtime();const apps=[{name:'root',contextRoots:['/']},{name:'app',contextRoots:['/app']},{name:'admin',contextRoots:['/app/admin']}];
    assert.equal(r.remoteApplicationForRoute('/app/admin/users',apps),'admin');
    assert.equal(r.remoteApplicationForRoute('/application',apps),'root');
    assert.equal(r.remoteApplicationForRoute('/app',apps),'app');
    assert.equal(r.remoteApplicationForRoute('/another',applications),null);
});

test('servlet fallback preserves unavailable error counts and computes mean response time',async()=>{
    const {context:r}=runtime();platform(r,{servlet:[{AppName:'app',RequestCount:'5',ResponseTime:'2000000'}]});
    const s=await r.collectRemoteMonitoringSample(one,applications);
    assert.equal(s.applications[0].requestCount,5);assert.equal(s.applications[0].averageResponseMs,2);
    assert.equal(s.applications[0].errorCount,null);assert.match(s.applicationNote,/Servlet/);
});

test('rates use server uptime and cumulative counter differences',()=>{
    const {context:r}=runtime();const old=sample();const current=sample({uptime:20000,gcTimeMs:130,applications:[{name:'app',requestCount:150}]});
    const values=r.remoteMonitoringValues(current,old);assert.equal(values.gc,3);assert.equal(values.applications[0].requestRate,5);
});

test('restart, counter reset and different server identities invalidate rate baselines',()=>{
    const {context:r}=runtime();
    for(const changed of [{startTime:2000},{serverId:'two'},{uptime:100}]) {
        const values=r.remoteMonitoringValues(sample(changed),sample());assert.equal(values.gc,null);assert.equal(values.applications[0].requestRate,null);
    }
    const values=r.remoteMonitoringValues(sample({uptime:20000,gcTimeMs:10,applications:[{name:'app',requestCount:2}]}),sample());
    assert.equal(values.gc,null);assert.equal(values.applications[0].requestRate,null);
});

test('permission failures are reported without querying local /metrics',async()=>{
    const {context:r}=runtime();const urls=[];
    r.collectiveRequest=async(server,url)=>{urls.push(url);const e=new Error('Forbidden');e.status=403;throw e;};
    await assert.rejects(r.collectRemoteMonitoringSample(one,applications),e=>e.status===403);
    assert(urls.every(url=>url.startsWith('/mbeans')));
});

test('missing optional CPU and GC values do not hide valid heap/thread samples',async()=>{
    const {context:r}=runtime();platform(r);const original=r.collectiveRequest;
    r.collectiveRequest=async(server,url,options)=>{
        if(decodeURIComponent(url).includes('OperatingSystem')||decodeURIComponent(url).includes('GarbageCollector')) throw new Error('Unsupported');
        return original(server,url,options);
    };
    const s=await r.collectRemoteMonitoringSample(one,applications);assert.equal(s.cpuPercent,null);assert.equal(s.gcTimeMs,null);assert.equal(s.threads.ThreadCount,'30');
});

test('HTTP requests to many MBeans are limited to four workers and keep ordering',async()=>{
    const {context:r}=runtime();let active=0,max=0;
    const values=await r.remoteMap(Array.from({length:12},(_,i)=>i),async value=>{
        max=Math.max(max,++active);await new Promise(setImmediate);active--;return value*2;
    });
    assert.equal(max,4);same(values,Array.from({length:12},(_,i)=>i*2));
});

test('a failed worker waits for pending reads before the sample can complete',async()=>{
    const {context:r}=runtime();const gate=deferred();let ended=false;
    const pending=r.remoteMap([0,1,2,3],async i=>{if(i===0)throw new Error('Unavailable');await gate.promise;return i;}).catch(e=>{ended=true;throw e;});
    await new Promise(setImmediate);assert.equal(ended,false);gate.resolve();await assert.rejects(pending,/Unavailable/);
});

test('refreshes coalesce and auto refresh is scheduled only after the sample completes',async()=>{
    const {context:r,state,timers}=runtime();state.active=true;state.serverId='one';const gate=deferred();let calls=0;
    r.collectRemoteMonitoringSample=async()=>{calls++;return gate.promise;};
    const first=r.refreshRemoteMonitoring(),second=r.refreshRemoteMonitoring();await new Promise(setImmediate);assert.equal(first,second);assert.equal(calls,1);assert.equal(timers.size,0);
    gate.resolve(sample());await first;assert.equal(timers.size,1);assert.equal([...timers.values()][0].delay,10000);assert.equal(state.inFlight,null);
});

test('leaving monitoring aborts requests and ignores their late results',async()=>{
    const {context:r,state,timers}=runtime();state.active=true;state.serverId='one';const gate=deferred();let signal;
    r.collectRemoteMonitoringSample=async(server,apps,options)=>{signal=options.signal;return gate.promise;};
    const pending=r.refreshRemoteMonitoring();await new Promise(setImmediate);r.stopRemoteMonitoring();r.currentPage='server';assert.equal(signal.aborted,true);
    gate.resolve(sample());await pending;assert.equal(r.rendered,undefined);assert.equal(state.history.length,0);assert.equal(timers.size,0);
});

test('a late response from a previous server cannot overwrite the selected server or its pending read',async()=>{
    const {context:r,state}=runtime();const old=deferred(),fresh=deferred();state.active=true;state.serverId='one';
    r.collectRemoteMonitoringSample=async server=>server.id==='one'?old.promise:fresh.promise;
    const previous=r.refreshRemoteMonitoring();await new Promise(setImmediate);r.stopRemoteMonitoring();state.active=true;state.serverId='two';const current=r.refreshRemoteMonitoring();
    old.resolve(sample());await previous;assert.equal(state.inFlight,current);assert.equal(r.rendered,undefined);
    fresh.resolve(sample({serverId:'two'}));await current;assert.equal(r.rendered.server.id,'two');assert.equal(state.history.length,1);
});

test('a server restart clears chart history as well as rate baselines',async()=>{
    const {context:r,state}=runtime();state.active=true;state.serverId='one';state.previous=sample();state.history=[{time:100,memory:100}];
    r.collectRemoteMonitoringSample=async()=>sample({startTime:2000,uptime:100});await r.refreshRemoteMonitoring();
    assert.equal(state.history.length,1);assert.equal(r.rendered.restarted,true);assert.equal(r.rendered.values.gc,null);
});

test('offline samples show unavailable values and a graph gap, with no zero data point',async()=>{
    const {context:r,state,nodes}=runtime();state.active=true;state.serverId='one';state.history=[{time:100,memory:100,cpu:20,threads:30,gc:0}];
    r.collectRemoteMonitoringSample=async()=>{throw new Error('Member unavailable');};await r.refreshRemoteMonitoring();
    assert.match(nodes.get('remote-monitoring-status').textContent,/unavailable/);assert.equal(nodes.get('remote-heap-value').textContent,'—');
    assert.equal(state.history[1].memory,null);assert.equal(state.history[1].cpu,null);
});

test('disabling auto refresh during a pending sample leaves no background timer',async()=>{
    const {context:r,state,timers}=runtime();state.active=true;state.serverId='one';const gate=deferred();r.collectRemoteMonitoringSample=async()=>gate.promise;
    const pending=r.refreshRemoteMonitoring();r.document.getElementById('remote-monitoring-auto').checked=false;r.toggleRemoteMonitoringRefresh();
    gate.resolve(sample());await pending;assert.equal(timers.size,0);
});

test('unreadable HTTP route ObjectNames fall back to servlet counters',async()=>{
    const {context:r}=runtime();platform(r,{http:[{HttpRoute:'/app',Count:'20',Duration:'20000000',ResponseStatus:'200',ErrorType:''}],servlet:[{AppName:'app',RequestCount:'12',ResponseTime:'3000000'}]});
    const original=r.collectiveRequest;r.collectiveRequest=async(server,url,options)=>{
        if(url==='/mbeans/http0/attributes') {const error=new Error("Couldn't find object name in request URI");error.status=500;throw error;}
        return original(server,url,options);
    };
    const s=await r.collectRemoteMonitoringSample(one,applications);
    assert.equal(s.applications[0].requestCount,12);assert.equal(s.applications[0].averageResponseMs,3);assert.equal(s.applications[0].errorCount,null);
    assert.match(s.applicationNote,/HTTP route metrics cannot be read/);
});

test('fresh monitoring metadata replaces an outdated application inventory on every sample',async()=>{
    const {context:r,state}=runtime();state.active=true;state.serverId='one';
    r.readRemoteMonitoringTarget=async()=>({server:one,applications:[{name:'newly-deployed',serverId:'one'}]});
    r.collectRemoteMonitoringSample=async(server,apps)=>{assert.equal(apps[0].name,'newly-deployed');return sample();};
    await r.refreshRemoteMonitoring();assert.equal(r.rendered.server.id,'one');
});

test('a known HTTP ObjectName routing failure is cached to avoid repeated connector errors',async()=>{
    const {context:r}=runtime();platform(r,{http:[{HttpRoute:'/app',Count:'20'}],servlet:[{AppName:'app',RequestCount:'12',ResponseTime:'1000000'}]});
    const original=r.collectiveRequest;let errors=0;
    r.collectiveRequest=async(server,url,options)=>{
        if(url==='/mbeans/http0/attributes') {errors++;throw new Error("Couldn't find object name in request URI");}
        return original(server,url,options);
    };
    await r.collectRemoteMonitoringSample(one,applications);await r.collectRemoteMonitoringSample(one,applications);assert.equal(errors,1);
});

test('changing target clears the old metrics before waiting for refreshed inventory',async()=>{
    const {context:r,state,nodes}=runtime();const gate=deferred();state.serverId='two';state.previous=sample();state.history=[{time:1,memory:100}];
    r.document.getElementById('remote-heap-value').textContent='100 MB';r.loadCollective=async()=>gate.promise;
    const pending=r.startRemoteMonitoring();assert.equal(nodes.get('remote-heap-value').textContent,'—');assert.equal(state.previous,null);assert.equal(state.history.length,0);
    r.currentPage='server';gate.resolve();await pending;
});

test('switching between HTTP and servlet counters resets the application rate baseline',()=>{
    const {context:r}=runtime();
    const previous=sample({applications:[{name:'app',source:'http',requestCount:10}]});
    const current=sample({uptime:20000,applications:[{name:'app',source:'servlet',requestCount:100}]});
    assert.equal(r.remoteMonitoringValues(current,previous).applications[0].requestRate,null);
});

test('an application-metrics timeout preserves valid JVM charts and marks its counters unavailable',async()=>{
    const {context:r}=runtime();platform(r);
    r.remoteApplicationMetrics=async()=>{const error=new Error('Application metrics timed out');error.name='TimeoutError';throw error;};
    const s=await r.collectRemoteMonitoringSample(one,applications);
    assert(s.heap.used>0);assert.equal(s.applications[0].requestCount,null);assert.match(s.applicationNote,/unavailable.*timed out/);
});
