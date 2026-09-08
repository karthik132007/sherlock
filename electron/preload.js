const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sherlockAPI', {
  listCases: () => ipcRenderer.invoke('cases:list'),
  getCaseDetails: (caseId) => ipcRenderer.invoke('cases:getDetails', caseId),
  getSystemStatus: () => ipcRenderer.invoke('system:status'),
  platform: process.platform
});
