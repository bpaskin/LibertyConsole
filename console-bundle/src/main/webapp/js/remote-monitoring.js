// Remote metrics always travel through the controller's authenticated REST JMX
// connector, with a complete server identity on every request. No local fallback.
const REMOTE_MONITORING_INTERVAL = 10000;
const REMOTE_MONITORING_POINTS = 20;
const remoteHttpRoutingFailures = new Map();
const remoteMonitoring = {
    serverId: '', active: false, generation: 0, timer: null, controller: null,
    inFlight: null, previous: null, history: [], charts: {}, lastSuccess: null
};

function remoteNumber(value) {
    if (typeof value !== 'number' && (typeof value !== 'string' || !value.trim())) return null;
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? number : null;
}

async function remoteAttribute(server, bean, attribute, options) {
    const response = await collectiveRequest(server, `/mbeans/${encodeURIComponent(bean)}/attributes/${encodeURIComponent(attribute)}`, options);
    return (await response.json()).value;
}

async function remoteAttributes(server, bean, attributes, options) {
    const results = await Promise.allSettled(attributes.map(attribute => remoteAttribute(server, bean, attribute, options)));
    const values = {}; const errors = [];
    results.forEach((result, index) => {
        if (result.status === 'fulfilled') values[attributes[index]] = result.value;
        else errors.push(result.reason);
    });
    return {values, errors};
}

async function remoteQuery(server, pattern, options) {
    const response = await collectiveRequest(server, `/mbeans?${new URLSearchParams({objectName: pattern})}`, options);
    const beans = await response.json();
    if (!Array.isArray(beans) || beans.some(bean => typeof bean.objectName !== 'string')) throw new Error('Liberty returned an invalid monitoring MBean list.');
    return beans;
}

// Bound concurrent requests when a server publishes many HTTP routes or servlets.
async function remoteMap(items, callback, signal) {
    const results = new Array(items.length); let next = 0;
    const workers = await Promise.allSettled(Array.from({length: Math.min(4, items.length)}, async () => {
        while (next < items.length) {
            if (signal?.aborted) throw signal.reason;
            const index = next++;
            results[index] = await callback(items[index]);
        }
    }));
    const failure = workers.find(worker => worker.status === 'rejected');
    if (failure) throw failure.reason;
    return results;
}

async function remoteGarbageCollectors(server, options) {
    const beans = await remoteQuery(server, 'java.lang:type=GarbageCollector,*', options);
    return remoteMap(beans, async bean => {
        const {values} = await remoteAttributes(server, bean.objectName, ['Name', 'CollectionCount', 'CollectionTime'], options);
        return {name: values.Name || bean.objectName, count: remoteNumber(values.CollectionCount), timeMs: remoteNumber(values.CollectionTime)};
    }, options.signal);
}

function remoteApplicationForRoute(route, applications) {
    if (typeof route !== 'string') return null;
    let matched = null; let length = -1;
    for (const app of applications) {
        for (const raw of app.contextRoots || []) {
            const root = raw === '/' ? '/' : raw.replace(/\/$/, '');
            if (root && (root === '/' || route === root || route.startsWith(root + '/')) && root.length > length) {
                matched = app.name; length = root.length;
            }
        }
    }
    return matched;
}

function remoteAggregateApplications(applications, stats, source) {
    const rows = new Map(applications.map(app => [app.name, {
        name: app.name, state: app.state, source, requestCount: null, errorCount: null,
        durationNanos: 0, timingKnown: true, errorsKnown: source === 'http'
    }]));
    for (const stat of stats) {
        const name = source === 'http' ? remoteApplicationForRoute(stat.HttpRoute, applications) : stat.AppName;
        const row = rows.get(name);
        const count = remoteNumber(source === 'http' ? stat.Count : stat.RequestCount);
        if (!row || count === null) continue;
        row.requestCount = (row.requestCount ?? 0) + count;
        const duration = remoteNumber(source === 'http' ? stat.Duration : stat.ResponseTime);
        if (duration === null) row.timingKnown = false;
        else row.durationNanos += source === 'http' ? duration : duration * count;
        if (source === 'http') {
            const status = remoteNumber(stat.ResponseStatus);
            if (status === null && typeof stat.ErrorType !== 'string') row.errorsKnown = false;
            const error = (status !== null && status >= 500) || (typeof stat.ErrorType === 'string' && stat.ErrorType.trim() !== '');
            row.errorCount = (row.errorCount ?? 0) + (error ? count : 0);
        }
    }
    return [...rows.values()].map(row => ({...row,
        averageResponseMs: row.requestCount > 0 && row.timingKnown ? row.durationNanos / row.requestCount / 1e6 : null,
        errorCount: row.requestCount !== null && row.errorsKnown ? row.errorCount : null
    }));
}

async function remoteApplicationMetrics(server, applications, options) {
    if (!applications.length) return {rows: [], note: 'No application deployments are reported for this server.'};
    let source = 'http';
    const cached = remoteHttpRoutingFailures.get(server.id);
    let httpFailure = cached && Date.now() - cached.time < 300000 ? cached.error : null;
    let beans = httpFailure ? [] : await remoteQuery(server, 'WebSphere:type=HttpServerStats,*', options);
    const read = async names => remoteMap(names, async bean => {
        const response = await collectiveRequest(server, `/mbeans/${encodeURIComponent(bean.objectName)}/attributes`, options);
        const attributes = await response.json();
        if (!Array.isArray(attributes)) throw new Error('Liberty returned invalid application metrics.');
        return Object.fromEntries(attributes.map(attribute => [attribute.name, attribute.value?.value]));
    }, options.signal);
    let stats;
    if (beans.length) {
        try { stats = await read(beans); }
        catch (error) {
            if (options.signal?.aborted || error.status === 401 || error.status === 403) throw error;
            // Some Liberty levels treat semicolons in HTTP metric ObjectNames as
            // URI path parameters. Servlet MBeans remain usable through REST.
            httpFailure = error;
            if (error.message.includes("Couldn't find object name in request URI")) {
                remoteHttpRoutingFailures.set(server.id, {time: Date.now(), error});
            }
        }
    }
    if (!beans.length || httpFailure) {
        source = 'servlet';
        beans = await remoteQuery(server, 'WebSphere:type=ServletStats,*', options);
        stats = beans.length ? await read(beans) : [];
    }
    if (!beans.length) return {
        rows: remoteAggregateApplications(applications, [], source),
        note: httpFailure ? `HTTP route metrics cannot be read through this Liberty REST connector (${httpFailure.message}); no servlet request metrics are published.` :
            'No request metrics are published. Enable monitor-1.0 on this member and send application requests.'
    };
    return {rows: remoteAggregateApplications(applications, stats, source),
        note: source === 'servlet' ? `${httpFailure ? 'HTTP route metrics cannot be read through this Liberty REST connector. ' : ''}Servlet monitoring is available; error counts are not exposed by these MBeans.` :
            'HTTP request totals and average response times. Request rates need two samples; errors include HTTP 5xx and reported request errors.'};
}

async function collectRemoteMonitoringSample(server, applications, options = {}) {
    const request = {...options, timeoutMs: 8000};
    const applicationTimeout = AbortSignal.timeout(12000);
    const applicationRequest = {...request, signal: options.signal ? AbortSignal.any([options.signal, applicationTimeout]) : applicationTimeout};
    const results = await Promise.allSettled([
        remoteAttributes(server, 'java.lang:type=Runtime', ['StartTime', 'Uptime'], request),
        remoteAttributes(server, 'java.lang:type=Memory', ['HeapMemoryUsage', 'NonHeapMemoryUsage'], request),
        remoteAttributes(server, 'java.lang:type=Threading', ['ThreadCount', 'DaemonThreadCount', 'PeakThreadCount', 'TotalStartedThreadCount'], request),
        remoteAttributes(server, 'java.lang:type=OperatingSystem', ['ProcessCpuLoad', 'AvailableProcessors'], request),
        remoteAttributes(server, 'java.lang:type=ClassLoading', ['LoadedClassCount'], request),
        remoteGarbageCollectors(server, request),
        remoteApplicationMetrics(server, applications, applicationRequest)
    ]);
    if (options.signal?.aborted) throw options.signal.reason;
    const group = index => results[index].status === 'fulfilled' ? results[index].value : {values: {}, errors: [results[index].reason]};
    const runtime = group(0), memory = group(1), threads = group(2), os = group(3), classes = group(4);
    if (remoteNumber(runtime.values.StartTime) === null || remoteNumber(runtime.values.Uptime) === null ||
        remoteNumber(memory.values.HeapMemoryUsage?.used) === null || remoteNumber(threads.values.ThreadCount) === null) {
        throw [...runtime.errors, ...memory.errors, ...threads.errors][0] || new Error('The selected server did not publish the required JVM metrics.');
    }
    const collectors = results[5].status === 'fulfilled' ? results[5].value : [];
    const total = property => collectors.length && collectors.every(gc => gc[property] !== null) ? collectors.reduce((sum, gc) => sum + gc[property], 0) : null;
    const load = remoteNumber(os.values.ProcessCpuLoad);
    const appMetrics = results[6].status === 'fulfilled' ? results[6].value : {
        rows: remoteAggregateApplications(applications, [], 'http'), note: `Application metrics unavailable: ${results[6].reason.message}`
    };
    return {
        serverId: server.id, sampledAt: Date.now(), startTime: Number(runtime.values.StartTime), uptime: Number(runtime.values.Uptime),
        heap: memory.values.HeapMemoryUsage, nonHeap: memory.values.NonHeapMemoryUsage,
        cpuPercent: load !== null && load <= 1 ? load * 100 : null,
        processors: remoteNumber(os.values.AvailableProcessors), threads: threads.values,
        loadedClasses: remoteNumber(classes.values.LoadedClassCount), collectors,
        gcCount: total('count'), gcTimeMs: total('timeMs'), applications: appMetrics.rows, applicationNote: appMetrics.note
    };
}

function remoteSamplesContinuous(previous, sample) {
    return previous && previous.serverId === sample.serverId && previous.startTime === sample.startTime && sample.uptime >= previous.uptime;
}

function remoteMonitoringValues(sample, previous) {
    const continuous = remoteSamplesContinuous(previous, sample);
    const elapsed = continuous ? (sample.uptime - previous.uptime) / 1000 : 0;
    const previousApps = new Map((continuous ? previous.applications : []).map(app => [app.name, app]));
    return {
        memory: Number(sample.heap.used) / (1024 * 1024), cpu: sample.cpuPercent,
        threads: remoteNumber(sample.threads.ThreadCount),
        gc: elapsed > 0 && sample.gcTimeMs !== null && previous.gcTimeMs !== null && sample.gcTimeMs >= previous.gcTimeMs ?
            (sample.gcTimeMs - previous.gcTimeMs) / elapsed : null,
        applications: sample.applications.map(app => {
            const old = previousApps.get(app.name);
            const rate = elapsed > 0 && app.source === old?.source && app.requestCount !== null && old?.requestCount != null && app.requestCount >= old.requestCount ?
                (app.requestCount - old.requestCount) / elapsed : null;
            return {...app, requestRate: rate};
        })
    };
}

function remoteMonitoringStatus(message, failed = false) {
    const status = document.getElementById('remote-monitoring-status');
    status.textContent = message; status.className = `alert ${failed ? 'alert-warning' : 'alert-info'}`;
}

function resetRemoteMonitoringData() {
    remoteMonitoring.previous = null; remoteMonitoring.history = []; remoteMonitoring.lastSuccess = null;
    ['heap', 'cpu', 'threads', 'uptime'].forEach(name => { document.getElementById(`remote-${name}-value`).textContent = '—'; });
    document.getElementById('remote-monitoring-updated').textContent = '';
    document.getElementById('remote-application-status').textContent = '';
    ['remote-application-metrics', 'remote-gc-details', 'remote-jvm-details'].forEach(id => document.getElementById(id).replaceChildren());
    updateRemoteMonitoringCharts();
}

function initializeRemoteMonitoringCharts() {
    const status = document.getElementById('remote-chart-status');
    if (typeof Chart !== 'function') { status.hidden = false; status.textContent = 'Charts are unavailable. Live values and metric tables remain available below.'; return; }
    status.hidden = true;
    if (Object.keys(remoteMonitoring.charts).length) return;
    const definitions = {memory: ['Heap used (MB)', '#0f62fe'], cpu: ['Process CPU (%)', '#24a148'],
        threads: ['Live threads', '#8a3ffc'], gc: ['GC time (ms/s)', '#f1c21b']};
    for (const [name, [label, color]] of Object.entries(definitions)) {
        remoteMonitoring.charts[name] = new Chart(document.getElementById(`remote-${name}-chart`), {
            type: 'line', data: {labels: [], datasets: [{label, data: [], borderColor: color, backgroundColor: color + '1a', fill: true, tension: 0.4, spanGaps: false}]},
            options: {responsive: true, maintainAspectRatio: true, animation: {duration: 300}, plugins: {legend: {display: false}},
                scales: {y: {beginAtZero: true, ...(name === 'cpu' ? {max: 100} : {})}}}
        });
    }
}

function updateRemoteMonitoringCharts() {
    for (const [name, chart] of Object.entries(remoteMonitoring.charts)) {
        chart.data.labels = remoteMonitoring.history.map(point => new Date(point.time).toLocaleTimeString());
        chart.data.datasets[0].data = remoteMonitoring.history.map(point => point[name]);
        if (typeof getComputedStyle === 'function') {
            const colors = getComputedStyle(document.body);
            for (const axis of ['x', 'y']) {
                chart.options.scales[axis] ||= {};
                chart.options.scales[axis].ticks = {color: colors.getPropertyValue('--text-secondary').trim()};
                chart.options.scales[axis].grid = {color: colors.getPropertyValue('--border-color').trim()};
            }
        }
        chart.update('none');
    }
}

function remoteFormat(value, suffix = '', digits = 0) {
    const number = remoteNumber(value);
    return number === null ? '—' : number.toLocaleString(undefined, {maximumFractionDigits: digits}) + suffix;
}

function renderRemoteMonitoringSample(server, sample, values, restarted) {
    document.getElementById('remote-monitoring-target').textContent = `${server.host} · ${server.userDir}${server.cluster ? ` · ${server.cluster}` : ''}`;
    document.getElementById('remote-heap-value').textContent = formatBytes(Number(sample.heap.used));
    document.getElementById('remote-cpu-value').textContent = remoteFormat(values.cpu, '%', 1);
    document.getElementById('remote-threads-value').textContent = remoteFormat(values.threads);
    document.getElementById('remote-uptime-value').textContent = formatUptime(sample.uptime);
    document.getElementById('remote-monitoring-updated').textContent = `Last successful sample: ${new Date(sample.sampledAt).toLocaleTimeString()}`;
    const notes = [];
    if (restarted) notes.push('The server restarted; chart history and rate baselines were reset.');
    if (values.cpu === null) notes.push('CPU usage is unavailable or awaiting another sample.');
    if (sample.gcTimeMs === null) notes.push('Garbage collection time is unavailable.');
    remoteMonitoringStatus(`Live metrics from ${server.name} (${server.host}). ${notes.join(' ')}`.trim());
    document.getElementById('remote-application-status').textContent = sample.applicationNote;
    collectiveTable('remote-application-metrics', ['Application', 'State', 'Requests', 'Avg Response', 'Errors', 'Request Rate'], values.applications.map(app => [
        app.name, collectiveBadge(app.state), remoteFormat(app.requestCount), remoteFormat(app.averageResponseMs, ' ms', 2),
        remoteFormat(app.errorCount), remoteFormat(app.requestRate, ' req/s', 3)
    ]));
    collectiveTable('remote-gc-details', ['Collector', 'Collections', 'Total Collection Time'], sample.collectors.map(gc => [gc.name, remoteFormat(gc.count), remoteFormat(gc.timeMs, ' ms')]));
    const details = document.getElementById('remote-jvm-details'); details.replaceChildren();
    const bytes = value => remoteNumber(value) === null ? '—' : formatBytes(Number(value));
    const fields = [['Heap committed', bytes(sample.heap.committed)], ['Heap maximum', bytes(sample.heap.max)],
        ['Non-heap used', bytes(sample.nonHeap?.used)], ['Non-heap committed', bytes(sample.nonHeap?.committed)],
        ['Daemon threads', remoteFormat(sample.threads.DaemonThreadCount)], ['Peak threads', remoteFormat(sample.threads.PeakThreadCount)],
        ['Threads started', remoteFormat(sample.threads.TotalStartedThreadCount)], ['Loaded classes', remoteFormat(sample.loadedClasses)],
        ['Available processors', remoteFormat(sample.processors)], ['GC collections', remoteFormat(sample.gcCount)],
        ['Total GC time', remoteFormat(sample.gcTimeMs, ' ms')], ['GC time rate', remoteFormat(values.gc, ' ms/s', 2)]];
    for (const [label, value] of fields) {
        const item = collectiveElement('div', undefined, 'info-item');
        item.append(collectiveElement('span', label, 'info-label'), collectiveElement('span', value, 'info-value')); details.append(item);
    }
}

function stopRemoteMonitoring() {
    remoteMonitoring.active = false; remoteMonitoring.generation++;
    clearTimeout(remoteMonitoring.timer); remoteMonitoring.timer = null;
    remoteMonitoring.controller?.abort(); remoteMonitoring.controller = null; remoteMonitoring.inFlight = null;
}

function scheduleRemoteMonitoring() {
    clearTimeout(remoteMonitoring.timer); remoteMonitoring.timer = null;
    if (remoteMonitoring.active && currentPage === 'remote-monitoring' && document.getElementById('remote-monitoring-auto').checked) {
        remoteMonitoring.timer = setTimeout(() => { remoteMonitoring.timer = null; refreshRemoteMonitoring(); }, REMOTE_MONITORING_INTERVAL);
    }
}

async function readRemoteMonitoringTarget(id, signal) {
    const response = await fetch(`${API_BASE}/collective/servers/${encodeURIComponent(id)}`, {
        credentials: 'same-origin', redirect: 'error', signal
    });
    const metadata = await response.json();
    if (!response.ok) {
        const error = new Error(metadata.error || `HTTP ${response.status}`); error.status = response.status; throw error;
    }
    if (metadata.server?.id !== id || !Array.isArray(metadata.applications)) throw new Error('The controller returned an invalid monitoring target.');
    return metadata;
}

function refreshRemoteMonitoring() {
    if (!remoteMonitoring.active || currentPage !== 'remote-monitoring') return Promise.resolve();
    if (remoteMonitoring.inFlight) return remoteMonitoring.inFlight;
    clearTimeout(remoteMonitoring.timer); remoteMonitoring.timer = null;
    const selected = collectiveData?.servers.find(server => server.id === remoteMonitoring.serverId);
    if (!selected) { remoteMonitoringStatus('The selected server is no longer in the collective. Reopen Remote Monitoring to select a server.', true); return Promise.resolve(); }
    let server = {...selected};
    const generation = remoteMonitoring.generation;
    const controller = new AbortController(); remoteMonitoring.controller = controller;
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(20000)]);
    const button = document.getElementById('remote-monitoring-refresh'); button.disabled = true;
    remoteMonitoringStatus(`Loading metrics from ${server.name} (${server.host})…`);
    const pending = (async () => {
        try {
            const metadata = await readRemoteMonitoringTarget(server.id, signal);
            if (generation !== remoteMonitoring.generation || !remoteMonitoring.active || currentPage !== 'remote-monitoring') return;
            server = metadata.server;
            const sample = await collectRemoteMonitoringSample(server, metadata.applications, {signal});
            if (generation !== remoteMonitoring.generation || !remoteMonitoring.active || currentPage !== 'remote-monitoring') return;
            const previous = remoteMonitoring.previous;
            const restarted = previous && !remoteSamplesContinuous(previous, sample);
            if (restarted) { remoteMonitoring.history = []; remoteHttpRoutingFailures.delete(server.id); }
            const values = remoteMonitoringValues(sample, previous);
            remoteMonitoring.history.push({time: sample.sampledAt, memory: values.memory, cpu: values.cpu, threads: values.threads, gc: values.gc});
            remoteMonitoring.history = remoteMonitoring.history.slice(-REMOTE_MONITORING_POINTS);
            remoteMonitoring.previous = sample; remoteMonitoring.lastSuccess = sample.sampledAt;
            renderRemoteMonitoringSample(server, sample, values, restarted);
            updateRemoteMonitoringCharts();
        } catch (error) {
            if (generation !== remoteMonitoring.generation || controller.signal.aborted) return;
            const message = error.status === 401 || error.status === 403 ?
                'The REST connector refused remote monitoring. Sign in with its required management permissions.' :
                error.name === 'TimeoutError' ? 'The selected server did not answer before the monitoring timeout.' : error.message;
            remoteMonitoringStatus(`Remote metrics unavailable for ${server.name} (${server.host}): ${message}`, true);
            ['heap', 'cpu', 'threads', 'uptime'].forEach(name => { document.getElementById(`remote-${name}-value`).textContent = '—'; });
            ['remote-application-metrics', 'remote-gc-details', 'remote-jvm-details'].forEach(id => document.getElementById(id).replaceChildren());
            document.getElementById('remote-application-status').textContent = 'Application metrics are unavailable for this sample.';
            if (remoteMonitoring.history.length && remoteMonitoring.history.at(-1).memory !== null) {
                remoteMonitoring.history.push({time: Date.now(), memory: null, cpu: null, threads: null, gc: null});
                remoteMonitoring.history = remoteMonitoring.history.slice(-REMOTE_MONITORING_POINTS); updateRemoteMonitoringCharts();
            }
        } finally {
            if (generation === remoteMonitoring.generation) {
                remoteMonitoring.inFlight = null; remoteMonitoring.controller = null; button.disabled = false; scheduleRemoteMonitoring();
            }
            controller.abort();
        }
    })();
    remoteMonitoring.inFlight = pending;
    return pending;
}

async function startRemoteMonitoring() {
    if (!collectiveAvailable) return;
    if (currentPage !== 'remote-monitoring') return;
    if (!remoteMonitoring.active) {
        // Clear the old target before awaiting inventory or any network request.
        resetRemoteMonitoringData();
        document.getElementById('remote-monitoring-target').textContent = '';
        document.getElementById('remote-monitoring-refresh').disabled = true;
        remoteMonitoringStatus('Loading the selected server…');
        await loadCollective();
    }
    if (currentPage !== 'remote-monitoring' || !collectiveData) return;
    if (remoteMonitoring.active) return refreshRemoteMonitoring();
    const select = document.getElementById('remote-monitoring-server');
    select.replaceChildren();
    collectiveData.servers.forEach(server => select.add(new Option(`${server.name} (${server.host}, ${server.userDir})`, server.id)));
    if (!collectiveData.servers.some(server => server.id === remoteMonitoring.serverId)) {
        remoteMonitoring.serverId = (collectiveData.servers.find(server => !server.controller && server.state === 'STARTED') || collectiveData.servers[0])?.id || '';
    }
    select.value = remoteMonitoring.serverId;
    remoteHttpRoutingFailures.delete(remoteMonitoring.serverId);
    initializeRemoteMonitoringCharts();
    const server = collectiveData.servers.find(server => server.id === remoteMonitoring.serverId);
    document.getElementById('remote-monitoring-target').textContent = server ? `${server.host} · ${server.userDir}${server.cluster ? ` · ${server.cluster}` : ''}` : '';
    document.getElementById('remote-monitoring-refresh').disabled = !server;
    if (!server) { remoteMonitoringStatus('No servers are registered with this collective.'); return; }
    remoteMonitoring.active = true;
    return refreshRemoteMonitoring();
}

function selectRemoteMonitoringServer() {
    const id = document.getElementById('remote-monitoring-server').value;
    stopRemoteMonitoring(); remoteMonitoring.serverId = id;
    return startRemoteMonitoring();
}

function showRemoteMonitoring(id) {
    if (remoteMonitoring.serverId !== id) stopRemoteMonitoring();
    remoteMonitoring.serverId = id;
    navigateToPage('remote-monitoring');
}

function toggleRemoteMonitoringRefresh() {
    clearTimeout(remoteMonitoring.timer); remoteMonitoring.timer = null;
    if (document.getElementById('remote-monitoring-auto').checked) return refreshRemoteMonitoring();
}

document.addEventListener('DOMContentLoaded', () => {
    new MutationObserver(() => { if (currentPage === 'remote-monitoring') updateRemoteMonitoringCharts(); })
        .observe(document.body, {attributes: true, attributeFilter: ['class']});
});
window.addEventListener('pagehide', stopRemoteMonitoring);
