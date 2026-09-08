const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');
const { exec } = require('child_process');

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1320,
    height: 860,
    minWidth: 1024,
    minHeight: 700,
    backgroundColor: '#0a0d14',
    title: 'Sherlock — AI Investigation Knowledge Graph',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      devTools: true
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// IPC: List available cases from `data/` directory
ipcMain.handle('cases:list', async () => {
  try {
    const dataDir = path.join(__dirname, '..', 'data');
    if (!fs.existsSync(dataDir)) {
      return [];
    }

    const entries = fs.readdirSync(dataDir, { withFileTypes: true });
    const cases = [];

    for (const entry of entries) {
      if (entry.isDirectory()) {
        const casePath = path.join(dataDir, entry.name);
        const files = fs.readdirSync(casePath);
        const textFiles = files.filter(f => f.endsWith('.txt'));
        const jsonFiles = files.filter(f => f.endsWith('.json'));
        const hasWarehouse = files.includes('warehouse.txt') || fs.existsSync(path.join(casePath, 'warehouse.txt'));
        const hasProcessed = fs.existsSync(path.join(casePath, 'processed'));

        cases.push({
          id: entry.name,
          name: entry.name.replace(/_/g, ' ').toUpperCase(),
          path: casePath,
          totalFiles: files.length,
          textFilesCount: textFiles.length,
          jsonFilesCount: jsonFiles.length,
          hasWarehouse,
          hasProcessed,
          filesList: files
        });
      }
    }

    return cases;
  } catch (error) {
    console.error('Error listing cases:', error);
    return [];
  }
});

// IPC: Get specific case details
ipcMain.handle('cases:getDetails', async (event, caseId) => {
  try {
    const casePath = path.join(__dirname, '..', 'data', caseId);
    if (!fs.existsSync(casePath)) {
      return null;
    }

    const files = fs.readdirSync(casePath);
    const fileStats = files.map(file => {
      const fullPath = path.join(casePath, file);
      const stat = fs.statSync(fullPath);
      return {
        name: file,
        size: stat.size,
        isFile: stat.isFile(),
        modified: stat.mtime
      };
    });

    return {
      caseId,
      path: casePath,
      files: fileStats
    };
  } catch (err) {
    console.error(`Error loading case ${caseId}:`, err);
    return null;
  }
});

// IPC: Check system environment and Python status
ipcMain.handle('system:status', async () => {
  return new Promise((resolve) => {
    exec('python --version', (error, stdout, stderr) => {
      const pythonVersion = stdout ? stdout.trim() : (stderr ? stderr.trim() : 'Not detected');
      const rootDir = path.join(__dirname, '..');
      const hasEngine = fs.existsSync(path.join(rootDir, 'engine'));
      const hasMainPy = fs.existsSync(path.join(rootDir, 'main.py'));

      resolve({
        pythonAvailable: !error,
        pythonVersion,
        hasEngine,
        hasMainPy,
        platform: process.platform,
        nodeVersion: process.version
      });
    });
  });
});

app.whenReady().then(() => {
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
