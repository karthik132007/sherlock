// Sherlock Electron Renderer Logic — Full Interactive Investigation Engine

document.addEventListener('DOMContentLoaded', async () => {
  // Global State
  let allCases = [];
  let currentCaseId = null;
  let currentCaseDetails = null;
  let currentProcessed = null;
  let activeLogListener = null;
  let activeDoneListener = null;

  // Graph Simulation State
  let graphData = { nodes: [], links: [] };
  let filteredNodes = [];
  let filteredLinks = [];
  let activeFilterType = 'ALL';
  let selectedNode = null;
  let hoveredNode = null;
  let transform = { x: 0, y: 0, k: 1 };
  let isDragging = false;
  let dragNode = null;
  let lastMouse = { x: 0, y: 0 };
  let simulationAnimFrame = null;

  // =========================================================================
  // DOM Elements
  // =========================================================================
  const caseSelect = document.getElementById('caseSelect');
  const newCaseBtn = document.getElementById('newCaseBtn');
  const engineStatusBadge = document.getElementById('engineStatusBadge');
  const refreshBtn = document.getElementById('refreshBtn');
  const settingsBtn = document.getElementById('settingsBtn');
  const navItems = document.querySelectorAll('.nav-item');
  const tabViews = document.querySelectorAll('.tab-view');

  // Overview Elements
  const activeCaseTitle = document.getElementById('activeCaseTitle');
  const activeCaseSubtitle = document.getElementById('activeCaseSubtitle');
  const statEvidenceFiles = document.getElementById('statEvidenceFiles');
  const statWarehouse = document.getElementById('statWarehouse');
  const statProcessed = document.getElementById('statProcessed');
  const statProcessedDetails = document.getElementById('statProcessedDetails');
  const statPythonVer = document.getElementById('statPythonVer');
  const statPythonSub = document.getElementById('statPythonSub');
  const evidenceCountBadge = document.getElementById('evidenceCountBadge');
  const graphCountBadge = document.getElementById('graphCountBadge');
  const timelineCountBadge = document.getElementById('timelineCountBadge');
  const contraCountBadge = document.getElementById('contraCountBadge');
  const quickEvidenceList = document.getElementById('quickEvidenceList');
  const evidenceBadge = document.getElementById('evidenceBadge');

  // Overview Action Buttons
  const openDataFolderBtn = document.getElementById('openDataFolderBtn');
  const buildWarehouseBtn = document.getElementById('buildWarehouseBtn');
  const buildWarehouseBtn2 = document.getElementById('buildWarehouseBtn2');
  const bootstrapDataBtn = document.getElementById('bootstrapDataBtn');
  const openPipelineModalBtn = document.getElementById('openPipelineModalBtn');
  const quickRunPipelineBtn = document.getElementById('quickRunPipelineBtn');

  // Evidence Tab
  const evidenceSearchInput = document.getElementById('evidenceSearchInput');
  const evidenceTableBody = document.getElementById('evidenceTableBody');
  const addEvidenceBtn = document.getElementById('addEvidenceBtn');

  // Graph Tab
  const graphCanvas = document.getElementById('graphCanvas');
  const canvasWrapper = document.getElementById('canvasWrapper');
  const graphSearchInput = document.getElementById('graphSearchInput');
  const graphFitBtn = document.getElementById('graphFitBtn');
  const graphResetBtn = document.getElementById('graphResetBtn');
  const graphEmptyOverlay = document.getElementById('graphEmptyOverlay');
  const graphExtractBtn = document.getElementById('graphExtractBtn');
  const nodeInspector = document.getElementById('nodeInspector');
  const closeInspectorBtn = document.getElementById('closeInspectorBtn');
  const inspectTypeBadge = document.getElementById('inspectTypeBadge');
  const inspectNodeName = document.getElementById('inspectNodeName');
  const inspectAliases = document.getElementById('inspectAliases');
  const inspectAttributes = document.getElementById('inspectAttributes');
  const inspectRelationships = document.getElementById('inspectRelationships');
  const inspectSources = document.getElementById('inspectSources');
  const filterPills = document.querySelectorAll('.filter-pill');

  // Timeline Tab
  const timelineList = document.getElementById('timelineList');
  const timelineEmpty = document.getElementById('timelineEmpty');
  const timelineSearchInput = document.getElementById('timelineSearchInput');
  const extractTimelineBtn = document.getElementById('extractTimelineBtn');

  // Contradictions Tab
  const contraList = document.getElementById('contraList');
  const contraEmpty = document.getElementById('contraEmpty');
  const contraSearchInput = document.getElementById('contraSearchInput');
  const detectContraBtn = document.getElementById('detectContraBtn');

  // Sherlock Opinion & Chat Tab
  const opTheoryTitle = document.getElementById('opTheoryTitle');
  const opConfidenceScore = document.getElementById('opConfidenceScore');
  const opResponsible = document.getElementById('opResponsible');
  const opClassification = document.getElementById('opClassification');
  const opSupportingList = document.getElementById('opSupportingList');
  const opCounterList = document.getElementById('opCounterList');
  const opLeadsList = document.getElementById('opLeadsList');
  const generateOpinionBtn = document.getElementById('generateOpinionBtn');
  const chatMessages = document.getElementById('chatMessages');
  const chatForm = document.getElementById('chatForm');
  const chatInput = document.getElementById('chatInput');
  const promptChips = document.querySelectorAll('.prompt-chip');

  // Pipeline Modal Elements
  const pipelineModal = document.getElementById('pipelineModal');
  const closePipelineModalBtn = document.getElementById('closePipelineModalBtn');
  const startPipelineExecutionBtn = document.getElementById('startPipelineExecutionBtn');
  const cancelPipelineBtn = document.getElementById('cancelPipelineBtn');
  const terminalOutput = document.getElementById('terminalOutput');
  const pipelineStatusTag = document.getElementById('pipelineStatusTag');
  const chkExtract = document.getElementById('chkExtract');
  const chkTimeline = document.getElementById('chkTimeline');
  const chkContra = document.getElementById('chkContra');
  const chkOpinion = document.getElementById('chkOpinion');
  const chkSingleCall = document.getElementById('chkSingleCall');
  const batchSizeInput = document.getElementById('batchSizeInput');

  // File Viewer Modal
  const fileViewerModal = document.getElementById('fileViewerModal');
  const closeFileViewerBtn = document.getElementById('closeFileViewerBtn');
  const closeFileViewerBtn2 = document.getElementById('closeFileViewerBtn2');
  const fileViewerTitle = document.getElementById('fileViewerTitle');
  const fileViewerBadge = document.getElementById('fileViewerBadge');
  const fileViewerSize = document.getElementById('fileViewerSize');
  const fileViewerChars = document.getElementById('fileViewerChars');
  const fileViewerContent = document.getElementById('fileViewerContent');

  // Add Evidence Modal
  const addEvidenceModal = document.getElementById('addEvidenceModal');
  const closeAddEvidenceBtn = document.getElementById('closeAddEvidenceBtn');
  const cancelAddEvidenceBtn = document.getElementById('cancelAddEvidenceBtn');
  const saveEvidenceBtn = document.getElementById('saveEvidenceBtn');
  const newEvidenceFilename = document.getElementById('newEvidenceFilename');
  const newEvidenceContent = document.getElementById('newEvidenceContent');

  // New Case Modal
  const newCaseModal = document.getElementById('newCaseModal');
  const closeNewCaseBtn = document.getElementById('closeNewCaseBtn');
  const cancelNewCaseBtn = document.getElementById('cancelNewCaseBtn');
  const createCaseBtn = document.getElementById('createCaseBtn');
  const newCaseId = document.getElementById('newCaseId');
  const newCaseName = document.getElementById('newCaseName');

  // Settings Modal
  const settingsModal = document.getElementById('settingsModal');
  const closeSettingsBtn = document.getElementById('closeSettingsBtn');
  const cancelSettingsBtn = document.getElementById('cancelSettingsBtn');
  const saveSettingsBtn = document.getElementById('saveSettingsBtn');
  const cfgProvider = document.getElementById('cfgProvider');
  const cfgModel = document.getElementById('cfgModel');
  const cfgApiKey = document.getElementById('cfgApiKey');
  const cfgContextWindow = document.getElementById('cfgContextWindow');
  const cfgBaseUrl = document.getElementById('cfgBaseUrl');
  const cfgFeedback = document.getElementById('cfgFeedback');

  // Status Bar
  const statusPlatform = document.getElementById('statusPlatform');
  const statusActivePath = document.getElementById('statusActivePath');
  const statusPython = document.getElementById('statusPython');

  // =========================================================================
  // Navigation & Tabs
  // =========================================================================
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

      if (targetTab === 'graph') {
        resizeGraphCanvas();
        restartSimulation();
      }
    });
  });

  // =========================================================================
  // System Health & Python Status
  // =========================================================================
  async function checkSystem() {
    try {
      if (!window.sherlockAPI) {
        engineStatusBadge.className = 'status-badge offline';
        engineStatusBadge.querySelector('.status-label').textContent = 'API Bridge Missing';
        return;
      }

      const status = await window.sherlockAPI.getSystemStatus();
      if (status.pythonAvailable) {
        const venvLabel = status.isVenv ? ' (.venv)' : '';
        engineStatusBadge.className = 'status-badge online';
        engineStatusBadge.querySelector('.status-label').textContent = `Engine Active: ${status.pythonVersion}${venvLabel}`;
        statPythonVer.textContent = 'Ready';
        statPythonSub.textContent = `${status.pythonVersion}${venvLabel}`;
        statusPython.textContent = `${status.pythonVersion}${venvLabel}`;
      } else {
        engineStatusBadge.className = 'status-badge offline';
        engineStatusBadge.querySelector('.status-label').textContent = 'Python Engine Missing';
        statPythonVer.textContent = 'Offline';
        statPythonSub.textContent = 'Python not detected in .venv or PATH';
        statusPython.textContent = 'Not detected';
        statusPython.className = 'text-rose';
      }

      statusPlatform.textContent = `Platform: ${status.platform || 'Desktop'}`;
    } catch (err) {
      console.error('System check failed:', err);
    }
  }

  // =========================================================================
  // Case Loading & Selection
  // =========================================================================
  async function loadCases(targetId = null) {
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
        opt.textContent = `${c.name} (${c.evidenceCount} evidence files)`;
        caseSelect.appendChild(opt);
      });

      const selectedId = targetId || (allCases.find(c => c.id.includes('rose'))?.id || allCases[0].id);
      caseSelect.value = selectedId;
      await selectCase(selectedId);
    } catch (err) {
      console.error('Failed to load cases:', err);
    }
  }

  async function selectCase(caseId) {
    if (!caseId) return;
    currentCaseId = caseId;
    const caseSummary = allCases.find(c => c.id === caseId);

    activeCaseTitle.textContent = caseSummary ? caseSummary.name : caseId.toUpperCase();
    statusActivePath.textContent = `Active Case: data/${caseId}`;

    currentCaseDetails = await window.sherlockAPI.getCaseDetails(caseId);
    currentProcessed = await window.sherlockAPI.getProcessedData(caseId);

    renderCaseOverview(caseSummary, currentCaseDetails, currentProcessed);
    renderEvidenceVault(currentCaseDetails ? currentCaseDetails.files : []);
    renderKnowledgeGraph(currentProcessed);
    renderTimeline(currentProcessed);
    renderContradictions(currentProcessed);
    renderOpinion(currentProcessed);
  }

  function renderCaseOverview(summary, details, processed) {
    const rawFiles = (details?.files || []).filter(f => f.name.endsWith('.txt') && f.name !== 'warehouse.txt');
    statEvidenceFiles.textContent = rawFiles.length;
    evidenceCountBadge.textContent = rawFiles.length;
    evidenceBadge.textContent = `${rawFiles.length} Evidence Sources`;

    const hasWarehouse = (details?.files || []).some(f => f.name === 'warehouse.txt');
    statWarehouse.textContent = hasWarehouse ? 'Generated' : 'Unbuilt';

    // Stepper updates
    document.getElementById('step-warehouse').className = hasWarehouse ? 'step-item completed' : 'step-item';
    document.getElementById('step-chunks').className = processed?.chunks ? 'step-item completed' : 'step-item';
    document.getElementById('step-extract').className = processed?.entities ? 'step-item completed' : 'step-item';
    document.getElementById('step-analysis').className = (processed?.timeline && processed?.opinion) ? 'step-item completed' : 'step-item';

    const hasEntities = Boolean(processed?.entities?.entities?.length || processed?.graph?.entities?.length);
    if (hasEntities) {
      const entCount = processed?.entities?.entities?.length || processed?.graph?.entities?.length || 0;
      const relCount = processed?.graph?.graph?.length || processed?.relations?.relationships?.length || 0;
      statProcessed.textContent = `${entCount} Entities`;
      statProcessedDetails.textContent = `${relCount} Relationships mapped`;
    } else {
      statProcessed.textContent = processed?.chunks ? 'Chunked' : 'Ready for Run';
      statProcessedDetails.textContent = processed?.chunks ? `${processed.chunks.total_chunks} chunks indexed` : 'Run pipeline to extract facts';
    }

    // Quick list on overview
    quickEvidenceList.innerHTML = '';
    const sorted = [...rawFiles].sort((a, b) => a.name.localeCompare(b.name));
    sorted.slice(0, 8).forEach(file => {
      const item = document.createElement('div');
      item.className = 'file-mini-item';
      item.innerHTML = `
        <span class="file-mini-name">📄 ${file.name}</span>
        <span class="file-mini-size">${formatBytes(file.size)}</span>
      `;
      item.style.cursor = 'pointer';
      item.addEventListener('click', () => viewEvidenceFile(file.name));
      quickEvidenceList.appendChild(item);
    });
  }

  // =========================================================================
  // Evidence Vault (Tab 2)
  // =========================================================================
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
    return { tag: 'Investigation Record', cls: 'meta' };
  }

  function formatBytes(bytes) {
    if (!bytes && bytes !== 0) return '-';
    if (bytes < 1024) return bytes + ' B';
    const kb = (bytes / 1024).toFixed(1);
    return kb + ' KB';
  }

  function renderEvidenceVault(files) {
    evidenceTableBody.innerHTML = '';
    const rawFiles = files.filter(f => f.name !== 'warehouse.txt' && !f.name.endsWith('.pyc'));

    rawFiles.forEach(file => {
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
        <td>
          <button class="btn btn-sm btn-secondary view-evidence-btn">Inspect</button>
        </td>
      `;

      row.querySelector('.view-evidence-btn').addEventListener('click', () => {
        viewEvidenceFile(file.name);
      });

      evidenceTableBody.appendChild(row);
    });
  }

  async function viewEvidenceFile(filename) {
    if (!currentCaseId) return;
    const res = await window.sherlockAPI.readEvidence(currentCaseId, filename);
    if (!res.success) {
      alert(`Could not open file: ${res.error}`);
      return;
    }

    const cat = getFileCategory(filename);
    fileViewerTitle.textContent = filename;
    fileViewerBadge.textContent = cat.tag.toUpperCase();
    fileViewerBadge.className = `category-tag ${cat.cls}`;
    fileViewerSize.textContent = `Size: ${formatBytes(res.size)}`;
    fileViewerChars.textContent = `Chars: ${res.content.length.toLocaleString()}`;
    fileViewerContent.textContent = res.content;
    fileViewerModal.classList.remove('hidden');
  }

  evidenceSearchInput.addEventListener('input', (e) => {
    if (!currentCaseDetails) return;
    const query = e.target.value.toLowerCase();
    const filtered = currentCaseDetails.files.filter(f => f.name.toLowerCase().includes(query));
    renderEvidenceVault(filtered);
  });

  // Rebuild Warehouse
  async function triggerBuildWarehouse() {
    if (!currentCaseId) return;
    const btn = buildWarehouseBtn;
    btn.textContent = 'Building...';
    btn.disabled = true;

    const res = await window.sherlockAPI.buildWarehouse(currentCaseId);
    btn.disabled = false;
    btn.textContent = '📦 Rebuild Warehouse';

    if (res.success) {
      alert(`Warehouse generated successfully for ${currentCaseId}!\n\nSources Ingested: ${res.sourcesCount}\nTotal Characters: ${res.totalChars.toLocaleString()}`);
      await selectCase(currentCaseId);
    } else {
      alert(`Failed to build warehouse: ${res.error}`);
    }
  }

  buildWarehouseBtn.addEventListener('click', triggerBuildWarehouse);
  buildWarehouseBtn2.addEventListener('click', triggerBuildWarehouse);

  // Bootstrap Demo Data
  bootstrapDataBtn.addEventListener('click', async () => {
    if (!currentCaseId) return;
    bootstrapDataBtn.textContent = '✨ Materializing...';
    bootstrapDataBtn.disabled = true;

    // First ensure warehouse is built
    await window.sherlockAPI.buildWarehouse(currentCaseId);

    const res = await window.sherlockAPI.bootstrapCaseData(currentCaseId);
    bootstrapDataBtn.disabled = false;
    bootstrapDataBtn.textContent = '✨ Bootstrap Demo';

    if (res.success) {
      alert(`Demo Ground Truth successfully materialized into processed/!\n\n• Entities: ${res.entitiesCount}\n• Relationships: ${res.relationsCount}\n• Timeline Events: ${res.timelineCount}\n• Contradictions: ${res.contradictionsCount}`);
      await selectCase(currentCaseId);
    } else {
      alert(`Bootstrap notice: ${res.error}`);
    }
  });

  // =========================================================================
  // Interactive Knowledge Graph (Tab 3)
  // =========================================================================
  function getNodeColor(type) {
    switch (type) {
      case 'Person': return '#38bdf8';
      case 'Location': return '#10b981';
      case 'Document': return '#f59e0b';
      case 'Phone': return '#a855f7';
      case 'Organization': return '#6366f1';
      default: return '#94a3b8';
    }
  }

  function resizeGraphCanvas() {
    const rect = canvasWrapper.getBoundingClientRect();
    if (rect.width && rect.height) {
      graphCanvas.width = rect.width;
      graphCanvas.height = rect.height;
    }
  }

  window.addEventListener('resize', resizeGraphCanvas);

  function renderKnowledgeGraph(processed) {
    const rawEntities = processed?.graph?.entities || processed?.entities?.entities || [];
    const rawLinks = processed?.graph?.graph || processed?.relations?.relationships || [];

    graphCountBadge.textContent = rawEntities.length;

    if (rawEntities.length === 0) {
      graphEmptyOverlay.classList.remove('hidden');
      graphData = { nodes: [], links: [] };
      return;
    }

    graphEmptyOverlay.classList.add('hidden');

    // Build Simulation Nodes
    const rect = canvasWrapper.getBoundingClientRect();
    const cx = (rect.width || 800) / 2;
    const cy = (rect.height || 600) / 2;

    const nodes = rawEntities.map((e, idx) => {
      const angle = (idx / rawEntities.length) * 2 * Math.PI;
      const radius = 180 + (idx % 3) * 60;
      return {
        id: e.id || e.name,
        name: e.name,
        type: e.type || 'Entity',
        confidence: e.confidence || 0.9,
        aliases: e.aliases || [],
        data: e.data || {},
        source_files: e.source_files || [],
        x: cx + Math.cos(angle) * radius + (Math.random() - 0.5) * 40,
        y: cy + Math.sin(angle) * radius + (Math.random() - 0.5) * 40,
        vx: 0,
        vy: 0,
        radius: (e.type === 'Person' ? 22 : 18)
      };
    });

    // Build Simulation Links
    const links = [];
    rawLinks.forEach(rel => {
      const srcName = typeof rel.source === 'object' ? rel.source.name : rel.source;
      const tgtName = typeof rel.target === 'object' ? rel.target.name : rel.target;

      const sourceNode = nodes.find(n => n.name.toLowerCase() === (srcName || '').toLowerCase());
      const targetNode = nodes.find(n => n.name.toLowerCase() === (tgtName || '').toLowerCase());

      if (sourceNode && targetNode && sourceNode !== targetNode) {
        links.push({
          source: sourceNode,
          target: targetNode,
          relation: rel.relation || 'CONNECTED_TO',
          confidence: rel.confidence || 0.85,
          evidence_text: rel.evidence_text || ''
        });
      }
    });

    graphData = { nodes, links };
    applyGraphFilters();
    restartSimulation();
  }

  function applyGraphFilters() {
    if (activeFilterType === 'ALL') {
      filteredNodes = graphData.nodes;
    } else {
      filteredNodes = graphData.nodes.filter(n => n.type === activeFilterType);
    }

    const nodeSet = new Set(filteredNodes);
    filteredLinks = graphData.links.filter(l => nodeSet.has(l.source) && nodeSet.has(l.target));
  }

  filterPills.forEach(pill => {
    pill.addEventListener('click', () => {
      filterPills.forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      activeFilterType = pill.getAttribute('data-type');
      applyGraphFilters();
    });
  });

  // Physics Simulation Step
  function stepSimulation() {
    const nodes = filteredNodes;
    const links = filteredLinks;

    // Repulsion between all nodes
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const dx = nodes[j].x - nodes[i].x;
        const dy = nodes[j].y - nodes[i].y;
        const distSq = dx * dx + dy * dy || 1;
        const dist = Math.sqrt(distSq);

        if (dist < 320) {
          const force = (320 - dist) / dist * 0.4;
          const fx = dx * force;
          const fy = dy * force;

          if (nodes[i] !== dragNode) { nodes[i].x -= fx * 0.5; nodes[i].y -= fy * 0.5; }
          if (nodes[j] !== dragNode) { nodes[j].x += fx * 0.5; nodes[j].y += fy * 0.5; }
        }
      }
    }

    // Link attraction
    for (const link of links) {
      const dx = link.target.x - link.source.x;
      const dy = link.target.y - link.source.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const targetDist = 130;
      const force = (dist - targetDist) * 0.03;
      const fx = (dx / dist) * force;
      const fy = (dy / dist) * force;

      if (link.source !== dragNode) { link.source.x += fx; link.source.y += fy; }
      if (link.target !== dragNode) { link.target.x -= fx; link.target.y -= fy; }
    }

    // Center gravity
    const cx = graphCanvas.width / 2;
    const cy = graphCanvas.height / 2;
    for (const node of nodes) {
      if (node !== dragNode) {
        node.x += (cx - node.x) * 0.008;
        node.y += (cy - node.y) * 0.008;
      }
    }
  }

  // Draw Knowledge Graph on Canvas
  function drawGraph() {
    const ctx = graphCanvas.getContext('2d');
    ctx.clearRect(0, 0, graphCanvas.width, graphCanvas.height);

    ctx.save();
    ctx.translate(transform.x, transform.y);
    ctx.scale(transform.k, transform.k);

    // Draw Links
    for (const link of filteredLinks) {
      const isConnectedToHover = hoveredNode && (link.source === hoveredNode || link.target === hoveredNode);
      const isConnectedToSelected = selectedNode && (link.source === selectedNode || link.target === selectedNode);

      ctx.beginPath();
      ctx.moveTo(link.source.x, link.source.y);
      ctx.lineTo(link.target.x, link.target.y);

      if (isConnectedToHover || isConnectedToSelected) {
        ctx.strokeStyle = '#38bdf8';
        ctx.lineWidth = 2.5;
      } else {
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.12)';
        ctx.lineWidth = 1.2;
      }
      ctx.stroke();

      // Relation Label
      const midX = (link.source.x + link.target.x) / 2;
      const midY = (link.source.y + link.target.y) / 2;

      ctx.font = '9px "JetBrains Mono"';
      ctx.fillStyle = isConnectedToHover || isConnectedToSelected ? '#38bdf8' : 'rgba(148, 163, 184, 0.7)';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(link.relation, midX, midY - 6);
    }

    // Draw Nodes
    for (const node of filteredNodes) {
      const isHovered = node === hoveredNode;
      const isSelected = node === selectedNode;
      const color = getNodeColor(node.type);

      // Outer Halo / Glow
      ctx.beginPath();
      ctx.arc(node.x, node.y, node.radius + (isSelected ? 8 : (isHovered ? 6 : 2)), 0, Math.PI * 2);
      ctx.fillStyle = isSelected ? 'rgba(56, 189, 248, 0.35)' : (isHovered ? 'rgba(56, 189, 248, 0.2)' : 'rgba(255, 255, 255, 0.04)');
      ctx.fill();

      // Core Node Circle
      ctx.beginPath();
      ctx.arc(node.x, node.y, node.radius, 0, Math.PI * 2);
      ctx.fillStyle = '#0a0e1a';
      ctx.fill();
      ctx.lineWidth = isSelected ? 3 : 2;
      ctx.strokeStyle = color;
      ctx.stroke();

      // Node Label
      ctx.font = '11px "Outfit", sans-serif';
      ctx.fillStyle = '#f1f5f9';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';
      ctx.fillText(node.name, node.x, node.y + node.radius + 5);

      // Node Type Sublabel
      ctx.font = '9px "JetBrains Mono"';
      ctx.fillStyle = color;
      ctx.fillText(node.type.toUpperCase(), node.x, node.y + node.radius + 18);
    }

    ctx.restore();
  }

  function simulationLoop() {
    stepSimulation();
    drawGraph();
    simulationAnimFrame = requestAnimationFrame(simulationLoop);
  }

  function restartSimulation() {
    if (simulationAnimFrame) cancelAnimationFrame(simulationAnimFrame);
    resizeGraphCanvas();
    simulationLoop();
  }

  // Mouse Interaction: Pan, Zoom, Drag & Inspect
  function getCanvasCoords(e) {
    const rect = graphCanvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    return {
      x: (mx - transform.x) / transform.k,
      y: (my - transform.y) / transform.k
    };
  }

  function findNodeAt(x, y) {
    for (let i = filteredNodes.length - 1; i >= 0; i--) {
      const node = filteredNodes[i];
      const dx = node.x - x;
      const dy = node.y - y;
      if (dx * dx + dy * dy <= node.radius * node.radius * 1.5) {
        return node;
      }
    }
    return null;
  }

  graphCanvas.addEventListener('mousedown', (e) => {
    const coords = getCanvasCoords(e);
    const hit = findNodeAt(coords.x, coords.y);

    if (hit) {
      dragNode = hit;
      selectedNode = hit;
      inspectEntity(hit);
    } else {
      isDragging = true;
      lastMouse = { x: e.clientX, y: e.clientY };
    }
  });

  graphCanvas.addEventListener('mousemove', (e) => {
    const coords = getCanvasCoords(e);

    if (dragNode) {
      dragNode.x = coords.x;
      dragNode.y = coords.y;
      return;
    }

    if (isDragging) {
      const dx = e.clientX - lastMouse.x;
      const dy = e.clientY - lastMouse.y;
      transform.x += dx;
      transform.y += dy;
      lastMouse = { x: e.clientX, y: e.clientY };
      return;
    }

    const hit = findNodeAt(coords.x, coords.y);
    if (hit !== hoveredNode) {
      hoveredNode = hit;
      graphCanvas.style.cursor = hit ? 'pointer' : 'grab';
    }
  });

  window.addEventListener('mouseup', () => {
    dragNode = null;
    isDragging = false;
  });

  graphCanvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9;
    const rect = graphCanvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    transform.x = mouseX - (mouseX - transform.x) * zoomFactor;
    transform.y = mouseY - (mouseY - transform.y) * zoomFactor;
    transform.k *= zoomFactor;
    transform.k = Math.max(0.3, Math.min(3.0, transform.k));
  }, { passive: false });

  // Inspector Drawer
  function inspectEntity(node) {
    if (!node) return;
    inspectNodeName.textContent = node.name;
    inspectTypeBadge.textContent = node.type.toUpperCase();
    inspectTypeBadge.className = `category-tag ${node.type.toLowerCase()}`;

    // Aliases
    inspectAliases.innerHTML = '';
    const aliases = node.aliases.length > 0 ? node.aliases : [node.name];
    aliases.forEach(a => {
      const tag = document.createElement('span');
      tag.className = 'tag-item';
      tag.textContent = a;
      inspectAliases.appendChild(tag);
    });

    // Attributes
    inspectAttributes.innerHTML = '';
    const dataEntries = Object.entries(node.data || {});
    if (dataEntries.length > 0) {
      dataEntries.forEach(([k, v]) => {
        const row = document.createElement('div');
        row.className = 'attribute-row';
        row.innerHTML = `<span class="attr-label">${k}</span><span class="attr-value">${typeof v === 'object' ? JSON.stringify(v) : v}</span>`;
        inspectAttributes.appendChild(row);
      });
    } else {
      inspectAttributes.innerHTML = '<div class="text-dim">No extra metadata attributes</div>';
    }

    // Connected Relationships
    inspectRelationships.innerHTML = '';
    const connectedLinks = graphData.links.filter(l => l.source.name === node.name || l.target.name === node.name);
    if (connectedLinks.length > 0) {
      connectedLinks.forEach(l => {
        const otherNode = l.source.name === node.name ? l.target : l.source;
        const dir = l.source.name === node.name ? '→' : '←';
        const item = document.createElement('div');
        item.className = 'rel-item';
        item.innerHTML = `
          <div class="rel-header">
            <span>${dir} ${otherNode.name}</span>
            <span class="rel-type">${l.relation}</span>
          </div>
          ${l.evidence_text ? `<div class="rel-evidence">"${l.evidence_text}"</div>` : ''}
        `;
        inspectRelationships.appendChild(item);
      });
    } else {
      inspectRelationships.innerHTML = '<div class="text-dim">No explicit relationships recorded</div>';
    }

    // Sources Provenance
    inspectSources.innerHTML = '';
    const sources = node.source_files.length > 0 ? node.source_files : ['16_relationship_map.txt'];
    sources.forEach(src => {
      const pill = document.createElement('span');
      pill.className = 'source-pill';
      pill.textContent = src;
      pill.style.cursor = 'pointer';
      pill.addEventListener('click', () => viewEvidenceFile(src));
      inspectSources.appendChild(pill);
    });

    nodeInspector.classList.remove('hidden');
  }

  closeInspectorBtn.addEventListener('click', () => {
    nodeInspector.classList.add('hidden');
    selectedNode = null;
  });

  // Fit to screen
  graphFitBtn.addEventListener('click', () => {
    if (filteredNodes.length === 0) return;
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    filteredNodes.forEach(n => {
      minX = Math.min(minX, n.x - 40);
      maxX = Math.max(maxX, n.x + 40);
      minY = Math.min(minY, n.y - 40);
      maxY = Math.max(maxY, n.y + 40);
    });

    const w = maxX - minX || 100;
    const h = maxY - minY || 100;
    const scaleX = graphCanvas.width / w;
    const scaleY = graphCanvas.height / h;
    const k = Math.min(scaleX, scaleY, 1.2) * 0.85;

    transform.k = k;
    transform.x = (graphCanvas.width - w * k) / 2 - minX * k;
    transform.y = (graphCanvas.height - h * k) / 2 - minY * k;
  });

  graphResetBtn.addEventListener('click', () => {
    transform = { x: 0, y: 0, k: 1 };
    renderKnowledgeGraph(currentProcessed);
  });

  graphSearchInput.addEventListener('input', (e) => {
    const q = e.target.value.toLowerCase().trim();
    if (!q) {
      selectedNode = null;
      return;
    }

    const match = filteredNodes.find(n => n.name.toLowerCase().includes(q) || n.aliases.some(a => a.toLowerCase().includes(q)));
    if (match) {
      selectedNode = match;
      inspectEntity(match);
      transform.x = graphCanvas.width / 2 - match.x * transform.k;
      transform.y = graphCanvas.height / 2 - match.y * transform.k;
    }
  });

  // =========================================================================
  // Chronological Timeline (Tab 4)
  // =========================================================================
  function renderTimeline(processed) {
    const events = processed?.timeline?.timeline || [];
    timelineCountBadge.textContent = events.length;

    if (events.length === 0) {
      timelineEmpty.classList.remove('hidden');
      timelineList.innerHTML = '';
      return;
    }

    timelineEmpty.classList.add('hidden');
    timelineList.innerHTML = '';

    events.forEach(ev => {
      const item = document.createElement('div');
      item.className = 'timeline-item';

      const entitiesHtml = (ev.involved_entities || []).map(ent => `<span class="entity-chip">${ent}</span>`).join('');
      const confStr = ev.confidence ? `${(ev.confidence * 100).toFixed(0)}% Confidence` : 'Verified';

      item.innerHTML = `
        <div class="timeline-marker">
          <div class="timeline-dot"></div>
          <div class="timeline-line"></div>
        </div>
        <div class="timeline-card">
          <div class="timeline-card-header">
            <span class="timeline-timestamp">${ev.timestamp || 'Unknown Time'}</span>
            <span class="timeline-confidence">${confStr}</span>
          </div>
          <div class="timeline-text">${ev.event}</div>
          <div class="timeline-card-footer">
            <div class="timeline-entities">${entitiesHtml}</div>
            <span class="timeline-provenance">📄 ${ev.source_file || 'warehouse.txt'}</span>
          </div>
        </div>
      `;

      item.querySelector('.timeline-provenance').style.cursor = 'pointer';
      item.querySelector('.timeline-provenance').addEventListener('click', () => {
        if (ev.source_file) viewEvidenceFile(ev.source_file);
      });

      timelineList.appendChild(item);
    });
  }

  timelineSearchInput.addEventListener('input', (e) => {
    const q = e.target.value.toLowerCase();
    const items = timelineList.querySelectorAll('.timeline-item');
    items.forEach(item => {
      const text = item.textContent.toLowerCase();
      item.style.display = text.includes(q) ? 'flex' : 'none';
    });
  });

  // =========================================================================
  // Contradictions Matrix (Tab 5)
  // =========================================================================
  function renderContradictions(processed) {
    const contras = processed?.contradictions?.contradictions || [];
    contraCountBadge.textContent = contras.length;

    if (contras.length === 0) {
      contraEmpty.classList.remove('hidden');
      contraList.innerHTML = '';
      return;
    }

    contraEmpty.classList.add('hidden');
    contraList.innerHTML = '';

    contras.forEach(c => {
      const card = document.createElement('div');
      card.className = 'contradiction-card';

      card.innerHTML = `
        <div class="contra-header">
          <div class="contra-speaker">
            <span>👤 ${c.speaker || 'Suspect / Witness'}</span>
          </div>
          <span class="contra-nature">${c.nature || 'Inconsistency'}</span>
        </div>
        <div class="contra-split">
          <div class="contra-box claim">
            <div class="contra-box-title">Claim / Stated Alibi</div>
            <div>"${c.claim_a}"</div>
            <div class="contra-source-tag">Source: ${c.source_a}</div>
          </div>
          <div class="contra-box truth">
            <div class="contra-box-title">Physical Forensic Reality</div>
            <div>${c.claim_b}</div>
            <div class="contra-source-tag">Verified by: ${c.source_b}</div>
          </div>
        </div>
        ${c.investigator_notes ? `<div class="contra-notes"><strong>Forensic Note:</strong> ${c.investigator_notes}</div>` : ''}
      `;

      contraList.appendChild(card);
    });
  }

  contraSearchInput.addEventListener('input', (e) => {
    const q = e.target.value.toLowerCase();
    const cards = contraList.querySelectorAll('.contradiction-card');
    cards.forEach(card => {
      const text = card.textContent.toLowerCase();
      card.style.display = text.includes(q) ? 'block' : 'none';
    });
  });

  // =========================================================================
  // Sherlock's Opinion & Investigation Chat (Tab 6)
  // =========================================================================
  function renderOpinion(processed) {
    const op = processed?.opinion;

    if (!op) {
      opTheoryTitle.textContent = 'Deductive theory not yet formulated.';
      opConfidenceScore.textContent = 'Unprocessed';
      opResponsible.textContent = '-';
      opClassification.textContent = '-';
      opSupportingList.innerHTML = '<div class="text-dim">Run opinion deduction from the pipeline runner.</div>';
      opCounterList.innerHTML = '<div class="text-dim">Run opinion deduction from the pipeline runner.</div>';
      opLeadsList.innerHTML = '<div class="text-dim">Run opinion deduction from the pipeline runner.</div>';
      return;
    }

    opTheoryTitle.textContent = op.primary_theory;
    opConfidenceScore.textContent = `${((op.confidence || 0.9) * 100).toFixed(0)}% Confidence`;
    opResponsible.textContent = op.responsible_person || 'Undetermined';
    opClassification.textContent = op.classification || 'Active Investigation';

    // Supporting Evidence
    opSupportingList.innerHTML = '';
    (op.supporting_evidence || []).forEach(item => {
      const div = document.createElement('div');
      div.className = 'bullet-item';
      div.innerHTML = `
        <span class="bullet-icon">✓</span>
        <div>
          <span class="bullet-excerpt">"${item.excerpt || item}"</span>
          ${item.significance ? `<br><small class="text-dim">${item.significance}</small>` : ''}
        </div>
      `;
      opSupportingList.appendChild(div);
    });

    // Counter Evidence
    opCounterList.innerHTML = '';
    (op.flaws_and_counter_evidence || []).forEach(item => {
      const div = document.createElement('div');
      div.className = 'bullet-item';
      div.innerHTML = `
        <span class="bullet-icon" style="color: var(--accent-rose)">✗</span>
        <div>
          <span class="text-muted">${item.anomaly || item}</span>
          ${item.refutation ? `<br><small class="text-green">${item.refutation}</small>` : ''}
        </div>
      `;
      opCounterList.appendChild(div);
    });

    // Actionable Leads
    opLeadsList.innerHTML = '';
    (op.actionable_leads || []).forEach(lead => {
      const div = document.createElement('div');
      div.className = 'bullet-item';
      div.innerHTML = `
        <span class="bullet-icon" style="color: var(--accent-amber)">→</span>
        <span class="text-muted">${lead}</span>
      `;
      opLeadsList.appendChild(div);
    });
  }

  // Interactive Chat
  async function submitQuestion(question) {
    if (!question || !question.trim()) return;

    // Add User Bubble
    const userBubble = document.createElement('div');
    userBubble.className = 'chat-bubble user';
    userBubble.innerHTML = `
      <div class="bubble-sender">Investigator</div>
      <div class="bubble-text">${escapeHtml(question)}</div>
    `;
    chatMessages.appendChild(userBubble);
    chatMessages.scrollTop = chatMessages.scrollHeight;

    // Add Thinking Bubble
    const thinkingBubble = document.createElement('div');
    thinkingBubble.className = 'chat-bubble sherlock';
    thinkingBubble.innerHTML = `
      <div class="bubble-sender">🕵️‍♂️ Sherlock Holmes</div>
      <div class="bubble-text"><em>Examining knowledge graph and synthesizing evidence...</em></div>
    `;
    chatMessages.appendChild(thinkingBubble);
    chatMessages.scrollTop = chatMessages.scrollHeight;

    try {
      const res = await window.sherlockAPI.askSherlock(currentCaseId, question);
      const answer = res.success ? res.answer : `Could not query deduction engine: ${res.error}`;

      thinkingBubble.querySelector('.bubble-text').innerHTML = formatMarkdown(answer);
      chatMessages.scrollTop = chatMessages.scrollHeight;

      if (res.highlightNodes && res.highlightNodes.length > 0) {
        // Highlight in graph if open
        const target = filteredNodes.find(n => res.highlightNodes.some(hn => hn.toLowerCase() === n.name.toLowerCase()));
        if (target) {
          selectedNode = target;
          inspectEntity(target);
        }
      }
    } catch (err) {
      thinkingBubble.querySelector('.bubble-text').textContent = `Query error: ${err.message}`;
    }
  }

  chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const q = chatInput.value.trim();
    if (q) {
      chatInput.value = '';
      submitQuestion(q);
    }
  });

  promptChips.forEach(chip => {
    chip.addEventListener('click', () => {
      const prompt = chip.getAttribute('data-prompt');
      submitQuestion(prompt);
    });
  });

  function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function formatMarkdown(text) {
    return escapeHtml(text)
      .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.*?)\*/g, '<em>$1</em>')
      .replace(/\n\n/g, '<br><br>')
      .replace(/\n/g, '<br>');
  }

  // =========================================================================
  // Pipeline Execution Modal & Streaming Terminal
  // =========================================================================
  openPipelineModalBtn.addEventListener('click', () => {
    pipelineModal.classList.remove('hidden');
  });

  quickRunPipelineBtn.addEventListener('click', () => {
    pipelineModal.classList.remove('hidden');
  });

  extractTimelineBtn.addEventListener('click', () => {
    chkExtract.checked = false;
    chkTimeline.checked = true;
    chkContra.checked = false;
    chkOpinion.checked = false;
    pipelineModal.classList.remove('hidden');
  });

  detectContraBtn.addEventListener('click', () => {
    chkExtract.checked = false;
    chkTimeline.checked = false;
    chkContra.checked = true;
    chkOpinion.checked = false;
    pipelineModal.classList.remove('hidden');
  });

  generateOpinionBtn.addEventListener('click', () => {
    chkExtract.checked = false;
    chkTimeline.checked = false;
    chkContra.checked = false;
    chkOpinion.checked = true;
    pipelineModal.classList.remove('hidden');
  });

  graphExtractBtn.addEventListener('click', () => {
    chkExtract.checked = true;
    chkTimeline.checked = true;
    chkContra.checked = true;
    chkOpinion.checked = true;
    pipelineModal.classList.remove('hidden');
  });

  closePipelineModalBtn.addEventListener('click', () => {
    pipelineModal.classList.add('hidden');
  });

  // Real-time Pipeline Execution
  startPipelineExecutionBtn.addEventListener('click', async () => {
    if (!currentCaseId) return;

    startPipelineExecutionBtn.disabled = true;
    cancelPipelineBtn.disabled = false;
    pipelineStatusTag.className = 'terminal-status running';
    pipelineStatusTag.textContent = 'Running';
    terminalOutput.textContent = `[Sherlock Desktop] Initializing pipeline execution on ${currentCaseId}...\n`;

    // Setup streaming listener
    if (activeLogListener) activeLogListener();
    activeLogListener = window.sherlockAPI.onPipelineLog((log) => {
      terminalOutput.textContent += log.text;
      terminalOutput.scrollTop = terminalOutput.scrollHeight;
    });

    if (activeDoneListener) activeDoneListener();
    activeDoneListener = window.sherlockAPI.onPipelineDone((res) => {
      startPipelineExecutionBtn.disabled = false;
      cancelPipelineBtn.disabled = true;
      if (res.success) {
        pipelineStatusTag.className = 'terminal-status success';
        pipelineStatusTag.textContent = 'Completed';
        terminalOutput.textContent += `\n[Sherlock Desktop] Pipeline finished successfully (exit code ${res.code}). Refreshing case data...\n`;
      } else {
        pipelineStatusTag.className = 'terminal-status error';
        pipelineStatusTag.textContent = 'Failed';
        terminalOutput.textContent += `\n[Sherlock Desktop] Process ended with code ${res.code}.\n`;
      }
      terminalOutput.scrollTop = terminalOutput.scrollHeight;
      selectCase(currentCaseId);
    });

    const options = {
      extract: chkExtract.checked,
      timeline: chkTimeline.checked,
      contradictions: chkContra.checked,
      opinion: chkOpinion.checked,
      singleCall: chkSingleCall.checked,
      batchSize: parseInt(batchSizeInput.value, 10) || 20
    };

    await window.sherlockAPI.runPipeline(currentCaseId, options);
  });

  cancelPipelineBtn.addEventListener('click', async () => {
    await window.sherlockAPI.cancelPipeline();
    terminalOutput.textContent += `\n[Sherlock Desktop] Pipeline cancellation requested by user.\n`;
    cancelPipelineBtn.disabled = true;
  });

  // =========================================================================
  // Modals Management
  // =========================================================================
  // File Viewer
  closeFileViewerBtn.addEventListener('click', () => fileViewerModal.classList.add('hidden'));
  closeFileViewerBtn2.addEventListener('click', () => fileViewerModal.classList.add('hidden'));

  // Add Evidence File
  addEvidenceBtn.addEventListener('click', () => {
    newEvidenceFilename.value = '';
    newEvidenceContent.value = '';
    addEvidenceModal.classList.remove('hidden');
  });
  closeAddEvidenceBtn.addEventListener('click', () => addEvidenceModal.classList.add('hidden'));
  cancelAddEvidenceBtn.addEventListener('click', () => addEvidenceModal.classList.add('hidden'));

  saveEvidenceBtn.addEventListener('click', async () => {
    const filename = newEvidenceFilename.value.trim();
    const content = newEvidenceContent.value.trim();
    if (!filename || !content) {
      alert('Please specify both a file name and evidence content.');
      return;
    }

    const res = await window.sherlockAPI.saveEvidence(currentCaseId, filename, content);
    if (res.success) {
      addEvidenceModal.classList.add('hidden');
      alert(`Evidence saved to ${res.filename}! Rebuild warehouse to include it in the analysis.`);
      await selectCase(currentCaseId);
    } else {
      alert(`Failed to save evidence: ${res.error}`);
    }
  });

  // New Case Modal
  newCaseBtn.addEventListener('click', () => {
    newCaseId.value = '';
    newCaseName.value = '';
    newCaseModal.classList.remove('hidden');
  });
  closeNewCaseBtn.addEventListener('click', () => newCaseModal.classList.add('hidden'));
  cancelNewCaseBtn.addEventListener('click', () => newCaseModal.classList.add('hidden'));

  createCaseBtn.addEventListener('click', async () => {
    const id = newCaseId.value.trim();
    const name = newCaseName.value.trim();
    if (!id && !name) {
      alert('Please specify a case identifier or name.');
      return;
    }

    const res = await window.sherlockAPI.createCase({ caseId: id, caseName: name });
    if (res.success) {
      newCaseModal.classList.add('hidden');
      await loadCases(res.caseId);
    } else {
      alert(`Could not create case: ${res.error}`);
    }
  });

  // Settings Modal (LLM Config)
  settingsBtn.addEventListener('click', async () => {
    cfgFeedback.className = 'form-feedback hidden';
    const cfg = await window.sherlockAPI.getLLMConfig(currentCaseId);
    if (cfg) {
      cfgProvider.value = cfg.provider || 'openai';
      cfgModel.value = cfg.model || 'gpt-4o-mini';
      cfgContextWindow.value = cfg.contextWindow || 128000;
      cfgBaseUrl.value = cfg.baseUrl || '';
      cfgApiKey.placeholder = cfg.hasKey ? '•••••••••••••••• (saved)' : 'sk-...';
    }
    settingsModal.classList.remove('hidden');
  });

  closeSettingsBtn.addEventListener('click', () => settingsModal.classList.add('hidden'));
  cancelSettingsBtn.addEventListener('click', () => settingsModal.classList.add('hidden'));

  saveSettingsBtn.addEventListener('click', async () => {
    const config = {
      provider: cfgProvider.value,
      model: cfgModel.value.trim() || 'gpt-4o-mini',
      apiKey: cfgApiKey.value.trim(),
      contextWindow: cfgContextWindow.value,
      baseUrl: cfgBaseUrl.value.trim() || null
    };

    const res = await window.sherlockAPI.saveLLMConfig(currentCaseId, config);
    if (res.success) {
      cfgFeedback.className = 'form-feedback success';
      cfgFeedback.textContent = `Configuration saved to ${res.path}!`;
      setTimeout(() => {
        settingsModal.classList.add('hidden');
      }, 1000);
    } else {
      cfgFeedback.className = 'form-feedback error';
      cfgFeedback.textContent = `Error: ${res.error}`;
    }
  });

  // Case Select Dropdown Change
  caseSelect.addEventListener('change', (e) => {
    selectCase(e.target.value);
  });

  // Refresh Button
  refreshBtn.addEventListener('click', async () => {
    refreshBtn.style.transform = 'rotate(180deg)';
    await checkSystem();
    await loadCases(currentCaseId);
    setTimeout(() => { refreshBtn.style.transform = 'none'; }, 300);
  });

  // Open Case Folder in Windows Explorer
  openDataFolderBtn.addEventListener('click', () => {
    if (currentCaseId) {
      window.sherlockAPI.openCaseFolder(currentCaseId);
    }
  });

  // =========================================================================
  // Initial Boot
  // =========================================================================
  await checkSystem();
  await loadCases();
});
