// Sherlock Electron Renderer Logic

document.addEventListener('DOMContentLoaded', async () => {
  let allCases = [];
  let currentCaseDetails = null;

  // UI Elements
  const caseSelect = document.getElementById('caseSelect');
  const engineStatusBadge = document.getElementById('engineStatusBadge');
  const refreshBtn = document.getElementById('refreshBtn');
  const navItems = document.querySelectorAll('.nav-item');
  const tabViews = document.querySelectorAll('.tab-view');

  // Metrics
  const statEvidenceFiles = document.getElementById('statEvidenceFiles');
  const statWarehouse = document.getElementById('statWarehouse');
  const statProcessed = document.getElementById('statProcessed');
  const statPythonVer = document.getElementById('statPythonVer');
  const statPythonSub = document.getElementById('statPythonSub');
  const evidenceCountBadge = document.getElementById('evidenceCountBadge');
  const evidenceBadge = document.getElementById('evidenceBadge');
  const activeCaseTitle = document.getElementById('activeCaseTitle');
  const activeCaseSubtitle = document.getElementById('activeCaseSubtitle');

  // Lists & Tables
  const quickEvidenceList = document.getElementById('quickEvidenceList');
  const evidenceTableBody = document.getElementById('evidenceTableBody');
  const evidenceSearchInput = document.getElementById('evidenceSearchInput');

  // Status Bar
  const statusPlatform = document.getElementById('statusPlatform');
  const statusActivePath = document.getElementById('statusActivePath');
  const statusPython = document.getElementById('statusPython');

  // Setup Tab Navigation
  navItems.forEach(item => {
    item.addEventListener('click', () => {
      const targetTab = item.getAttribute('data-tab');

      navItems.forEach(i => i.classList.remove('active'));
      tabViews.forEach(v => v.classList.remove('active'));

      item.classList.add('active');
      const targetView = document.getElementById(`tab-${targetTab}`);
      if (targetView) {
        targetView.classList.add('active');
      }
    });
  });

  // Load System & Python Status
  async function checkSystem() {
    try {
      if (!window.sherlockAPI) {
        engineStatusBadge.className = 'status-badge offline';
        engineStatusBadge.querySelector('.status-label').textContent = 'API Bridge Unavailable';
        return;
      }

      const status = await window.sherlockAPI.getSystemStatus();
      if (status.pythonAvailable) {
        engineStatusBadge.className = 'status-badge online';
        engineStatusBadge.querySelector('.status-label').textContent = `Engine Ready (${status.pythonVersion})`;
        statPythonVer.textContent = 'Active';
        statPythonSub.textContent = status.pythonVersion;
        statusPython.textContent = status.pythonVersion;
      } else {
        engineStatusBadge.className = 'status-badge offline';
        engineStatusBadge.querySelector('.status-label').textContent = 'Python Missing';
        statPythonVer.textContent = 'Offline';
        statPythonSub.textContent = 'Python not found in PATH';
        statusPython.textContent = 'Not detected';
        statusPython.className = 'text-rose';
      }

      statusPlatform.textContent = `Platform: ${status.platform || 'Desktop'}`;
    } catch (err) {
      console.error('System check failed:', err);
      engineStatusBadge.className = 'status-badge offline';
      engineStatusBadge.querySelector('.status-label').textContent = 'System Check Failed';
    }
  }

  // Load Cases
  async function loadCases() {
    try {
      if (!window.sherlockAPI) return;

      allCases = await window.sherlockAPI.listCases();
      caseSelect.innerHTML = '';

      if (allCases.length === 0) {
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = 'No cases found in data/';
        caseSelect.appendChild(opt);
        return;
      }

      allCases.forEach(c => {
        const opt = document.createElement('option');
        opt.value = c.id;
        opt.textContent = `${c.name} (${c.totalFiles} files)`;
        caseSelect.appendChild(opt);
      });

      // Default select case_rose_001 if available
      const preferred = allCases.find(c => c.id.includes('rose')) || allCases[0];
      caseSelect.value = preferred.id;
      await selectCase(preferred.id);
    } catch (err) {
      console.error('Failed to load cases:', err);
    }
  }

  // Select and Render a Case
  async function selectCase(caseId) {
    if (!caseId) return;
    const caseSummary = allCases.find(c => c.id === caseId);

    activeCaseTitle.textContent = caseSummary ? caseSummary.name : caseId.toUpperCase();
    statusActivePath.textContent = `Active Case: data/${caseId}`;

    currentCaseDetails = await window.sherlockAPI.getCaseDetails(caseId);
    if (!currentCaseDetails) return;

    renderCaseMetrics(caseSummary, currentCaseDetails);
    renderEvidence(currentCaseDetails.files);
  }

  function renderCaseMetrics(summary, details) {
    const rawFiles = details.files.filter(f => f.name.endsWith('.txt'));
    statEvidenceFiles.textContent = rawFiles.length;
    evidenceCountBadge.textContent = rawFiles.length;
    evidenceBadge.textContent = `${rawFiles.length} Evidence Sources`;

    const hasWarehouse = details.files.some(f => f.name === 'warehouse.txt');
    statWarehouse.textContent = hasWarehouse ? 'Generated' : 'Unbuilt';

    const hasProcessed = details.files.some(f => f.name === 'processed');
    statProcessed.textContent = hasProcessed ? 'Materialized' : 'Ready for Run';
  }

  function getFileCategory(name) {
    const lower = name.toLowerCase();
    if (lower.includes('fir') || lower.includes('scene') || lower.includes('postmortem') || lower.includes('police')) {
      return { tag: 'Police / Forensics', cls: 'police' };
    }
    if (lower.includes('witness') || lower.includes('interview')) {
      return { tag: 'Witness & Suspect', cls: 'witness' };
    }
    if (lower.includes('cctv') || lower.includes('call_log') || lower.includes('phone') || lower.includes('digital') || lower.includes('financial')) {
      return { tag: 'Digital Forensics', cls: 'forensic' };
    }
    return { tag: 'Case Meta / Summary', cls: 'meta' };
  }

  function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    const kb = (bytes / 1024).toFixed(1);
    return kb + ' KB';
  }

  function renderEvidence(files) {
    // Render Quick List on Overview tab
    quickEvidenceList.innerHTML = '';
    const sorted = [...files].sort((a, b) => a.name.localeCompare(b.name));

    sorted.slice(0, 7).forEach(file => {
      const item = document.createElement('div');
      item.className = 'file-mini-item';
      item.innerHTML = `
        <span class="file-mini-name">📄 ${file.name}</span>
        <span class="file-mini-size">${formatBytes(file.size)}</span>
      `;
      quickEvidenceList.appendChild(item);
    });

    // Render Full Table on Evidence Tab
    renderEvidenceTable(sorted);
  }

  function renderEvidenceTable(files) {
    evidenceTableBody.innerHTML = '';

    files.forEach(file => {
      const cat = getFileCategory(file.name);
      const row = document.createElement('tr');
      const ext = file.name.split('.').pop().toUpperCase();
      const dateStr = new Date(file.modified).toLocaleDateString();

      row.innerHTML = `
        <td class="file-row-name">📄 ${file.name}</td>
        <td><span class="category-tag ${cat.cls}">${cat.tag}</span></td>
        <td>${formatBytes(file.size)}</td>
        <td><code>${ext}</code></td>
        <td>${dateStr}</td>
      `;
      evidenceTableBody.appendChild(row);
    });
  }

  // Filter Evidence Table
  evidenceSearchInput.addEventListener('input', (e) => {
    if (!currentCaseDetails) return;
    const query = e.target.value.toLowerCase();
    const filtered = currentCaseDetails.files.filter(f => f.name.toLowerCase().includes(query));
    renderEvidenceTable(filtered);
  });

  // Case Dropdown Change
  caseSelect.addEventListener('change', (e) => {
    selectCase(e.target.value);
  });

  // Refresh Button
  refreshBtn.addEventListener('click', async () => {
    refreshBtn.style.transform = 'rotate(180deg)';
    await checkSystem();
    await loadCases();
    setTimeout(() => { refreshBtn.style.transform = 'none'; }, 300);
  });

  // Trigger Action Buttons
  document.getElementById('runPipelineBtn').addEventListener('click', () => {
    alert(`To run the Python pipeline on ${caseSelect.value}:\n\npython main.py --project data/${caseSelect.value} --extract --timeline --contradictions`);
  });

  document.getElementById('openDataFolderBtn').addEventListener('click', () => {
    alert(`Case data is located in:\ndata/${caseSelect.value}`);
  });

  // Initial Boot
  await checkSystem();
  await loadCases();
});
