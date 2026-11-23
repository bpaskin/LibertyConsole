#!/usr/bin/env node
// Dependency-free regression tests for ND targeting, REST compatibility and safe file operations.
// Run with: node scripts/test-collective-ui.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../console-bundle/src/main/webapp/js/collective.js'), 'utf8');
const one = {id: 'one', host: 'host-one', name: 'same-name', userDir: 'C:/Liberty users/usr', state: 'STARTED', cluster: 'first'};
const two = {id: 'two', host: 'host-two', name: 'same-name', userDir: '/opt/wlp/usr', state: 'STARTED', cluster: 'second'};
function runtime() {
    const context = vm.createContext({document: {addEventListener() {}}, AbortSignal, URLSearchParams, console, confirm: () => true});
    vm.runInContext(source, context);
    context.fixture = {servers: [one, two], applications: [], clusters: []};
    vm.runInContext('collectiveData = fixture;', context);
    return context;
}
function same(actual, expected) { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected); }

test('routing identifies a server by host, unencoded user directory and server name', () => {
    const r = runtime();
    const headers = r.collectiveHeaders(one);
    assert.equal(headers['com.ibm.websphere.jmx.connector.rest.routing.serverUserDir'], one.userDir);
    assert.equal(r.collectiveHeaders(two)['com.ibm.websphere.jmx.connector.rest.routing.hostName'], two.host);
    assert.equal(headers['com.ibm.websphere.jmx.connector.rest.routing.hostName'], one.host);
});

test('cluster and server scopes keep identically named servers separate', () => {
    const r = runtime();
    same(r.collectiveTargets('cluster:first'), [one]);
    same(r.collectiveTargets('server:two'), [two]);
    same(r.collectiveTargets('all'), [one, two]);
    same(r.collectiveTargets('local'), []);
    same(r.collectiveTargets('invalid'), []);
});

test('standalone capability detection leaves collective controls hidden', async () => {
    const r = runtime();
    r.fetchAPI = async () => ({available: false});
    r.document.querySelectorAll = () => { throw new Error('Standalone controls must not be changed'); };
    await r.initializeCollective();
    assert.equal(vm.runInContext('collectiveAvailable', r), false);
});

test('deployment escapes XML and writes the archive before its configuration', async () => {
    const r = runtime(); const calls = [];
    r.collectiveFileMetadata = async () => null;
    r.collectiveRequest = async (server, url, options) => calls.push({server, path: decodeURIComponent(url), options});
    await r.deployCollectiveArchive(one, 'my-app', 'war', '/a&"<b>', 'archive bytes');
    assert(calls[0].path.endsWith('/apps/my-app.war'));
    assert(calls[1].path.endsWith('/configDropins/overrides/console-my-app.xml'));
    assert(calls[1].options.body.includes('contextRoot="/a&amp;&quot;&lt;b&gt;"'));
    assert.equal(calls[0].server.id, one.id);
});

test('an existing deployment is not overwritten', async () => {
    const r = runtime();
    r.collectiveFileMetadata = async () => ({fileName: 'existing.war'});
    r.collectiveRequest = async () => assert.fail('Must not write existing deployment');
    await assert.rejects(r.deployCollectiveArchive(one, 'existing', 'war', '', 'bytes'), /already exists/);
});

test('failed configuration transfer removes the newly uploaded archive', async () => {
    const r = runtime(); const calls = [];
    r.collectiveFileMetadata = async () => null;
    r.collectiveRequest = async (server, url, options) => {
        calls.push(options.method);
        if (calls.length === 2) throw new Error('Config write denied');
    };
    await assert.rejects(r.deployCollectiveArchive(one, 'new', 'war', '', 'bytes'), /Config write denied/);
    assert.deepEqual(calls, ['POST', 'POST', 'DELETE']);
});

test('failed cleanup is explicitly reported', async () => {
    const r = runtime(); let calls = 0;
    r.collectiveFileMetadata = async () => null;
    r.collectiveRequest = async () => { if (++calls > 1) throw new Error('Permission denied'); };
    await assert.rejects(r.deployCollectiveArchive(one, 'new', 'war', '', 'bytes'), /cleanup also failed/);
});

test('Liberty metadata string booleans and absolute Windows or Unix paths are supported', () => {
    const r = runtime();
    assert.equal(r.collectiveDropinFile('app', [{directory: 'false', fileName: '/wlp/usr/servers/server/dropins/app.war'}]), 'app.war');
    assert.equal(r.collectiveDropinFile('app', [{directory: false, fileName: 'C:\\wlp\\dropins\\app.ear'}]), 'app.ear');
    assert.equal(r.collectiveDropinFile('app', [{directory: 'true', fileName: '/dropins/app.war'}]), null);
    assert.equal(r.collectiveDropinFile('app', [{directory: 'false', fileName: '/dropins/another.war'}]), null);
});

test('REST errors show the Liberty error, without its serialized Throwable', async () => {
    const r = runtime(); let request;
    r.fetch = async (url, options) => { request = options; return {ok: false, status: 500, text: async () => JSON.stringify({error: 'Host unavailable', throwable: 'private serialized stack'})}; };
    await assert.rejects(r.collectiveRequest(one, '/file/test'), error => error.message.includes('Host unavailable') && !error.message.includes('serialized'));
    assert.equal(request.credentials, 'same-origin');
    assert.equal(request.redirect, 'error');
});

test('selected attribute reads use the per-attribute REST route', async () => {
    const r = runtime();
    r.collectiveRequest = async (server, url) => {
        assert(url.endsWith('/attributes/State'));
        return {json: async () => ({value: 'STOPPED', type: 'java.lang.String'})};
    };
    same(await r.collectiveAttributes(one, 'WebSphere:name=app', ['State']), {State: 'STOPPED'});
});

test('bulk operations preserve each target failure', async () => {
    const r = runtime(); let results;
    r.fixture.applications = [one, two].map(server => ({name: 'app', objectName: 'WebSphere:name=app', serverId: server.id}));
    r.collectiveInvoke = async server => { if (server.id === two.id) throw new Error('Member unavailable'); };
    r.showCollectiveResults = (title, values) => { results = values; };
    r.loadCollective = async () => {};
    vm.runInContext("applicationScope = 'all'", r);
    await r.collectiveApplicationOperation('app', 'start');
    same(results.map(result => result.success), [true, false]);
    assert.equal(results[1].error, 'Member unavailable');
});
