// ND support is optional. All remote calls use Liberty's same-origin, authenticated
// REST connector and its per-request routing headers; no passwords are stored here.
let collectiveAvailable = false;
let collectiveReadOnly = true;
let collectiveData = null;
let collectiveLoading = null;
let applicationScope = 'local';
let collectiveLastFocus = null;
const COLLECTIVE_JMX = '/IBMJMXConnectorREST';
const COLLECTIVE_FILES = 'WebSphere:feature=restConnector,type=FileService,name=FileService';

function collectiveElement(tag, text, className) {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
}

function collectiveButton(label, action, {mutating = false, danger = false, disabled = false} = {}) {
    const button = collectiveElement('button', label, `btn btn-sm ${danger ? 'btn-danger' : 'btn-secondary'}`);
    button.type = 'button';
    button.disabled = disabled || (mutating && collectiveReadOnly);
    if (mutating && collectiveReadOnly) button.title = 'Requires the Administrator role';
    button.addEventListener('click', async () => {
        button.disabled = true;
        try { await action(); } catch (error) { showError(error.message); }
        finally { button.disabled = disabled || (mutating && collectiveReadOnly); }
    });
    return button;
}

function collectiveTable(containerId, headers, rows) {
    const table = collectiveElement('table', undefined, 'data-table');
    const head = table.createTHead().insertRow();
    headers.forEach(label => head.append(collectiveElement('th', label)));
    const body = table.createTBody();
    rows.forEach(values => {
        const row = body.insertRow();
        values.forEach(value => {
            const cell = row.insertCell();
            if (value instanceof Node) cell.append(value);
            else cell.textContent = value == null ? '' : String(value);
        });
    });
    if (!rows.length) {
        const cell = body.insertRow().insertCell();
        cell.colSpan = headers.length;
        cell.textContent = 'No resources found';
        cell.className = 'text-center text-muted';
    }
    document.getElementById(containerId).replaceChildren(table);
}

function collectiveBadge(state) {
    const css = state === 'STARTED' ? 'badge-success' : ['STOPPED', 'NOT STARTED'].includes(state) ? 'badge-danger' : 'badge-warning';
    return collectiveElement('span', state || 'UNKNOWN', `badge ${css}`);
}

async function initializeCollective() {
    try {
        const capabilities = await fetchAPI('/collective/capabilities');
        collectiveAvailable = capabilities.available === true;
        if (!collectiveAvailable) return;
        document.querySelectorAll('.collective-only').forEach(element => { element.hidden = false; });
        document.querySelector('.sidebar-nav').classList.add('collective-navigation');
        document.getElementById('nav-local').open = false;
        document.getElementById('nav-remote').open = false;
        applicationScope = 'all';
        await loadCollective();
    } catch (error) {
        console.error('Collective discovery failed:', error);
    }
}

async function loadCollective() {
    if (!collectiveAvailable) return;
    if (collectiveLoading) return collectiveLoading;
    collectiveLoading = (async () => {
        try {
            collectiveData = await fetchAPI('/collective');
            document.getElementById('collective-name').textContent = collectiveData.name;
            const counts = [
                ['Hosts', collectiveData.hosts.length], ['Clusters', collectiveData.clusters.length],
                ['Servers', collectiveData.servers.length],
                ['Running servers', collectiveData.servers.filter(server => server.state === 'STARTED').length],
                ['Application deployments', collectiveData.applications.length]
            ];
            const stats = collectiveElement('div', undefined, 'stats-grid');
            counts.forEach(([label, count]) => {
                const card = collectiveElement('div', undefined, 'stat-card');
                const content = collectiveElement('div', undefined, 'stat-content');
                content.append(collectiveElement('h3', String(count)), collectiveElement('p', label));
                card.append(content); stats.append(card);
            });
            document.getElementById('collective-overview').replaceChildren(stats);
            const summary = collectiveElement('div', undefined, 'collective-stats');
            counts.forEach(([label, count]) => summary.append(collectiveElement('span', `${count} ${label.toLowerCase()}`)));
            document.getElementById('collective-dashboard-summary').replaceChildren(summary);
            collectiveTable('collective-hosts', ['Host', 'Servers'], collectiveData.hosts.map(host => [host.name, host.serverCount]));
            const filter = document.getElementById('collective-server-filter');
            const oldFilter = filter.value;
            filter.replaceChildren(new Option('All servers', ''));
            collectiveData.clusters.forEach(cluster => filter.add(new Option(cluster.name, cluster.name)));
            if ([...filter.options].some(option => option.value === oldFilter)) filter.value = oldFilter;
            updateApplicationScopes();
            renderCollectiveClusters(); renderCollectiveServers(); renderCollectiveApplications();
            return collectiveData;
        } catch (error) {
            // Preserve a visible failure instead of presenting an empty, healthy collective.
            document.getElementById('collective-dashboard-summary').textContent = 'Collective information is unavailable. Refresh to retry.';
            showError(`Unable to refresh the collective: ${error.message}`);
            return null;
        } finally { collectiveLoading = null; }
    })();
    return collectiveLoading;
}

function updateApplicationScopes() {
    const select = document.getElementById('application-scope');
    select.replaceChildren(new Option('Collective — all servers', 'all'), new Option('This server', 'local'));
    (collectiveData?.clusters || []).forEach(cluster => select.add(new Option(`Cluster: ${cluster.name}`, `cluster:${cluster.name}`)));
    (collectiveData?.servers || []).forEach(server => select.add(new Option(`Server: ${server.name} (${server.host}, ${server.userDir})`, `server:${server.id}`)));
    if (![...select.options].some(option => option.value === applicationScope)) applicationScope = 'all';
    select.value = applicationScope;
    document.getElementById('local-applications').hidden = applicationScope !== 'local';
    document.getElementById('collective-applications').hidden = applicationScope === 'local';
    document.querySelector('#page-applications .page-header button').disabled = applicationScope !== 'local' && collectiveReadOnly;
}

function changeApplicationScope() {
    applicationScope = document.getElementById('application-scope').value;
    updateApplicationScopes();
    updateNavigationSelection();
    loadApplications();
}

function showCollectiveApplications(scope) {
    applicationScope = scope;
    updateApplicationScopes();
    navigateToPage('applications');
}

function collectiveTargets(scope = applicationScope) {
    if (!collectiveData) return [];
    if (scope.startsWith('server:')) return collectiveData.servers.filter(server => server.id === scope.slice(7));
    if (scope.startsWith('cluster:')) return collectiveData.servers.filter(server => server.cluster === scope.slice(8));
    return scope === 'all' ? collectiveData.servers : [];
}

function renderCollectiveClusters() {
    collectiveTable('collective-clusters', ['Cluster', 'State', 'Members', 'Actions'], collectiveData.clusters.map(cluster => {
        const actions = collectiveElement('div', undefined, 'action-buttons');
        actions.append(
            collectiveButton('Servers', () => { document.getElementById('collective-server-filter').value = cluster.name; navigateToPage('servers'); }),
            collectiveButton('Applications', () => showCollectiveApplications(`cluster:${cluster.name}`)),
            collectiveButton('Start', () => collectiveOperation('cluster', cluster.name, 'start'), {mutating: true}),
            collectiveButton('Stop', () => collectiveOperation('cluster', cluster.name, 'stop'), {mutating: true, danger: true}),
            collectiveButton('Generate Plugin', () => collectiveOperation('cluster', cluster.name, 'plugin'), {mutating: true})
        );
        return [cluster.name, collectiveBadge(cluster.state), cluster.memberCount, actions];
    }));
}

function renderCollectiveServers() {
    if (!collectiveData) return;
    const filter = document.getElementById('collective-server-filter').value;
    collectiveTable('collective-servers', ['Server', 'Host / User directory', 'Cluster', 'State', 'Actions'], collectiveData.servers.filter(server => !filter || server.cluster === filter).map(server => {
        const identity = collectiveElement('div', server.host);
        identity.append(collectiveElement('div', server.userDir, 'collective-path'));
        const actions = collectiveElement('div', undefined, 'action-buttons');
        actions.append(
            collectiveButton('Details', () => showCollectiveServer(server)),
            collectiveButton('Monitor', () => showRemoteMonitoring(server.id)),
            collectiveButton('Applications', () => showCollectiveApplications(`server:${server.id}`)),
            collectiveButton('Start', () => collectiveOperation('server', server.id, 'start'), {mutating: true, disabled: server.state === 'STARTED'}),
            collectiveButton('Stop', () => collectiveOperation('server', server.id, 'stop'), {mutating: true, danger: true, disabled: server.state === 'STOPPED'}),
            collectiveButton('Java Dump', () => collectiveOperation('server', server.id, 'dump'), {mutating: true, disabled: server.state !== 'STARTED'})
        );
        return [`${server.name}${server.controller ? ' (controller)' : ''}`, identity, server.cluster || '—', collectiveBadge(server.state), actions];
    }));
}

async function loadCollectiveApplications() {
    await loadCollective();
}

function renderCollectiveApplications() {
    if (!collectiveData || applicationScope === 'local') return;
    const targets = new Set(collectiveTargets().map(server => server.id));
    const applications = collectiveData.applications.filter(app => targets.has(app.serverId));
    const selection = document.getElementById('collective-app-action-target');
    const oldSelection = selection.value;
    selection.replaceChildren();
    [...new Set(applications.map(app => app.name))].sort().forEach(name => selection.add(new Option(name, name)));
    if ([...selection.options].some(option => option.value === oldSelection)) selection.value = oldSelection;
    const bulk = document.getElementById('collective-app-bulk-actions');
    bulk.replaceChildren(...['start', 'stop', 'restart'].map(action => collectiveButton(`${action[0].toUpperCase() + action.slice(1)} All Instances`,
        () => collectiveApplicationOperation(selection.value, action), {mutating: true, disabled: applications.length === 0})));
    collectiveTable('collective-applications-table', ['Application', 'Context roots', 'Server', 'Host', 'State', 'Actions'], applications.map(app => {
        const server = collectiveData.servers.find(item => item.id === app.serverId);
        const actions = collectiveElement('div', undefined, 'action-buttons');
        ['start', 'stop', 'restart'].forEach(action => actions.append(collectiveButton(action[0].toUpperCase() + action.slice(1), async () => {
            if (!confirm(`${action} ${app.name} on ${server.name} (${server.host})?`)) return;
            await collectiveInvoke(server, app.objectName, action);
            showSuccess(`${action} requested for ${app.name} on ${server.name}`);
            await loadCollective();
        }, {mutating: true, disabled: server.state !== 'STARTED'})));
        actions.append(collectiveButton('Undeploy', () => undeployCollectiveApplication(server, app), {mutating: true, danger: true, disabled: server.state !== 'STARTED'}));
        return [app.name, (app.contextRoots || []).join(', ') || '—', app.server, app.host, collectiveBadge(app.state), actions];
    }));
}

async function collectiveApplicationOperation(name, action) {
    const targets = new Set(collectiveTargets().map(server => server.id));
    const applications = collectiveData.applications.filter(app => app.name === name && targets.has(app.serverId));
    if (!applications.length || !confirm(`${action} ${name} on ${applications.length} server(s) in this scope?`)) return;
    const results = [];
    for (const app of applications) {
        const server = collectiveData.servers.find(item => item.id === app.serverId);
        try {
            if (server.state !== 'STARTED') throw new Error('The server is not running.');
            await collectiveInvoke(server, app.objectName, action);
            results.push({target: `${server.name} (${server.host})`, success: true});
        } catch (error) { results.push({target: `${server.name} (${server.host})`, success: false, error: error.message}); }
    }
    showCollectiveResults(`${action} ${name}`, results);
    await loadCollective();
}

async function collectiveOperation(kind, target, action) {
    const server = kind === 'server' ? collectiveData.servers.find(item => item.id === target) : null;
    const label = server ? `${server.name} (${server.host})` : target;
    const warning = server && server.controller && action === 'stop' ? '\nStopping this controller may disconnect the console.' : '';
    if (!confirm(`${action} ${kind} ${label}?${warning}`)) return;
    const response = await fetch(`${API_BASE}/collective/operations`, {
        method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({kind, target, action})
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `HTTP ${response.status}`);
    showCollectiveResults(`${label}: ${action}`, result.results || [{target: label, success: result.success, stdout: result.path}]);
    await loadCollective();
}

function collectiveHeaders(server) {
    return {
        'com.ibm.websphere.jmx.connector.rest.routing.hostName': server.host,
        'com.ibm.websphere.jmx.connector.rest.routing.serverName': server.name,
        'com.ibm.websphere.jmx.connector.rest.routing.serverUserDir': server.userDir
    };
}

async function collectiveRequest(server, path, options = {}) {
    const {timeoutMs = 120000, ...requestOptions} = options;
    const timeout = AbortSignal.timeout(timeoutMs);
    const response = await fetch(`${COLLECTIVE_JMX}${path}`, {
        ...requestOptions, credentials: 'same-origin', redirect: 'error',
        signal: options.signal ? AbortSignal.any([options.signal, timeout]) : timeout,
        headers: {...collectiveHeaders(server), ...(options.headers || {})}
    });
    if (!response.ok) {
        const text = await response.text();
        let detail;
        try {
            const error = JSON.parse(text);
            detail = error.error || error.message || 'The Liberty REST connector failed; check the controller logs.';
        } catch (_) { detail = text.slice(0, 1000); }
        const error = new Error(`${server.name} (${server.host}): HTTP ${response.status}${detail ? ` — ${detail}` : ''}`);
        error.status = response.status;
        throw error;
    }
    return response;
}

async function collectiveInvoke(server, objectName, operation, params = [], signature = []) {
    const response = await collectiveRequest(server, `/mbeans/${encodeURIComponent(objectName)}/operations/${encodeURIComponent(operation)}`, {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({params, signature})
    });
    if (response.status === 204) return null;
    const result = await response.json();
    return result.value;
}

function openCollectiveDialog(title) {
    if (!document.getElementById('collective-modal').classList.contains('active')) collectiveLastFocus = document.activeElement;
    document.getElementById('collective-modal-title').textContent = title;
    const body = document.getElementById('collective-modal-body');
    body.replaceChildren();
    document.getElementById('collective-modal').classList.add('active');
    document.querySelector('#collective-modal .modal-close').focus();
    return body;
}

function closeCollectiveDialog() {
    document.getElementById('collective-modal').classList.remove('active');
    collectiveLastFocus?.focus();
}

document.addEventListener('keydown', event => {
    const modal = document.getElementById('collective-modal');
    if (!modal.classList.contains('active')) return;
    if (event.key === 'Escape') closeCollectiveDialog();
    if (event.key === 'Tab') {
        const controls = [...modal.querySelectorAll('button, input, select, textarea, [tabindex="0"]')].filter(item => !item.disabled);
        if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls.at(-1).focus(); }
        else if (!event.shiftKey && document.activeElement === controls.at(-1)) { event.preventDefault(); controls[0].focus(); }
    }
});

function showCollectiveResults(title, results) {
    const body = openCollectiveDialog(title);
    const successful = results.filter(result => result.success).length;
    body.append(collectiveElement('p', `${successful} of ${results.length} targets completed successfully.`));
    results.forEach(result => {
        const row = collectiveElement('div', undefined, 'collective-result');
        row.append(collectiveElement('strong', result.target), collectiveBadge(result.success ? 'STARTED' : 'FAILED'));
        row.lastChild.textContent = result.success ? 'SUCCESS' : 'FAILED';
        row.append(collectiveElement('pre', [result.error, result.stdout, result.stderr].filter(Boolean).join('\n'), 'collective-output'));
        body.append(row);
    });
}

async function collectiveAttributes(server, objectName, attributes) {
    const values = await Promise.all(attributes.map(async attribute => {
        const response = await collectiveRequest(server, `/mbeans/${encodeURIComponent(objectName)}/attributes/${encodeURIComponent(attribute)}`);
        return [attribute, (await response.json()).value];
    }));
    return Object.fromEntries(values);
}

async function showCollectiveServer(server) {
    const body = openCollectiveDialog(`${server.name} (${server.host})`);
    const info = collectiveElement('div', undefined, 'info-grid');
    const add = (label, value) => {
        const row = collectiveElement('div', undefined, 'info-item');
        row.append(collectiveElement('span', label, 'info-label'), collectiveElement('span', typeof value === 'object' ? JSON.stringify(value) : String(value ?? '—'), 'info-value collective-path'));
        info.append(row);
    };
    add('Host', server.host); add('User directory', server.userDir); add('Cluster', server.cluster || '—'); add('State', server.state);
    body.append(info);
    const actions = collectiveElement('div', undefined, 'action-buttons');
    actions.append(
        collectiveButton('Monitor', () => { closeCollectiveDialog(); showRemoteMonitoring(server.id); }),
        collectiveButton('View messages.log', () => showCollectiveFile(server, '${server.output.dir}/logs/messages.log', false)),
        collectiveButton('View / edit server.xml', () => showCollectiveFile(server, '${server.config.dir}/server.xml', true))
    );
    body.append(actions);
    if (server.state !== 'STARTED') return;
    try {
        const runtime = await collectiveAttributes(server, 'java.lang:type=Runtime', ['VmName', 'VmVersion', 'Uptime']);
        const memory = await collectiveAttributes(server, 'java.lang:type=Memory', ['HeapMemoryUsage']);
        Object.entries(runtime).forEach(([key, value]) => add(key, key === 'Uptime' ? formatUptime(Number(value)) : value));
        if (memory.HeapMemoryUsage) add('Heap used / max', `${formatBytes(Number(memory.HeapMemoryUsage.used))} / ${formatBytes(Number(memory.HeapMemoryUsage.max))}`);
    } catch (error) { body.append(collectiveElement('p', `Live JVM information unavailable: ${error.message}`, 'text-muted')); }
}

async function showCollectiveFile(server, path, editable) {
    const response = await collectiveRequest(server, `/file/${encodeURIComponent(path)}`);
    const original = await response.text();
    const body = openCollectiveDialog(`${server.name}: ${editable ? 'server.xml' : 'messages.log'}`);
    if (!editable) { body.append(collectiveElement('pre', original, 'collective-output')); return; }
    const editor = collectiveElement('textarea');
    editor.className = 'config-editor'; editor.rows = 20; editor.value = original; editor.setAttribute('aria-label', 'Server configuration XML');
    editor.readOnly = collectiveReadOnly;
    body.append(editor, collectiveElement('p', 'Saving creates a backup next to server.xml. Liberty may need a restart for some changes.', 'text-muted'));
    body.append(collectiveButton('Save Changes', async () => {
        const document = new DOMParser().parseFromString(editor.value, 'application/xml');
        if (document.querySelector('parsererror') || document.documentElement.localName !== 'server') throw new Error('Enter valid Liberty server XML.');
        if (!confirm(`Save server.xml on ${server.name} (${server.host})?`)) return;
        const current = await (await collectiveRequest(server, `/file/${encodeURIComponent(path)}`)).text();
        if (current !== original) throw new Error('The configuration changed since it was opened. Reopen the file before saving.');
        await collectiveRequest(server, `/file/${encodeURIComponent(`${path}.console-backup-${Date.now()}`)}`, {method: 'POST', body: original});
        await collectiveRequest(server, `/file/${encodeURIComponent(path)}`, {method: 'POST', body: editor.value});
        showSuccess(`Configuration saved on ${server.name}`); closeCollectiveDialog();
    }, {mutating: true}));
}

function collectiveFormField(form, labelText, type, name, required = false) {
    const group = collectiveElement('div', undefined, 'form-group');
    const label = collectiveElement('label', labelText);
    label.htmlFor = `collective-${name}`;
    const input = collectiveElement(type === 'select' ? 'select' : 'input');
    if (type !== 'select') input.type = type;
    input.id = `collective-${name}`; input.name = name; input.required = required;
    group.append(label, input); form.append(group); return input;
}

function showCollectiveDeployDialog() {
    if (collectiveReadOnly) { showError('Deployment requires the Administrator role'); return; }
    const body = openCollectiveDialog('Deploy to Collective');
    const form = collectiveElement('form');
    const target = collectiveFormField(form, 'Target', 'select', 'target', true);
    // Whole-collective deployment excludes controllers. Individual controllers can be selected explicitly.
    target.add(new Option('All member servers', 'members'));
    collectiveData.clusters.forEach(cluster => target.add(new Option(`Cluster: ${cluster.name}`, `cluster:${cluster.name}`)));
    collectiveData.servers.forEach(server => target.add(new Option(`Server: ${server.name} (${server.host}, ${server.userDir})`, `server:${server.id}`)));
    if (applicationScope.startsWith('server:') || applicationScope.startsWith('cluster:')) target.value = applicationScope;
    const name = collectiveFormField(form, 'Application name', 'text', 'name', true);
    name.pattern = '[A-Za-z0-9][A-Za-z0-9_.-]{0,99}';
    const context = collectiveFormField(form, 'Context root (WAR, optional)', 'text', 'context');
    const file = collectiveFormField(form, 'Application archive (.war or .ear)', 'file', 'file', true);
    file.accept = '.war,.ear';
    form.append(collectiveElement('p', 'The selected servers need remote file write access to apps and configDropins/overrides. Existing applications are kept; use a new name for a new deployment.', 'text-muted'));
    const submit = collectiveElement('button', 'Deploy', 'btn btn-primary'); submit.type = 'submit';
    form.append(submit); body.append(form);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const archive = file.files[0];
        const appName = name.value.trim();
        const extension = archive?.name.split('.').pop().toLowerCase();
        const servers = target.value === 'members' ? collectiveData.servers.filter(server => !server.controller) : collectiveTargets(target.value);
        if (!servers.length || !archive || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(appName) || !['war', 'ear'].includes(extension)) { showError('Choose a valid application name, WAR or EAR archive, and at least one target server.'); return; }
        if (context.value && !context.value.startsWith('/')) { showError('Context root must start with /.'); return; }
        if (!confirm(`Deploy ${appName} to ${servers.length} server(s)?`)) return;
        submit.disabled = true;
        const results = [];
        for (const server of servers) {
            try {
                await deployCollectiveArchive(server, appName, extension, context.value, archive);
                results.push({target: `${server.name} (${server.host})`, success: true, stdout: 'Archive and configuration transferred. Liberty will start the application asynchronously.'});
            } catch (error) { results.push({target: `${server.name} (${server.host})`, success: false, error: error.message}); }
        }
        showCollectiveResults(`Deploy ${appName}`, results);
        await loadCollective();
    });
}

function collectiveXml(value) {
    return String(value).replace(/[<>&"']/g, character => ({'<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;'}[character]));
}

async function collectiveFileMetadata(server, path) {
    return collectiveInvoke(server, COLLECTIVE_FILES, 'getMetaData', [
        {value: path, type: 'java.lang.String'}, {value: 'a', type: 'java.lang.String'}
    ], ['java.lang.String', 'java.lang.String']);
}

async function deployCollectiveArchive(server, name, extension, contextRoot, archive) {
    if (server.state !== 'STARTED') throw new Error('The target server must be running for application deployment.');
    const archivePath = `\${server.config.dir}/apps/${name}.${extension}`;
    const configPath = `\${server.config.dir}/configDropins/overrides/console-${name}.xml`;
    if (collectiveData.applications.some(app => app.serverId === server.id && app.name === name)) throw new Error('An application with this name is already deployed on this server.');
    if (await collectiveFileMetadata(server, archivePath) || await collectiveFileMetadata(server, configPath)) throw new Error('The target archive or configuration already exists.');
    const tag = extension === 'war' ? 'webApplication' : 'enterpriseApplication';
    const root = extension === 'war' && contextRoot ? ` contextRoot="${collectiveXml(contextRoot)}"` : '';
    const config = `<!-- Liberty Console collective deployment -->\n<server><${tag} id="${collectiveXml(name)}" name="${collectiveXml(name)}" location="${collectiveXml(name)}.${extension}"${root}/></server>\n`;
    await collectiveRequest(server, `/file/${encodeURIComponent(archivePath)}`, {method: 'POST', headers: {'Content-Type': 'application/octet-stream'}, body: archive});
    try {
        await collectiveRequest(server, `/file/${encodeURIComponent(configPath)}`, {method: 'POST', headers: {'Content-Type': 'application/xml'}, body: config});
    } catch (error) {
        try { await collectiveRequest(server, `/file/${encodeURIComponent(archivePath)}`, {method: 'DELETE'}); }
        catch (cleanup) { throw new Error(`${error.message}. Archive cleanup also failed: ${cleanup.message}`); }
        throw error;
    }
}

async function undeployCollectiveApplication(server, app) {
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(app.name)) throw new Error('Use the server configuration to remove this application.');
    if (!confirm(`Undeploy ${app.name} from ${server.name} (${server.host})?`)) return;
    const configPath = `\${server.config.dir}/configDropins/overrides/console-${app.name}.xml`;
    const metadata = await collectiveFileMetadata(server, configPath);
    if (metadata) {
        const config = await (await collectiveRequest(server, `/file/${encodeURIComponent(configPath)}`)).text();
        const xml = new DOMParser().parseFromString(config, 'application/xml');
        const element = xml.querySelector('server > webApplication, server > enterpriseApplication');
        if (!config.startsWith('<!-- Liberty Console collective deployment -->') || element?.getAttribute('name') !== app.name || !['war', 'ear'].some(extension => element.getAttribute('location') === `${app.name}.${extension}`)) throw new Error('This application configuration is not managed by the collective console.');
        await collectiveRequest(server, `/file/${encodeURIComponent(configPath)}`, {method: 'DELETE'});
        await collectiveRequest(server, `/file/${encodeURIComponent(`\${server.config.dir}/apps/${element.getAttribute('location')}`)}`, {method: 'DELETE'});
    } else {
        const entries = await collectiveInvoke(server, COLLECTIVE_FILES, 'getDirectoryEntries', [
            {value: '${server.config.dir}/dropins', type: 'java.lang.String'}, {value: 'false', type: 'java.lang.Boolean'}, {value: 'a', type: 'java.lang.String'}
        ], ['java.lang.String', 'boolean', 'java.lang.String']);
        const fileName = collectiveDropinFile(app.name, entries);
        if (!fileName) throw new Error('Use the target server configuration to remove this application; it was not deployed by this console or as a dropins archive.');
        await collectiveRequest(server, `/file/${encodeURIComponent(`\${server.config.dir}/dropins/${fileName}`)}`, {method: 'DELETE'});
    }
    showSuccess(`Undeploy requested for ${app.name} on ${server.name}`);
    await loadCollective();
}

function collectiveDropinFile(name, entries) {
    const entry = (entries || []).find(item => item.directory !== true && item.directory !== 'true'
        && ['war', 'ear'].some(extension => item.fileName.replace(/\\/g, '/').split('/').pop() === `${name}.${extension}`));
    return entry ? entry.fileName.replace(/\\/g, '/').split('/').pop() : null;
}
