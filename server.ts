import express from 'express';
import fs from 'fs';
import path from 'path';
import JSZip from 'jszip';
import { createServer as createViteServer } from 'vite';

interface ProjectFileEntry {
  path: string;
  name: string;
  extension: string;
  sizeBytes: number;
  lineCount: number;
  content: string;
}

const ANDROID_PROJECT_DIR = path.resolve(process.cwd(), 'android-project');

function collectProjectFiles(dir: string, baseDir: string): ProjectFileEntry[] {
  if (!fs.existsSync(dir)) return [];
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const results: ProjectFileEntry[] = [];

  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...collectProjectFiles(fullPath, baseDir));
    } else if (entry.isFile()) {
      const relPath = path.relative(baseDir, fullPath).replace(/\\/g, '/');
      const content = fs.readFileSync(fullPath, 'utf-8');
      const ext = path.extname(entry.name).replace(/^\./, '').toLowerCase() || 'txt';
      results.push({
        path: relPath,
        name: entry.name,
        extension: ext,
        sizeBytes: Buffer.byteLength(content, 'utf-8'),
        lineCount: content.split('\n').length,
        content,
      });
    }
  }

  return results.sort((a, b) => a.path.localeCompare(b.path));
}

async function startServer() {
  const app = express();
  const port = 3000;

  app.get('/api/project-files', (_req, res) => {
    try {
      const files = collectProjectFiles(ANDROID_PROJECT_DIR, ANDROID_PROJECT_DIR);
      res.json({
        projectName: 'ScreenRecorder',
        packageName: 'com.screenrecorder.pro',
        minSdk: 36,
        compileSdk: 37,
        targetSdk: 37,
        versionName: '1.0',
        versionCode: 1,
        fileCount: files.length,
        files,
      });
    } catch (error) {
      res.status(500).json({ error: 'Failed to read Android Studio project files.' });
    }
  });

  app.get('/api/download-zip', async (_req, res) => {
    try {
      const files = collectProjectFiles(ANDROID_PROJECT_DIR, ANDROID_PROJECT_DIR);
      const zip = new JSZip();
      const rootFolder = zip.folder('ScreenRecorder-AndroidStudio');

      for (const file of files) {
        rootFolder?.file(file.path, file.content);
      }

      const buffer = await zip.generateAsync({
        type: 'nodebuffer',
        compression: 'DEFLATE',
        compressionOptions: { level: 9 },
      });

      res.setHeader('Content-Type', 'application/zip');
      res.setHeader(
        'Content-Disposition',
        'attachment; filename="ScreenRecorder-AndroidStudio.zip"'
      );
      res.send(buffer);
    } catch (error) {
      res.status(500).json({ error: 'Failed to bundle Android Studio ZIP archive.' });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(process.cwd(), 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(port, '0.0.0.0', () => {
    console.log(`Server listening on http://0.0.0.0:${port}`);
  });
}

startServer();
