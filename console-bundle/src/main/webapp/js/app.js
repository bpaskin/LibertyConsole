// Liberty Admin Console - Main Application JavaScript

// Context root of the console (e.g. "/console"). The feature lets the path be
// changed with <usr_console contextPath="..."/>, so take it from the page URL.
const CONTEXT_ROOT = window.location.pathname.replace(/\/[^/]*$/, '');

// API Base URL
const API_BASE = `${CONTEXT_ROOT}/api`;

// Current page tracking
let currentPage = 'dashboard';

// Global settings object
let appSettings = {
    theme: 'light',
    showCpuChart: true,
    showThreadChart: true,
    showGcChart: true,
    dynamicCharts: [],
    autoRefreshMetrics: false
};

// Dashboard auto-refresh (session-only, not persisted)
let autoRefresh = true;
let refreshInterval = 30;

// Check authentication on page load
document.addEventListener('DOMContentLoaded', async function() {
    await loadSettings();
    checkAuthentication();
});

// Load settings from backend
async function loadSettings() {
    try {
        const response = await fetch(`${API_BASE}/settings`, {
            credentials: 'same-origin'
        });
        
        if (response.ok) {
            appSettings = await response.json();
            applySettings();
        } else {
            // Use default settings if backend fails
            applySettings();
        }
    } catch (error) {
        console.error('Error loading settings:', error);
        applySettings();
    }
}

// Apply settings to UI
function applySettings() {
    const body = document.body;
    const themeIcon = document.querySelector('.btn-icon[onclick="toggleTheme()"] i');
    
    // Apply theme
    if (appSettings.theme === 'dark') {
        body.classList.add('dark-mode');
        if (themeIcon) {
            themeIcon.classList.remove('fa-moon');
            themeIcon.classList.add('fa-sun');
        }
    } else {
        body.classList.remove('dark-mode');
        if (themeIcon) {
            themeIcon.classList.remove('fa-sun');
            themeIcon.classList.add('fa-moon');
        }
    }
    
    // Apply dashboard auto-refresh setting
    if (currentPage === 'dashboard') {
        if (autoRefresh) {
            startAutoRefresh();
        } else {
            stopAutoRefresh();
        }
    }
    
    // Apply monitoring auto-refresh from persisted settings
    const autoRefreshCheckbox = document.getElementById('auto-refresh-metrics');
    if (autoRefreshCheckbox) {
        autoRefreshCheckbox.checked = appSettings.autoRefreshMetrics;
        if (appSettings.autoRefreshMetrics) {
            startMetricsRefresh();
        } else {
            if (metricsRefreshInterval) {
                clearInterval(metricsRefreshInterval);
                metricsRefreshInterval = null;
            }
        }
    }
    
    // Restore dynamic charts if on monitoring page
    if (currentPage === 'monitoring' && appSettings.dynamicCharts && appSettings.dynamicCharts.length > 0) {
        // Wait a bit for the page to load
        setTimeout(() => {
            appSettings.dynamicCharts.forEach(metricKey => {
                if (availableDetailedMetrics[metricKey] && !dynamicMetricCharts[metricKey]) {
                    const select = document.getElementById('dynamic-metric-select');
                    if (select) {
                        select.value = metricKey;
                        addSelectedMetricChart();
                    }
                }
            });
        }, 500);
    }
}

// Save settings to backend
async function saveSettings() {
    try {
        const response = await fetch(`${API_BASE}/settings`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'same-origin',
            body: JSON.stringify(appSettings)
        });
        
        if (!response.ok) {
            console.error('Failed to save settings');
        }
    } catch (error) {
        console.error('Error saving settings:', error);
    }
}

// Check if user is authenticated
async function checkAuthentication() {
    // With FORM-based authentication, Liberty manages the session
    // No need to check for tokens - if user can access this page, they're authenticated
    try {
        // Try to fetch server info to verify session is valid
        const response = await fetch(`${API_BASE}/server/info`, {
            credentials: 'same-origin' // Include session cookie
        });
        
        if (!response.ok) {
            // Session invalid or expired, Liberty will redirect to login
            return;
        }
        
        // Session valid, initialize app
        await loadCurrentUser();
        await initializeCollective();
        
        initializeNavigation();
        loadDashboardData();
        // Don't start auto-refresh here - it will be started in applySettings() after settings are loaded
    } catch (error) {
        console.error('Authentication check failed:', error);
        // Liberty will handle redirect to login if session is invalid
    }
}

// Show the signed-in user. Users in <reader-role> only (no <administrator-role>) are read-only.
async function loadCurrentUser() {
    try {
        const response = await fetch(`${API_BASE}/user`, { credentials: 'same-origin' });
        if (!response.ok) {
            return;
        }
        const user = await response.json();
        collectiveReadOnly = !user.administrator;
        const label = user.readOnly ? `${user.name} (read-only)` : user.name;
        document.getElementById('username').textContent = label;
    } catch (error) {
        console.error('Could not load the current user:', error);
    }
}

// Logout function
function logout() {
    if (confirm('Are you sure you want to logout?')) {
        // Invalidate Liberty session and redirect to login
        // Use ibm_security_logout for FORM authentication
        window.location.href = 'ibm_security_logout?logoutExitPage=login.html';
    }
}

// Navigation handling
function initializeNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    
    navItems.forEach(item => {
        item.addEventListener('click', function(e) {
            e.preventDefault();
            const page = this.getAttribute('data-page');
            navigateToPage(page, this.getAttribute('data-scope'));
        });
    });
}

function updateNavigationSelection(page = currentPage) {
    const scope = collectiveAvailable && applicationScope !== 'local' ? 'all' : 'local';
    document.querySelectorAll('.nav-item').forEach(item => {
        const active = item.getAttribute('data-page') === page &&
            (page !== 'applications' || item.getAttribute('data-scope') === scope);
        item.classList.toggle('active', active);
        if (active) {
            item.setAttribute('aria-current', 'page');
            const group = item.closest('.nav-group');
            if (collectiveAvailable && group) group.open = true;
        } else {
            item.removeAttribute('aria-current');
        }
    });
}

function navigateToPage(page, scope) {
    if (page === 'applications' && collectiveAvailable && scope) {
        applicationScope = scope;
        updateApplicationScopes();
    }
    if (currentPage === 'remote-monitoring' && page !== 'remote-monitoring') stopRemoteMonitoring();
    // Stop metrics refresh when leaving monitoring page
    if (currentPage === 'monitoring' && page !== 'monitoring') {
        if (metricsRefreshInterval) {
            clearInterval(metricsRefreshInterval);
            metricsRefreshInterval = null;
        }
    }
    
    // Stop dashboard auto-refresh when leaving dashboard page
    if (currentPage === 'dashboard' && page !== 'dashboard') {
        stopAutoRefresh();
    }
    
    // Update active nav item
    updateNavigationSelection(page);
    
    // Update page visibility
    document.querySelectorAll('.page').forEach(p => {
        p.classList.remove('active');
    });
    document.getElementById(`page-${page}`).classList.add('active');
    
    // Update breadcrumb
    document.getElementById('current-page').textContent =
        page === 'remote-monitoring' ? 'Remote Monitoring' : page.charAt(0).toUpperCase() + page.slice(1);
    
    currentPage = page;
    
    // Load page-specific data
    loadPageData(page);
    
    // Start dashboard auto-refresh when entering dashboard page (if enabled)
    if (page === 'dashboard' && autoRefresh) {
        startAutoRefresh();
    }
}

function loadPageData(page) {
    switch(page) {
        case 'remote-monitoring':
            startRemoteMonitoring();
            break;
        case 'collective':
        case 'clusters':
        case 'servers':
            loadCollective();
            break;
        case 'dashboard':
            loadDashboardData();
            break;
        case 'server':
            loadServerDetails();
            break;
        case 'applications':
            loadApplications();
            break;
        case 'resources':
            loadResources();
            break;
        case 'logs':
            loadLogs();
            break;
        case 'batch':
            loadBatch();
            break;
        case 'monitoring':
            loadMetrics();
            break;
        case 'configuration':
            loadConfiguration();
            break;
    }
}

// Dashboard data loading
async function loadDashboardData() {
    try {
        if (collectiveAvailable) loadCollective();
        // Load server info
        const serverInfo = await fetchAPI('/server/info');
        updateDashboardStats(serverInfo);
        updateServerInfo(serverInfo);
        
        // Load applications
        const applications = await fetchAPI('/applications');
        document.getElementById('app-count').textContent = applications.length;
        
        // Batch information is always shown; it never raises an error alert
        loadBatchSummary();
        
    } catch (error) {
        console.error('Error loading dashboard data:', error);
        showError('Failed to load dashboard data');
    }
}

function updateDashboardStats(serverInfo) {
    // Update server status
    document.getElementById('server-status').textContent = serverInfo.state;
    
    // Update memory usage
    if (serverInfo.memoryInfo) {
        const usage = serverInfo.memoryInfo.usagePercentage.toFixed(1);
        document.getElementById('memory-usage').textContent = usage + '%';
        document.getElementById('memory-progress').style.width = usage + '%';
        
        // Update memory details
        const memoryDetails = document.getElementById('memory-details');
        memoryDetails.innerHTML = `
            <p><strong>Used:</strong> ${formatBytes(serverInfo.memoryInfo.usedMemory)}</p>
            <p><strong>Free:</strong> ${formatBytes(serverInfo.memoryInfo.freeMemory)}</p>
            <p><strong>Total:</strong> ${formatBytes(serverInfo.memoryInfo.totalMemory)}</p>
            <p><strong>Max:</strong> ${formatBytes(serverInfo.memoryInfo.maxMemory)}</p>
        `;
    }
    
    // Update uptime
    if (serverInfo.uptime) {
        document.getElementById('uptime').textContent = formatUptime(serverInfo.uptime);
    }
}

function updateServerInfo(serverInfo) {
    const infoGrid = document.getElementById('server-info');
    infoGrid.innerHTML = `
        <div class="info-item">
            <span class="info-label">Server Name:</span>
            <span class="info-value">${serverInfo.serverName}</span>
        </div>
        <div class="info-item">
            <span class="info-label">Version:</span>
            <span class="info-value">${serverInfo.version}</span>
        </div>
        <div class="info-item">
            <span class="info-label">Java Version:</span>
            <span class="info-value">${serverInfo.javaVersion}</span>
        </div>
        <div class="info-item">
            <span class="info-label">Java Vendor:</span>
            <span class="info-value">${serverInfo.javaVendor}</span>
        </div>
        <div class="info-item">
            <span class="info-label">OS:</span>
            <span class="info-value">${serverInfo.osName} ${serverInfo.osVersion}</span>
        </div>
        <div class="info-item">
            <span class="info-label">Start Time:</span>
            <span class="info-value">${formatDateTime(serverInfo.startTime)}</span>
        </div>
    `;
}

// Server details page
async function loadServerDetails() {
    try {
        const serverInfo = await fetchAPI('/server/info');
        const detailsDiv = document.getElementById('server-details');
        
        detailsDiv.innerHTML = `
            <div class="info-item">
                <span class="info-label">Server Name:</span>
                <span class="info-value">${serverInfo.serverName}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Version:</span>
                <span class="info-value">${serverInfo.version}</span>
            </div>
            <div class="info-item">
                <span class="info-label">State:</span>
                <span class="info-value"><span class="badge badge-success">${serverInfo.state}</span></span>
            </div>
            <div class="info-item">
                <span class="info-label">Java Version:</span>
                <span class="info-value">${serverInfo.javaVersion}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Java Vendor:</span>
                <span class="info-value">${serverInfo.javaVendor}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Operating System:</span>
                <span class="info-value">${serverInfo.osName} ${serverInfo.osVersion}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Start Time:</span>
                <span class="info-value">${formatDateTime(serverInfo.startTime)}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Uptime:</span>
                <span class="info-value">${formatUptime(serverInfo.uptime)}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Installed Features:</span>
                <span class="info-value">${serverInfo.installedFeatures.length === 0 ? '<span style="color: #e74c3c;">Error reading server.xml</span>' : serverInfo.installedFeatures.length}</span>
            </div>
            <div class="info-item">
                <span class="info-label">Active Applications:</span>
                <span class="info-value">${serverInfo.activeApplications.length}</span>
            </div>
        `;
    } catch (error) {
        console.error('Error loading server details:', error);
        showError('Failed to load server details');
    }
}

// Applications management
async function loadApplications() {
    if (collectiveAvailable && applicationScope !== 'local') {
        return loadCollectiveApplications();
    }
    try {
        const applications = await fetchAPI('/applications');
        const tbody = document.querySelector('#applications-table tbody');
        
        if (applications.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-center">No applications deployed</td></tr>';
            return;
        }
        
        tbody.innerHTML = applications.map(app => `
            <tr>
                <td>${app.name}</td>
                <td>${app.contextRoot}</td>
                <td><span class="badge ${getStateBadgeClass(app.state)}">${app.state}</span></td>
                <td>${app.type || 'WAR'}</td>
                <td>
                    <div class="action-buttons">
                        ${app.state === 'STARTED' ? 
                            `<button class="btn btn-sm btn-secondary" onclick="stopApplication('${app.name}')">
                                <i class="fas fa-stop"></i> Stop
                            </button>` :
                            `<button class="btn btn-sm btn-success" onclick="startApplication('${app.name}')">
                                <i class="fas fa-play"></i> Start
                            </button>`
                        }
                        <button class="btn btn-sm btn-secondary" onclick="restartApplication('${app.name}')">
                            <i class="fas fa-redo"></i> Restart
                        </button>
                        ${app.name !== 'liberty-admin-console' ?
                            `<button class="btn btn-sm btn-danger" onclick="undeployApplication('${app.name}')">
                                <i class="fas fa-trash"></i> Undeploy
                            </button>` : ''
                        }
                    </div>
                </td>
            </tr>
        `).join('');
    } catch (error) {
        console.error('Error loading applications:', error);
        showError('Failed to load applications');
    }
}

async function startApplication(name) {
    try {
        await fetchAPI(`/applications/${name}/start`, 'POST');
        showSuccess(`Application ${name} started successfully`);
        loadApplications();
    } catch (error) {
        showError(`Failed to start application: ${error.message}`);
    }
}

async function stopApplication(name) {
    try {
        await fetchAPI(`/applications/${name}/stop`, 'POST');
        showSuccess(`Application ${name} stopped successfully`);
        loadApplications();
    } catch (error) {
        showError(`Failed to stop application: ${error.message}`);
    }
}

async function restartApplication(name) {
    try {
        await fetchAPI(`/applications/${name}/restart`, 'POST');
        showSuccess(`Application ${name} restarted successfully`);
        loadApplications();
    } catch (error) {
        showError(`Failed to restart application: ${error.message}`);
    }
}

async function undeployApplication(name) {
    if (!confirm(`Are you sure you want to undeploy ${name}?`)) {
        return;
    }
    
    try {
        await fetchAPI(`/applications/${name}`, 'DELETE');
        showSuccess(`Application ${name} undeployed successfully`);
        loadApplications();
    } catch (error) {
        showError(`Failed to undeploy application: ${error.message}`);
    }
}

// Deploy application dialog
function showDeployDialog() {
    if (collectiveAvailable && applicationScope !== 'local') {
        return showCollectiveDeployDialog();
    }
    document.getElementById('deploy-modal').classList.add('active');
}

function closeDeployDialog() {
    document.getElementById('deploy-modal').classList.remove('active');
    document.getElementById('deploy-form').reset();
    document.getElementById('app-file').value = '';
}

async function deployApplication(event) {
    event.preventDefault();
    
    const name = document.getElementById('app-name').value.trim();
    const contextRootInput = document.getElementById('context-root').value.trim();
    const fileInput = document.getElementById('app-file');
    const selectedFile = fileInput.files[0];
    const contextRoot = contextRootInput || (name ? `/${name}` : '');
    
    try {
        if (!selectedFile) {
            throw new Error('Application file is required');
        }

        const formData = new FormData();
        if (name) {
            formData.append('name', name);
        }
        if (contextRoot) {
            formData.append('contextRoot', contextRoot);
        }
        formData.append('file', selectedFile);

        const response = await fetch(`${API_BASE}/applications/upload`, {
            method: 'POST',
            headers: {
                // Session cookie used for authentication
            },
            body: formData
        });

        if (!response.ok) {
            let errorMessage = `HTTP ${response.status}`;
            try {
                const errorData = await response.json();
                errorMessage = errorData.error || errorMessage;
            } catch (parseError) {
                // Ignore JSON parse failure and use status fallback
            }
            throw new Error(errorMessage);
        }

        const deployed = await response.json();
        
        const deployedSource = deployed.fileName ? ` from ${deployed.fileName}` : '';
        showSuccess(`Application ${deployed.name} deployed successfully at ${deployed.contextRoot}${deployedSource}`);
        closeDeployDialog();
        await loadApplications();
    } catch (error) {
        showError(`Failed to deploy application: ${error.message}`);
    }
}
// Resources management
async function loadResources() {
    // Load all resource types
    loadDataSources();
    loadJMSFactories();
    loadJMSQueues();
    loadJMSTopics();
    loadThreadPools();
    loadConnectionManagers();
}

async function loadDataSources() {
    const container = document.getElementById('datasources-container');
    try {
        // Try to fetch datasources from server configuration
        const response = await fetch(`${API_BASE}/configuration/server.xml`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (response.ok) {
            const config = await response.text();
            const datasources = parseDataSourcesFromConfig(config);
            
            if (datasources.length > 0) {
                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>JNDI Name</th>
                                <th>Driver</th>
                                <th>URL</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${datasources.map(ds => `
                                <tr>
                                    <td><strong>${ds.id}</strong></td>
                                    <td>${ds.jndiName}</td>
                                    <td>${ds.driver}</td>
                                    <td>${ds.url}</td>
                                    <td><span class="badge badge-success">Configured</span></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            } else {
                container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JDBC datasources configured</p>';
            }
        } else {
            container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JDBC datasources configured</p>';
        }
    } catch (error) {
        console.error('Error loading datasources:', error);
        container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JDBC datasources configured</p>';
    }
}

async function loadJMSFactories() {
    const container = document.getElementById('jms-factories-container');
    try {
        const response = await fetch(`${API_BASE}/configuration/server.xml`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (response.ok) {
            const config = await response.text();
            const factories = parseJMSFactoriesFromConfig(config);
            
            if (factories.length > 0) {
                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>JNDI Name</th>
                                <th>Type</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${factories.map(factory => `
                                <tr>
                                    <td><strong>${factory.id}</strong></td>
                                    <td>${factory.jndiName}</td>
                                    <td>${factory.type}</td>
                                    <td><span class="badge badge-success">Configured</span></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            } else {
                container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS connection factories configured</p>';
            }
        } else {
            container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS connection factories configured</p>';
        }
    } catch (error) {
        console.error('Error loading JMS factories:', error);
        container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS connection factories configured</p>';
    }
}

async function loadJMSQueues() {
    const container = document.getElementById('jms-queues-container');
    try {
        const response = await fetch(`${API_BASE}/configuration/server.xml`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (response.ok) {
            const config = await response.text();
            const queues = parseJMSQueuesFromConfig(config);
            
            if (queues.length > 0) {
                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>JNDI Name</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${queues.map(queue => `
                                <tr>
                                    <td><strong>${queue.id}</strong></td>
                                    <td>${queue.jndiName}</td>
                                    <td><span class="badge badge-success">Configured</span></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            } else {
                container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS queues configured</p>';
            }
        } else {
            container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS queues configured</p>';
        }
    } catch (error) {
        console.error('Error loading JMS queues:', error);
        container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS queues configured</p>';
    }
}

async function loadJMSTopics() {
    const container = document.getElementById('jms-topics-container');
    try {
        const response = await fetch(`${API_BASE}/configuration/server.xml`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (response.ok) {
            const config = await response.text();
            const topics = parseJMSTopicsFromConfig(config);
            
            if (topics.length > 0) {
                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>JNDI Name</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${topics.map(topic => `
                                <tr>
                                    <td><strong>${topic.id}</strong></td>
                                    <td>${topic.jndiName}</td>
                                    <td><span class="badge badge-success">Configured</span></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            } else {
                container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS topics configured</p>';
            }
        } else {
            container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS topics configured</p>';
        }
    } catch (error) {
        console.error('Error loading JMS topics:', error);
        container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No JMS topics configured</p>';
    }
}

async function loadThreadPools() {
    const container = document.getElementById('thread-pools-container');
    try {
        // Fetch thread pool configuration
        const configResponse = await fetch(`${API_BASE}/configuration/server.xml`, {
            credentials: 'same-origin'
        });
        
        // Fetch active thread counts
        const statsResponse = await fetch(`${API_BASE}/server/threadpools`, {
            credentials: 'same-origin'
        });
        
        let threadPoolStats = {};
        if (statsResponse.ok) {
            threadPoolStats = await statsResponse.json();
        }
        
        if (configResponse.ok) {
            const config = await configResponse.text();
            const customThreadPools = parseThreadPoolsFromConfig(config);
            
            // Always include the Default Executor as the first thread pool
            const threadPools = [
                {
                    id: 'Default Executor',
                    coreThreads: null,
                    maxThreads: null,
                    activeThreads: threadPoolStats['Default Executor'] || 0
                },
                ...customThreadPools.map(pool => ({
                    ...pool,
                    activeThreads: threadPoolStats[pool.id] || 0
                }))
            ];
            
            container.innerHTML = `
                <table class="data-table">
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>Core Threads</th>
                            <th>Max Threads</th>
                            <th>Active Threads</th>
                            <th>Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${threadPools.map(pool => `
                            <tr>
                                <td><strong>${pool.id}</strong></td>
                                <td>${pool.coreThreads || 'Default'}</td>
                                <td>${pool.maxThreads || 'Unlimited'}</td>
                                <td><strong>${pool.activeThreads}</strong></td>
                                <td><span class="badge badge-success">Active</span></td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            `;
        } else {
            // Show default executor even if config fetch fails
            const defaultActiveThreads = threadPoolStats['Default Executor'] || 0;
            container.innerHTML = `
                <table class="data-table">
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>Core Threads</th>
                            <th>Max Threads</th>
                            <th>Active Threads</th>
                            <th>Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td><strong>Default Executor</strong></td>
                            <td>Default</td>
                            <td>Unlimited</td>
                            <td><strong>${defaultActiveThreads}</strong></td>
                            <td><span class="badge badge-success">Active</span></td>
                        </tr>
                    </tbody>
                </table>
            `;
        }
    } catch (error) {
        console.error('Error loading thread pools:', error);
        // Show default executor on error
        container.innerHTML = `
            <table class="data-table">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Core Threads</th>
                        <th>Max Threads</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td><strong>Default Executor</strong></td>
                        <td>Default</td>
                        <td>Unlimited</td>
                        <td><span class="badge badge-success">Active</span></td>
                    </tr>
                </tbody>
            </table>
        `;
    }
}

async function loadConnectionManagers() {
    const container = document.getElementById('connection-managers-container');
    try {
        const response = await fetch(`${API_BASE}/configuration/server.xml`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (response.ok) {
            const config = await response.text();
            const connManagers = parseConnectionManagersFromConfig(config);
            
            if (connManagers.length > 0) {
                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>Max Pool Size</th>
                                <th>Min Pool Size</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${connManagers.map(cm => `
                                <tr>
                                    <td><strong>${cm.id}</strong></td>
                                    <td>${cm.maxPoolSize || 'Default'}</td>
                                    <td>${cm.minPoolSize || 'Default'}</td>
                                    <td><span class="badge badge-success">Active</span></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            } else {
                container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No custom connection managers configured (using defaults)</p>';
            }
        } else {
            container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No custom connection managers configured (using defaults)</p>';
        }
    } catch (error) {
        console.error('Error loading connection managers:', error);
        container.innerHTML = '<p class="text-muted"><i class="fas fa-info-circle"></i> No custom connection managers configured (using defaults)</p>';
    }
}

// Helper functions to parse resources from server.xml
function parseDataSourcesFromConfig(config) {
    const datasources = [];
    const dsRegex = /<dataSource[^>]*id="([^"]*)"[^>]*>/g;
    let match;
    
    while ((match = dsRegex.exec(config)) !== null) {
        const id = match[1];
        const dsBlock = config.substring(match.index, config.indexOf('</dataSource>', match.index) + 13);
        
        const jndiMatch = dsBlock.match(/jndiName="([^"]*)"/);
        const urlMatch = dsBlock.match(/serverName="([^"]*)"|url="([^"]*)"/);
        
        datasources.push({
            id: id,
            jndiName: jndiMatch ? jndiMatch[1] : 'N/A',
            driver: 'JDBC Driver',
            url: urlMatch ? (urlMatch[1] || urlMatch[2] || 'N/A') : 'N/A'
        });
    }
    
    return datasources;
}

function parseJMSFactoriesFromConfig(config) {
    const factories = [];
    const factoryRegex = /<(jmsConnectionFactory|jmsQueueConnectionFactory|jmsTopicConnectionFactory)[^>]*id="([^"]*)"[^>]*>/g;
    let match;
    
    while ((match = factoryRegex.exec(config)) !== null) {
        const type = match[1];
        const id = match[2];
        const factoryBlock = config.substring(match.index, config.indexOf('>', match.index) + 1);
        
        const jndiMatch = factoryBlock.match(/jndiName="([^"]*)"/);
        
        factories.push({
            id: id,
            jndiName: jndiMatch ? jndiMatch[1] : 'N/A',
            type: type.replace('jms', 'JMS ').replace('ConnectionFactory', ' Connection Factory')
        });
    }
    
    return factories;
}

function parseJMSQueuesFromConfig(config) {
    const queues = [];
    const queueRegex = /<jmsQueue[^>]*id="([^"]*)"[^>]*>/g;
    let match;
    
    while ((match = queueRegex.exec(config)) !== null) {
        const id = match[1];
        const queueBlock = config.substring(match.index, config.indexOf('>', match.index) + 1);
        
        const jndiMatch = queueBlock.match(/jndiName="([^"]*)"/);
        
        queues.push({
            id: id,
            jndiName: jndiMatch ? jndiMatch[1] : 'N/A'
        });
    }
    
    return queues;
}

function parseJMSTopicsFromConfig(config) {
    const topics = [];
    const topicRegex = /<jmsTopic[^>]*id="([^"]*)"[^>]*>/g;
    let match;
    
    while ((match = topicRegex.exec(config)) !== null) {
        const id = match[1];
        const topicBlock = config.substring(match.index, config.indexOf('>', match.index) + 1);
        
        const jndiMatch = topicBlock.match(/jndiName="([^"]*)"/);
        
        topics.push({
            id: id,
            jndiName: jndiMatch ? jndiMatch[1] : 'N/A'
        });
    }
    
    return topics;
}

function parseThreadPoolsFromConfig(config) {
    const threadPools = [];
    
    // First, parse concurrencyPolicy elements
    const concurrencyPolicies = {};
    const policyRegex = /<concurrencyPolicy[^>]*>/g;
    let match;
    
    while ((match = policyRegex.exec(config)) !== null) {
        const policyBlock = config.substring(match.index, config.indexOf('>', match.index) + 1);
        const idMatch = policyBlock.match(/id="([^"]*)"/);
        const maxMatch = policyBlock.match(/max="([^"]*)"/);
        
        if (idMatch && maxMatch) {
            concurrencyPolicies[idMatch[1]] = maxMatch[1];
        }
    }
    
    // Parse <executor> elements
    const executorRegex = /<executor[^>]*id="([^"]*)"[^>]*>/g;
    
    while ((match = executorRegex.exec(config)) !== null) {
        const id = match[1];
        const poolBlock = config.substring(match.index, config.indexOf('>', match.index) + 1);
        
        const coreMatch = poolBlock.match(/coreThreads="([^"]*)"/);
        const maxMatch = poolBlock.match(/maxThreads="([^"]*)"/);
        
        threadPools.push({
            id: id,
            coreThreads: coreMatch ? coreMatch[1] : null,
            maxThreads: maxMatch ? maxMatch[1] : null
        });
    }
    
    // Parse <managedExecutorService> elements (both self-closing and with content)
    const mesRegex = /<managedExecutorService[^>]*(?:\/>|>[\s\S]*?<\/managedExecutorService>)/g;
    while ((match = mesRegex.exec(config)) !== null) {
        const mesFullBlock = match[0];
        const mesOpenTag = mesFullBlock.substring(0, mesFullBlock.indexOf('>') + 1);
        
        const jndiMatch = mesOpenTag.match(/jndiName="([^"]*)"/);
        const idMatch = mesOpenTag.match(/id="([^"]*)"/);
        const policyRefMatch = mesOpenTag.match(/concurrencyPolicyRef="([^"]*)"/);
        
        const name = jndiMatch ? jndiMatch[1] : (idMatch ? idMatch[1] : null);
        let maxThreads = null;
        
        // First, check for referenced concurrency policy
        if (policyRefMatch && concurrencyPolicies[policyRefMatch[1]]) {
            maxThreads = concurrencyPolicies[policyRefMatch[1]];
        }
        
        // Second, check for inline nested concurrencyPolicy
        if (!maxThreads) {
            const nestedPolicyMatch = mesFullBlock.match(/<concurrencyPolicy[^>]*max="([^"]*)"/);
            if (nestedPolicyMatch) {
                maxThreads = nestedPolicyMatch[1];
            }
        }
        
        if (name) {
            threadPools.push({
                id: name,
                coreThreads: null,
                maxThreads: maxThreads
            });
        }
    }
    
    return threadPools;
}

function parseConnectionManagersFromConfig(config) {
    const connManagers = [];
    const cmRegex = /<connectionManager[^>]*id="([^"]*)"[^>]*>/g;
    let match;
    
    while ((match = cmRegex.exec(config)) !== null) {
        const id = match[1];
        const cmBlock = config.substring(match.index, config.indexOf('>', match.index) + 1);
        
        const maxMatch = cmBlock.match(/maxPoolSize="([^"]*)"/);
        const minMatch = cmBlock.match(/minPoolSize="([^"]*)"/);
        
        connManagers.push({
            id: id,
            maxPoolSize: maxMatch ? maxMatch[1] : null,
            minPoolSize: minMatch ? minMatch[1] : null
        });
    }
    
    return connManagers;
}


// Batch (Jakarta Batch job instances)
// The data comes from Liberty's batch management REST API (batchManagement-1.0),
// which accepts the console sign-in, the same way /metrics does. The Batch page
// and the dashboard card are always shown; when the API isn't there they say how
// to enable it.
const BATCH_API = '/ibm/api/batch';
const BATCH_RUNNING = ['STARTING', 'STARTED', 'STOPPING'];
const BATCH_NOT_COMPLETED = ['FAILED', 'STOPPED', 'ABANDONED'];

async function fetchBatchInstances() {
    let response;
    try {
        response = await fetch(`${BATCH_API}/jobinstances?page=0&pageSize=200`, {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
    } catch (error) {
        return { instances: null, message: `Batch information could not be read: ${error.message}` };
    }
    if (response.status === 404) {
        return { instances: null, message: 'Batch management is not enabled. Add batchManagement-1.0 and batch-2.1 to the featureManager in server.xml.' };
    }
    if (response.status === 401 || response.status === 403) {
        return { instances: null, message: 'The batch management API refused your sign-in. Your user needs to be in the administrator-role or reader-role in server.xml.' };
    }
    if (!response.ok) {
        return { instances: null, message: `The batch management API answered with HTTP ${response.status}.` };
    }
    try {
        const instances = await response.json();
        if (Array.isArray(instances)) {
            return { instances: instances.sort((a, b) => b.instanceId - a.instanceId), message: null };
        }
    } catch (error) {
        // falls through to the message below
    }
    return { instances: null, message: 'The batch management API returned an unexpected response.' };
}

function countBatchInstances(instances) {
    return {
        total: instances.length,
        running: instances.filter(i => BATCH_RUNNING.includes(i.batchStatus)).length,
        completed: instances.filter(i => i.batchStatus === 'COMPLETED').length,
        failed: instances.filter(i => BATCH_NOT_COMPLETED.includes(i.batchStatus)).length
    };
}

async function loadBatchSummary() {
    const { instances } = await fetchBatchInstances();
    const count = document.getElementById('batch-count');
    const label = document.getElementById('batch-count-label');
    if (instances === null) {
        count.textContent = 'N/A';
        label.textContent = 'Batch Jobs (not enabled)';
        return;
    }
    const counts = countBatchInstances(instances);
    count.textContent = counts.total;
    label.textContent = `Batch Jobs (${counts.running} running, ${counts.failed} failed)`;
}

function batchCell(row, text) {
    const td = document.createElement('td');
    td.textContent = text === undefined || text === null || text === '' ? '-' : text;
    row.appendChild(td);
    return td;
}

function batchStatusBadge(status) {
    const badge = document.createElement('span');
    let cls = 'badge-warning';
    if (status === 'COMPLETED') cls = 'badge-success';
    else if (BATCH_NOT_COMPLETED.includes(status)) cls = 'badge-danger';
    badge.className = `badge ${cls}`;
    badge.textContent = status || '-';
    return badge;
}

async function loadBatch() {
    const notice = document.getElementById('batch-notice');
    const tbody = document.getElementById('batch-tbody');
    notice.replaceChildren();
    
    const { instances, message } = await fetchBatchInstances();
    
    ['total', 'running', 'completed', 'failed'].forEach(key => {
        document.getElementById(`batch-${key}`).textContent = '-';
    });
    tbody.replaceChildren();
    
    const emptyRow = (text) => {
        const row = document.createElement('tr');
        const td = batchCell(row, text);
        td.colSpan = 7;
        td.className = 'text-center';
        tbody.appendChild(row);
    };
    
    if (instances === null) {
        const alert = document.createElement('div');
        alert.className = 'alert alert-warning';
        const p = document.createElement('p');
        p.textContent = message;
        alert.appendChild(p);
        notice.appendChild(alert);
        emptyRow('No batch information available');
        return;
    }
    
    const counts = countBatchInstances(instances);
    document.getElementById('batch-total').textContent = counts.total;
    document.getElementById('batch-running').textContent = counts.running;
    document.getElementById('batch-completed').textContent = counts.completed;
    document.getElementById('batch-failed').textContent = counts.failed;
    
    if (instances.length === 0) {
        emptyRow('No batch jobs have been submitted');
        return;
    }
    
    instances.forEach(instance => {
        const row = document.createElement('tr');
        batchCell(row, instance.instanceId);
        batchCell(row, instance.jobName);
        batchCell(row, instance.appName);
        batchCell(row, '').replaceChildren(batchStatusBadge(instance.batchStatus));
        batchCell(row, instance.submitter);
        batchCell(row, instance.lastUpdatedTime);
        const button = document.createElement('button');
        button.className = 'btn btn-sm btn-secondary';
        button.textContent = 'Executions';
        button.addEventListener('click', () => toggleBatchExecutions(instance.instanceId, row));
        batchCell(row, '').replaceChildren(button);
        tbody.appendChild(row);
    });
}

// Shows or hides the executions (with their steps) of one job instance under its row
async function toggleBatchExecutions(instanceId, row) {
    const existing = row.nextElementSibling;
    if (existing && existing.classList.contains('batch-executions')) {
        existing.remove();
        return;
    }
    
    const detailRow = document.createElement('tr');
    detailRow.className = 'batch-executions';
    const cell = batchCell(detailRow, 'Loading executions...');
    cell.colSpan = 7;
    row.after(detailRow);
    
    try {
        const response = await fetch(`${BATCH_API}/jobinstances/${encodeURIComponent(instanceId)}/jobexecutions`, {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const executions = await response.json();
        if (!Array.isArray(executions) || executions.length === 0) {
            cell.textContent = 'This job instance has no executions yet.';
            return;
        }
        const lines = executions.map(execution => {
            const steps = (execution.stepExecutions || [])
                .map(step => `${step.stepName}: ${step.batchStatus}${step.exitStatus ? ' (' + step.exitStatus + ')' : ''}`)
                .join(', ');
            return `Execution ${execution.executionId}: ${execution.batchStatus}`
                + ` (exit ${execution.exitStatus || '-'}),`
                + ` started ${execution.startTime || '-'}, ended ${execution.endTime || '-'}`
                + (steps ? `. Steps - ${steps}` : '');
        });
        cell.replaceChildren(...lines.map(line => {
            const div = document.createElement('div');
            div.textContent = line;
            return div;
        }));
    } catch (error) {
        cell.textContent = `Could not load executions: ${error.message}`;
    }
}

// Server operations
async function performGC() {
    try {
        await fetchAPI('/server/gc', 'POST');
        showSuccess('Garbage collection triggered successfully');
        setTimeout(loadDashboardData, 2000);
    } catch (error) {
        showError('Failed to trigger garbage collection');
    }
}

async function getThreadDump() {
    try {
        const response = await fetch(`${API_BASE}/server/threads`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (!response.ok) {
            const errorText = await response.text();
            console.error('Thread dump error:', response.status, errorText);
            throw new Error(`Failed to get thread dump: ${response.status}`);
        }
        
        const threadDump = await response.text();
        
        // Create a blob and download link
        const blob = new Blob([threadDump], { type: 'text/plain' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        
        // Generate filename with timestamp
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5);
        a.download = `thread-dump-${timestamp}.txt`;
        
        // Trigger download
        document.body.appendChild(a);
        a.click();
        
        // Cleanup
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
        
        showSuccess('Thread dump downloaded successfully');
    } catch (error) {
        console.error('Thread dump error:', error);
        showError('Failed to get thread dump: ' + error.message);
    }
}

// Logs
let currentLogFile = 'messages.log';
let availableLogFiles = [];

async function loadLogs() {
    try {
        // Load log file list first
        const logInfo = await fetchAPI('/logs/info');
        availableLogFiles = logInfo.availableLogs || [];
        updateLogFileSelector(logInfo);
        
        // Load default log (messages.log)
        await loadLogFile('messages.log');
    } catch (error) {
        console.error('Error loading logs:', error);
        const logContent = document.getElementById('log-content');
        logContent.textContent = 'Error loading logs: ' + error.message;
    }
}

function updateLogFileSelector(logInfo) {
    // Add log file selector if not exists
    const pageHeader = document.querySelector('#page-logs .page-header');
    
    // Remove existing selector if present
    const existingSelector = document.getElementById('log-selector-container');
    if (existingSelector) {
        existingSelector.remove();
    }
    
    const selectorDiv = document.createElement('div');
    selectorDiv.id = 'log-selector-container';
    selectorDiv.style.display = 'flex';
    selectorDiv.style.gap = '1rem';
    selectorDiv.style.alignItems = 'center';
    
    // Build options from available log files
    let options = '';
    if (availableLogFiles.length > 0) {
        availableLogFiles.forEach(file => {
            const selected = file === currentLogFile ? 'selected' : '';
            options += `<option value="${file}" ${selected}>${file}</option>`;
        });
    } else {
        options = '<option value="messages.log">messages.log</option><option value="console.log">console.log</option>';
    }
    
    selectorDiv.innerHTML = `
        <div style="display: flex; align-items: center; gap: 0.5rem;">
            <label style="font-weight: 500;">Log File:</label>
            <select id="log-selector" class="form-control" style="width: 250px; padding: 0.5rem; border: 1px solid #e0e0e0; border-radius: 4px;">
                ${options}
            </select>
        </div>
        <button class="btn btn-secondary" onclick="refreshLogs()">
            <i class="fas fa-sync-alt"></i> Refresh
        </button>
        <div style="color: var(--text-secondary); font-size: 0.875rem;">
            <i class="fas fa-folder"></i> ${logInfo.logDirectory || 'Loading...'}
        </div>
    `;
    
    pageHeader.appendChild(selectorDiv);
    
    // Add change event listener
    document.getElementById('log-selector').addEventListener('change', function() {
        currentLogFile = this.value;
        loadLogFile(currentLogFile);
    });
}

async function loadLogFile(fileName) {
    try {
        const logContent = document.getElementById('log-content');
        logContent.textContent = 'Loading logs...';
        
        // Determine endpoint based on file name
        let endpoint;
        if (fileName === 'messages.log') {
            endpoint = '/logs/messages';
        } else if (fileName === 'console.log') {
            endpoint = '/logs/console';
        } else {
            endpoint = `/logs/file/${encodeURIComponent(fileName)}`;
        }
        
        const response = await fetch(`${API_BASE}${endpoint}?lines=1000`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const logText = await response.text();
        logContent.textContent = logText;
        
        // Auto-scroll to bottom
        const logViewer = document.querySelector('.log-viewer');
        logViewer.scrollTop = logViewer.scrollHeight;
        
    } catch (error) {
        console.error('Error loading log file:', error);
        const logContent = document.getElementById('log-content');
        logContent.textContent = 'Error loading log file: ' + error.message;
    }
}

function refreshLogs() {
    loadLogFile(currentLogFile);
}


// Utility functions
async function fetchAPI(endpoint, method = 'GET', body = null) {
    const options = {
        method: method,
        headers: {
            'Content-Type': 'application/json'
        },
        credentials: 'same-origin' // Include session cookie
    };
    
    if (body) {
        options.body = JSON.stringify(body);
    }
    
    const response = await fetch(`${API_BASE}${endpoint}`, options);
    
    // Not signed in (or the session expired): Liberty answers with its login page
    if (response.status === 401 || (response.redirected && /\/login(-error)?\.html/.test(response.url))) {
        window.location.href = `${CONTEXT_ROOT}/`;
        return;
    }

    // Signed in, but not allowed (for example a reader-role user trying to change something)
    if (response.status === 403) {
        let message = 'You are not authorized to perform this operation';
        try {
            message = (await response.json()).error || message;
        } catch (parseError) {
            // keep the default message
        }
        throw new Error(message);
    }
    
    if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
    }
    
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
        return await response.json();
    }
    
    return await response.text();
}

function formatBytes(bytes) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
}

function formatUptime(milliseconds) {
    const seconds = Math.floor(milliseconds / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);
    
    if (days > 0) {
        return `${days}d ${hours % 24}h ${minutes % 60}m`;
    } else if (hours > 0) {
        return `${hours}h ${minutes % 60}m`;
    } else {
        return `${minutes}m ${seconds % 60}s`;
    }
}

function formatDateTime(dateTimeString) {
    if (!dateTimeString) return 'N/A';
    const date = new Date(dateTimeString);
    return date.toLocaleString();
}

function getStateBadgeClass(state) {
    switch(state) {
        case 'STARTED':
            return 'badge-success';
        case 'STOPPED':
            return 'badge-danger';
        case 'RESTARTING':
            return 'badge-warning';
        default:
            return 'badge-warning';
    }
}

function showSuccess(message) {
    alert('✓ ' + message);
}

function showError(message) {
    alert('✗ ' + message);
}

function refreshData() {
    loadPageData(currentPage);
    showSuccess('Data refreshed');
}

function toggleTheme() {
    const body = document.body;
    const themeIcon = document.querySelector('.btn-icon[onclick="toggleTheme()"] i');
    
    body.classList.toggle('dark-mode');
    
    // Update icon and settings
    if (body.classList.contains('dark-mode')) {
        themeIcon.classList.remove('fa-moon');
        themeIcon.classList.add('fa-sun');
        appSettings.theme = 'dark';
    } else {
        themeIcon.classList.remove('fa-sun');
        themeIcon.classList.add('fa-moon');
        appSettings.theme = 'light';
    }
    
    // Save to backend
    saveSettings();
}


// Auto-refresh management
let autoRefreshInterval = null;

function startAutoRefresh() {
    // Clear any existing interval
    if (autoRefreshInterval) {
        clearInterval(autoRefreshInterval);
    }
    
    // Only start if autoRefresh is enabled
    if (autoRefresh) {
        const intervalMs = (refreshInterval || 30) * 1000;
        autoRefreshInterval = setInterval(() => {
            if (currentPage === 'dashboard') {
                loadDashboardData();
            }
        }, intervalMs);
    }
}

function stopAutoRefresh() {
    if (autoRefreshInterval) {
        clearInterval(autoRefreshInterval);
        autoRefreshInterval = null;
    }
}

function toggleAutoRefresh() {
    autoRefresh = !autoRefresh;
    
    if (autoRefresh) {
        startAutoRefresh();
    } else {
        stopAutoRefresh();
    }
    
    // Note: Auto-refresh setting is not persisted (session-only)
}

// Made with Bob

// Metrics and Monitoring - Global Variables
let charts = {};
let metricsHistory = {
    memory: { labels: [], data: [] },
    cpu: { labels: [], data: [] },
    threads: { labels: [], data: [] },
    gc: { labels: [], data: [] }
};
const MAX_DATA_POINTS = 20;

function initializeCharts() {
    const chartConfig = {
        type: 'line',
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: {
                    display: false
                }
            },
            scales: {
                y: {
                    beginAtZero: true
                }
            },
            animation: {
                duration: 300
            }
        }
    };

    // Memory Chart
    charts.memory = new Chart(document.getElementById('memoryChart'), {
        ...chartConfig,
        data: {
            labels: [],
            datasets: [{
                label: 'Memory Usage (MB)',
                data: [],
                borderColor: '#0f62fe',
                backgroundColor: 'rgba(15, 98, 254, 0.1)',
                fill: true,
                tension: 0.4
            }]
        }
    });

    // CPU Chart
    charts.cpu = new Chart(document.getElementById('cpuChart'), {
        ...chartConfig,
        data: {
            labels: [],
            datasets: [{
                label: 'CPU Usage (%)',
                data: [],
                borderColor: '#24a148',
                backgroundColor: 'rgba(36, 161, 72, 0.1)',
                fill: true,
                tension: 0.4
            }]
        },
        options: {
            ...chartConfig.options,
            scales: {
                y: {
                    beginAtZero: true,
                    max: 100
                }
            }
        }
    });

    // Thread Chart
    charts.threads = new Chart(document.getElementById('threadChart'), {
        ...chartConfig,
        data: {
            labels: [],
            datasets: [{
                label: 'Thread Count',
                data: [],
                borderColor: '#8a3ffc',
                backgroundColor: 'rgba(138, 63, 252, 0.1)',
                fill: true,
                tension: 0.4
            }]
        }
    });

    // GC Chart
    charts.gc = new Chart(document.getElementById('gcChart'), {
        ...chartConfig,
        data: {
            labels: [],
            datasets: [{
                label: 'GC Count',
                data: [],
                borderColor: '#f1c21b',
                backgroundColor: 'rgba(241, 194, 27, 0.1)',
                fill: true,
                tension: 0.4
            }]
        }
    });
}

function updateCharts(metrics) {
    const timestamp = new Date().toLocaleTimeString();
    
    // Extract key metrics
    const memoryUsed = metrics.base['memory_usedHeap_bytes'] || 0;
    const memoryMB = (memoryUsed / (1024 * 1024)).toFixed(2);
    
    const cpuLoad = (metrics.base['cpu_processCpuLoad_percent'] || 0) * 100;
    const threadCount = metrics.base['thread_count'] || 0;
    const gcCount = metrics.base['gc_total'] || 0;
    
    // Update memory chart
    updateChartData(charts.memory, metricsHistory.memory, timestamp, memoryMB);
    
    // Update CPU chart
    updateChartData(charts.cpu, metricsHistory.cpu, timestamp, cpuLoad.toFixed(2));
    
    // Update thread chart
    updateChartData(charts.threads, metricsHistory.threads, timestamp, threadCount);
    
    // Update GC chart
    updateChartData(charts.gc, metricsHistory.gc, timestamp, gcCount);

    updateDynamicMetricCharts(metrics, timestamp);
}

function updateChartData(chart, history, label, value) {
    // Add new data
    history.labels.push(label);
    history.data.push(value);
    
    // Keep only last MAX_DATA_POINTS
    if (history.labels.length > MAX_DATA_POINTS) {
        history.labels.shift();
        history.data.shift();
    }
    
    // Update chart
    chart.data.labels = history.labels;
    chart.data.datasets[0].data = history.data;
    chart.update('none'); // Update without animation for smoother updates
}
// Metrics and Monitoring
let metricsRefreshInterval = null;
let dynamicMetricCharts = {};
let dynamicMetricHistory = {};
let availableDetailedMetrics = {};

async function loadMetrics() {
    try {
        // Initialize charts on first load
        const isFirstLoad = Object.keys(charts).length === 0;
        if (isFirstLoad) {
            initializeCharts();
        }
        
        const metricsText = await fetchMetricsText();
        const metrics = parsePrometheusMetrics(metricsText);
        
        // Update charts with new data
        updateCharts(metrics);
        
        // Display detailed metrics
        displayMetrics(metrics);
        
    } catch (error) {
        console.error('Error loading metrics:', error);
        const metricsContainer = document.querySelector('#metrics-details');
        if (metricsContainer) {
            metricsContainer.innerHTML = `
                <div class="alert alert-warning">
                    <p><strong>Unable to load metrics</strong></p>
                    <p>${error.message}</p>
                </div>
            `;
        }
    }

    // Per-application metrics come from the console API (Liberty's monitoring
    // MBeans), so they don't depend on the /metrics endpoint
    await loadApplicationMetrics();
}

// Reads the MicroProfile Metrics endpoint (Prometheus text format). The request
// carries the console sign-in (LTPA cookie), which /metrics accepts for users in
// <administrator-role> or <reader-role>, so it doesn't need authentication="false".
async function fetchMetricsText() {
    const response = await fetch('/metrics', {
        headers: { 'Accept': 'text/plain' },
        credentials: 'same-origin'
    });
    if (response.status === 404) {
        throw new Error('MicroProfile Metrics is not enabled. Add mpMetrics-5.1 to the featureManager in server.xml.');
    }
    if (response.status === 401 || response.status === 403) {
        throw new Error('The /metrics endpoint refused your sign-in. Your user needs to be in the administrator-role or reader-role in server.xml.');
    }
    if (!response.ok) {
        throw new Error(`The /metrics endpoint answered with HTTP ${response.status}.`);
    }
    return response.text();
}

async function loadApplicationMetrics() {
    try {
        const appMetrics = await fetchAPI('/server/metrics/applications');
        console.log('Application metrics response:', appMetrics);
        displayApplicationMetrics(appMetrics);
    } catch (error) {
        console.error('Error loading application metrics:', error);
        const debug = document.getElementById('app-metrics-debug');
        const tbody = document.getElementById('app-metrics-tbody');
        if (debug) {
            debug.textContent = `Application metrics error: ${error.message}`;
        }
        if (tbody) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="7" class="text-center" style="color: var(--danger-color);">
                        Error loading application metrics: ${error.message}
                    </td>
                </tr>
            `;
        }
    }
}

function displayApplicationMetrics(appMetrics) {
    const tbody = document.getElementById('app-metrics-tbody');
    if (!tbody) return;
    
    if (!Array.isArray(appMetrics) || appMetrics.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="7" class="text-center">No application metrics available</td>
            </tr>
        `;
        return;
    }
    
    const rows = appMetrics.map(app => {
        const applicationName = app?.applicationName ?? 'N/A';
        const status = app?.status ?? 'UNKNOWN';
        const requestCount = Number(app?.requestCount ?? 0);
        const averageResponseTime = Number(app?.averageResponseTime ?? 0);
        const activeRequests = Number(app?.activeRequests ?? 0);
        const errorCount = Number(app?.errorCount ?? 0);
        const requestRate = Number(app?.requestRate ?? 0);
        const statusBadgeClass = status === 'STARTED' ? 'badge-success' : 'badge-warning';
        
        return `<tr>
            <td><strong>${applicationName}</strong></td>
            <td><span class="badge ${statusBadgeClass}">${status}</span></td>
            <td>${requestCount.toLocaleString()}</td>
            <td>${averageResponseTime.toFixed(2)} ms</td>
            <td>${activeRequests}</td>
            <td>${errorCount}</td>
            <td>${requestRate.toFixed(3)} req/s</td>
        </tr>`;
    }).join('');
    
    tbody.innerHTML = rows;
}

function parsePrometheusMetrics(text) {
    const metrics = {
        base: {},
        vendor: {},
        application: {}
    };
    
    const lines = text.split('\n');
    
    for (const line of lines) {
        if (line.startsWith('#') || line.trim() === '') {
            continue;
        }
        
        const match = line.match(/^([^\s{]+)(?:\{([^}]*)\})?\s+(.+)$/);
        if (!match) {
            continue;
        }
        
        let [, name, labels = '', value] = match;
        const numValue = parseFloat(value);
        const parsedValue = isNaN(numValue) ? value : numValue;
        const scope = extractMetricScope(name, labels);
        const normalizedName = normalizeMetricName(name);
        const labelSuffix = formatMetricLabels(labels);
        const metricKey = labelSuffix ? `${normalizedName} ${labelSuffix}` : normalizedName;
        
        metrics[scope][metricKey] = parsedValue;
    }
    
    return metrics;
}
function flattenDetailedMetrics(metrics) {
    const flattened = {};
    ['base', 'vendor', 'application'].forEach(scope => {
        const scopedMetrics = metrics[scope] || {};
        Object.entries(scopedMetrics).forEach(([key, value]) => {
            if (typeof value === 'number' && Number.isFinite(value)) {
                flattened[`${scope}.${key}`] = {
                    label: `${scope.toUpperCase()} › ${key}`,
                    value: value
                };
            }
        });
    });
    return flattened;
}

function updateDynamicMetricSelector(metrics) {
    const select = document.getElementById('dynamic-metric-select');
    if (!select) return;

    availableDetailedMetrics = flattenDetailedMetrics(metrics);
    const currentValue = select.value;

    select.innerHTML = '<option value="">Choose a metric...</option>' +
        Object.entries(availableDetailedMetrics)
            .sort((a, b) => a[1].label.localeCompare(b[1].label))
            .map(([key, metric]) => `<option value="${key}">${metric.label}</option>`)
            .join('');

    if (currentValue && availableDetailedMetrics[currentValue]) {
        select.value = currentValue;
    }
}

function addSelectedMetricChart() {
    const select = document.getElementById('dynamic-metric-select');
    if (!select || !select.value || !availableDetailedMetrics[select.value]) {
        return;
    }

    if (dynamicMetricCharts[select.value]) {
        return;
    }

    const container = document.getElementById('dynamic-charts-container');
    if (!container) return;

    if (container.querySelector('.text-muted')) {
        container.innerHTML = '';
    }

    const metric = availableDetailedMetrics[select.value];
    const chartId = `dynamic-chart-${select.value.replace(/[^a-zA-Z0-9_-]/g, '-')}`;

    const chartCard = document.createElement('div');
    chartCard.className = 'card dynamic-chart-card';
    chartCard.innerHTML = `
        <div class="card-header">
            <h3>${metric.label}</h3>
            <button class="btn btn-secondary btn-sm" onclick="removeDynamicMetricChart('${select.value}')">
                <i class="fas fa-times"></i> Remove
            </button>
        </div>
        <div class="card-body">
            <canvas id="${chartId}"></canvas>
        </div>
    `;
    container.appendChild(chartCard);

    dynamicMetricHistory[select.value] = { labels: [], data: [] };
    dynamicMetricCharts[select.value] = new Chart(document.getElementById(chartId), {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: metric.label,
                data: [],
                borderColor: '#0f62fe',
                backgroundColor: 'rgba(15, 98, 254, 0.1)',
                fill: true,
                tension: 0.4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            animation: { duration: 300 },
            scales: {
                y: {
                    beginAtZero: true
                }
            }
        }
    });
    
    // Save to settings
    if (!appSettings.dynamicCharts.includes(select.value)) {
        appSettings.dynamicCharts.push(select.value);
        saveSettings();
    }
}

function updateDynamicMetricCharts(metrics, timestamp) {
    const flattened = flattenDetailedMetrics(metrics);
    Object.keys(dynamicMetricCharts).forEach(metricKey => {
        const chart = dynamicMetricCharts[metricKey];
        const history = dynamicMetricHistory[metricKey];
        const metric = flattened[metricKey];
        const value = metric ? metric.value : 0;
        updateChartData(chart, history, timestamp, value);
    });
}

function removeDynamicMetricChart(metricKey) {
    const chart = dynamicMetricCharts[metricKey];
    if (chart) {
        chart.destroy();
        delete dynamicMetricCharts[metricKey];
    }

    delete dynamicMetricHistory[metricKey];

    const chartId = `dynamic-chart-${metricKey.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
    const canvas = document.getElementById(chartId);
    const card = canvas ? canvas.closest('.dynamic-chart-card') : null;
    if (card) {
        card.remove();
    }
    
    // Remove from settings and save
    const index = appSettings.dynamicCharts.indexOf(metricKey);
    if (index > -1) {
        appSettings.dynamicCharts.splice(index, 1);
        saveSettings();
    }

    const container = document.getElementById('dynamic-charts-container');
    if (container && container.children.length === 0) {
        container.innerHTML = '<p class="text-muted">Select metrics from Detailed Metrics to create charts.</p>';
    }
}

function extractMetricScope(name, labels) {
    if (labels.includes('mp_scope="vendor"') || name.startsWith('vendor_') || name.startsWith('http_server_') || name.startsWith('servlet_')) {
        return 'vendor';
    }
    
    if (labels.includes('mp_scope="application"') || name.startsWith('application_')) {
        return 'application';
    }
    
    if (labels.includes('mp_scope="base"') || name.startsWith('base_') || name.startsWith('REST_')) {
        return 'base';
    }
    
    return 'vendor';
}

function normalizeMetricName(name) {
    return name
        .replace(/^vendor_/, '')
        .replace(/^application_/, '')
        .replace(/^base_/, '');
}

function formatMetricLabels(labels) {
    if (!labels) {
        return '';
    }
    
    const importantLabels = [];
    const labelPatterns = ['http_route', 'http_response_status_code', 'http_request_method', 'class', 'method', 'servlet'];
    
    labelPatterns.forEach(labelName => {
        const match = labels.match(new RegExp(`${labelName}="([^"]*)"`));
        if (match && match[1]) {
            importantLabels.push(`${labelName}: ${match[1]}`);
        }
    });
    
    return importantLabels.length > 0 ? `(${importantLabels.join(', ')})` : '';
}

function displayMetrics(metrics) {
    const metricsContainer = document.querySelector('#metrics-details');
    
    // Create metrics display
    let html = '<div class="metrics-grid">';
    
    // Base metrics
    if (metrics.base) {
        html += '<div class="metrics-section">';
        html += '<h4><i class="fas fa-server"></i> Base Metrics</h4>';
        html += '<div class="metrics-list">';
        
        for (const [key, value] of Object.entries(metrics.base)) {
            html += formatMetric(key, value);
        }
        
        html += '</div></div>';
    }
    
    // Vendor metrics
    if (metrics.vendor) {
        html += '<div class="metrics-section">';
        html += '<h4><i class="fas fa-chart-bar"></i> Vendor Metrics</h4>';
        html += '<div class="metrics-list">';
        
        for (const [key, value] of Object.entries(metrics.vendor)) {
            html += formatMetric(key, value);
        }
        
        html += '</div></div>';
    }
    
    // Application metrics
    html += '<div class="metrics-section">';
    html += '<h4><i class="fas fa-cube"></i> Application Metrics</h4>';
    html += '<div class="metrics-list">';
    
    const applicationEntries = Object.entries(metrics.application || {});
    if (applicationEntries.length === 0) {
        html += `
            <div class="metric-item">
                <span class="metric-label">No application-scope metrics exposed by /metrics</span>
                <span class="metric-value">N/A</span>
            </div>
        `;
    } else {
        for (const [key, value] of applicationEntries) {
            html += formatMetric(key, value);
        }
    }
    
    html += '</div></div>';
    
    html += '</div>';
    
    // Add refresh controls
    html += `
        <div style="margin-top: 2rem; padding-top: 1rem; border-top: 1px solid var(--border-color);">
            <button class="btn btn-primary" onclick="loadMetrics()">
                <i class="fas fa-sync-alt"></i> Refresh Metrics
            </button>
        </div>
    `;
    
    metricsContainer.innerHTML = html;
    updateDynamicMetricSelector(metrics);
}

function formatMetric(key, value) {
    let displayValue = value;
    
    if (typeof value === 'object' && value !== null) {
        // Handle complex metric objects
        if (value.count !== undefined) {
            displayValue = value.count;
        } else if (value.value !== undefined) {
            displayValue = value.value;
        } else {
            displayValue = JSON.stringify(value);
        }
    }
    
    // Format large numbers
    if (typeof displayValue === 'number') {
        if (displayValue > 1000000) {
            displayValue = (displayValue / 1000000).toFixed(2) + 'M';
        } else if (displayValue > 1000) {
            displayValue = (displayValue / 1000).toFixed(2) + 'K';
        } else {
            displayValue = displayValue.toFixed(2);
        }
    }
    
    // Clean up metric name
    const cleanKey = key.replace(/_/g, ' ').replace(/\./g, ' › ');
    
    return `
        <div class="metric-item">
            <span class="metric-label">${cleanKey}</span>
            <span class="metric-value">${displayValue}</span>
        </div>
    `;
}

function startMetricsRefresh() {
    // Clear existing interval
    if (metricsRefreshInterval) {
        clearInterval(metricsRefreshInterval);
        metricsRefreshInterval = null;
    }
    
    // Start new interval only if auto-refresh is enabled
    if (appSettings.autoRefreshMetrics) {
        metricsRefreshInterval = setInterval(() => {
            // Only refresh data, don't call applySettings again
            loadMetricsData();
        }, 10000); // Refresh every 10 seconds
    }
}

// Helper function to refresh metrics data without reapplying settings
async function loadMetricsData() {
    try {
        const metricsText = await fetchMetricsText();
        const metrics = parsePrometheusMetrics(metricsText);
        
        // Update charts with new data
        updateCharts(metrics);
        
        // Display detailed metrics
        displayMetrics(metrics);
        
    } catch (error) {
        console.error('Error refreshing metrics:', error);
    }

    // Per-application metrics don't depend on /metrics
    await loadApplicationMetrics();
}

function toggleMetricsAutoRefresh() {
    const autoRefreshCheckbox = document.getElementById('auto-refresh-metrics');

    appSettings.autoRefreshMetrics = autoRefreshCheckbox.checked;
    saveSettings();

    if (autoRefreshCheckbox.checked) {
        startMetricsRefresh();
    } else {
        if (metricsRefreshInterval) {
            clearInterval(metricsRefreshInterval);
            metricsRefreshInterval = null;
        }
    }
}


// Configuration Management
async function loadConfiguration() {
    try {
        // Load the list of configuration files
        await loadConfigurationFileList();
        
        // Load the default file (server.xml)
        const selector = document.getElementById('config-file-selector');
        if (selector && selector.value) {
            await loadSelectedConfigFile();
        }
    } catch (error) {
        console.error('Error loading configuration:', error);
        showError('Failed to load configuration');
    }
}

async function loadConfigurationFileList() {
    try {
        const selector = document.getElementById('config-file-selector');
        if (!selector) return;
        
        // Fetch list of configuration files
        const files = await fetchAPI('/configuration/files');
        
        // Clear and populate selector
        selector.innerHTML = '';
        
        if (files && files.length > 0) {
            files.forEach(file => {
                const option = document.createElement('option');
                option.value = file;
                option.textContent = file;
                selector.appendChild(option);
            });
            
            // Select server.xml by default if it exists
            const serverXmlOption = files.find(f => f === 'server.xml');
            if (serverXmlOption) {
                selector.value = serverXmlOption;
            }
        } else {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = 'No configuration files found';
            selector.appendChild(option);
        }
    } catch (error) {
        console.error('Error loading file list:', error);
        const selector = document.getElementById('config-file-selector');
        if (selector) {
            selector.innerHTML = '<option value="">Error loading files</option>';
        }
    }
}

async function loadSelectedConfigFile() {
    try {
        const selector = document.getElementById('config-file-selector');
        const editor = document.getElementById('config-editor');
        const status = document.getElementById('editor-status');
        const filePath = document.getElementById('config-file-path');
        
        if (!selector || !editor) return;
        
        const selectedFile = selector.value;
        if (!selectedFile) {
            editor.value = '';
            status.textContent = 'No file selected';
            filePath.textContent = '';
            return;
        }
        
        status.textContent = 'Loading file...';
        
        // Get file content
        const response = await fetch(`${API_BASE}/configuration/file?path=${encodeURIComponent(selectedFile)}`, {
            headers: {
                // Session cookie used for authentication
            }
        });
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const content = await response.text();
        editor.value = content;
        
        status.textContent = `Loaded ${content.split('\n').length} lines`;
        filePath.textContent = selectedFile;
        
    } catch (error) {
        console.error('Error loading file:', error);
        const status = document.getElementById('editor-status');
        if (status) {
            status.textContent = 'Error loading file: ' + error.message;
        }
        showError('Failed to load file: ' + error.message);
    }
}

async function saveConfiguration() {
    try {
        const selector = document.getElementById('config-file-selector');
        const editor = document.getElementById('config-editor');
        const status = document.getElementById('editor-status');
        
        if (!selector || !editor) return;
        
        const selectedFile = selector.value;
        if (!selectedFile) {
            showError('No file selected');
            return;
        }
        
        const content = editor.value;
        
        if (!content.trim()) {
            showError('File content cannot be empty');
            return;
        }
        
        // Confirm save
        if (!confirm(`Save changes to ${selectedFile}?\n\nA backup will be created automatically.\nServer restart may be required for changes to take effect.`)) {
            return;
        }
        
        status.textContent = 'Saving file...';
        
        const response = await fetch(`${API_BASE}/configuration/file?path=${encodeURIComponent(selectedFile)}`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'text/plain',
                // Session cookie used for authentication
            },
            body: content
        });
        
        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to save file');
        }
        
        const result = await response.json();
        status.textContent = 'File saved successfully';
        showSuccess(result.message + '\n\n' + result.note);
        
    } catch (error) {
        console.error('Error saving file:', error);
        const status = document.getElementById('editor-status');
        if (status) {
            status.textContent = 'Error: ' + error.message;
        }
        showError('Failed to save file: ' + error.message);
    }
}
