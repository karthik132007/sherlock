const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sherlockAPI', {
  // System & Environment
  getSystemStatus: () => ipcRenderer.invoke('system:status'),
  platform: process.platform,

  // Cases Management
  listCases: () => ipcRenderer.invoke('cases:list'),
  getCaseDetails: (caseId) => ipcRenderer.invoke('cases:getDetails', caseId),
  createCase: (data) => ipcRenderer.invoke('cases:create', data),
  openCaseFolder: (caseId) => ipcRenderer.invoke('cases:openFolder', caseId),

  // Evidence & Warehouse
  readEvidence: (caseId, filename) => ipcRenderer.invoke('evidence:read', { caseId, filename }),
  saveEvidence: (caseId, filename, content) => ipcRenderer.invoke('evidence:save', { caseId, filename, content }),
  buildWarehouse: (caseId) => ipcRenderer.invoke('warehouse:build', caseId),

  // Python Engine Pipeline
  runPipeline: (caseId, options) => ipcRenderer.invoke('pipeline:run', { caseId, options }),
  cancelPipeline: () => ipcRenderer.invoke('pipeline:cancel'),
  onPipelineLog: (callback) => {
    const handler = (event, data) => callback(data);
    ipcRenderer.on('pipeline:log', handler);
    return () => ipcRenderer.removeListener('pipeline:log', handler);
  },
  onPipelineDone: (callback) => {
    const handler = (event, data) => callback(data);
    ipcRenderer.on('pipeline:done', handler);
    return () => ipcRenderer.removeListener('pipeline:done', handler);
  },

  // Processed Data & Bootstrap
  getProcessedData: (caseId) => ipcRenderer.invoke('data:getProcessed', caseId),
  bootstrapCaseData: (caseId) => ipcRenderer.invoke('data:bootstrap', caseId),

  // Interactive Natural Language Query & Chat
  askSherlock: (caseId, question) => ipcRenderer.invoke('query:ask', { caseId, question }),

  // LLM Configuration
  getLLMConfig: (caseId) => ipcRenderer.invoke('llm:getConfig', caseId),
  saveLLMConfig: (caseId, config) => ipcRenderer.invoke('llm:saveConfig', { caseId, config })
});
