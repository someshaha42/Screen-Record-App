import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Check,
  Copy,
  Download,
  FileCode2,
  FolderOpen,
  Search,
  Terminal,
} from 'lucide-react';

interface ProjectFileEntry {
  path: string;
  name: string;
  extension: string;
  sizeBytes: number;
  lineCount: number;
  content: string;
}

interface ProjectPayload {
  projectName: string;
  packageName: string;
  minSdk: number;
  compileSdk: number;
  targetSdk: number;
  versionName: string;
  versionCode: number;
  fileCount: number;
  files: ProjectFileEntry[];
}

type ActiveTab = 'files' | 'architecture' | 'build';

interface SpecMappingRow {
  area: string;
  requirement: string;
  implementationFile: string;
  notes: string;
}

const SPEC_MAPPINGS: SpecMappingRow[] = [
  {
    area: 'Project & SDK Targets',
    requirement: 'minSdk 36, compileSdk 37, targetSdk 37, version 1.0 (1), Jetpack Compose',
    implementationFile: 'app/build.gradle.kts',
    notes: 'Configured with Kotlin 2.1.10, Compose BOM, Material 3, and debug build type.',
  },
  {
    area: 'Fixed Internal Parameters',
    requirement: 'Native resolution, 60 FPS, H.264/AVC, system theme — hidden from UI',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/config/InternalRecordingConfig.kt',
    notes: 'Not exposed in RecorderDashboardScreen or any settings control.',
  },
  {
    area: 'Hardware Encoder & Lossless Check',
    requirement: 'Prefer c2.qti.avc.encoder / c2.mtk.avc.encoder; no silent SW fallback; honest lossless check',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/codec/HardwareCodecInspector.kt',
    notes: 'Validates AVCProfileHigh444 + BITRATE_MODE_CQ; falls back transparently to max HW bitrate if unavailable.',
  },
  {
    area: 'Hardware-Only Decoder',
    requirement: 'Strict MediaCodec hardware AVC playback without software fallback',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/player/HardwareVideoPlayer.kt',
    notes: 'Uses MediaExtractor + MediaCodec.createByCodecName on hardware decoder or shows compatibility error.',
  },
  {
    area: 'GL Region Crop & Orientation',
    requirement: 'GPU sub-region crop before encoding; dynamic aspect-preserving mirroring',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/engine/GlSurfaceCropRenderer.kt',
    notes: 'Zero-copy SurfaceTexture OES shader crops strictly to selected rectangle and handles pause timestamps.',
  },
  {
    area: 'Audio & Mic Routing',
    requirement: 'Default Internal Sound; auto USB/Bluetooth/Built-in mic switching and disconnect safety',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/engine/AudioCaptureEngine.kt',
    notes: 'AudioPlaybackCaptureConfiguration for internal audio; AudioDeviceCallback handles live mic routing.',
  },
  {
    area: 'Region Border & Overlay Fallback',
    requirement: 'Deep-red animated dashed border outside capture; optional SYSTEM_ALERT_WINDOW',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/overlay/RegionBorderOverlayController.kt',
    notes: 'Draws stroke outward beyond crop rectangle; region recording works fully even if overlay is denied.',
  },
  {
    area: 'Recording Lifecycle & Service',
    requirement: 'Official MediaProjection consent, ForegroundService, notification controls',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/service/ScreenRecordService.kt',
    notes: 'Declares mediaProjection and microphone foregroundServiceTypes with Pause/Resume/Stop actions.',
  },
  {
    area: 'User Interface & Library',
    requirement: 'Expose only Audio, Bitrate, Mode, Region; MediaStore thumbnails, rename, delete, share',
    implementationFile: 'app/src/main/java/com/screenrecorder/pro/ui/screens/RecorderDashboardScreen.kt',
    notes: 'Follows system Light/Dark dynamic theme; RecordingLibraryScreen handles MediaStore playback & actions.',
  },
];

function groupLabelForPath(filePath: string): string {
  if (
    filePath.endsWith('.gradle.kts') ||
    filePath.endsWith('.properties') ||
    filePath.endsWith('.toml') ||
    filePath.endsWith('.pro') ||
    filePath === 'README.md'
  ) {
    return 'Gradle & Project Config';
  }
  if (filePath.includes('/src/test/')) {
    return 'Unit Tests';
  }
  if (filePath.includes('/src/main/res/') || filePath.endsWith('AndroidManifest.xml')) {
    return 'Manifest & Resources';
  }
  return 'Kotlin Source';
}

export default function App() {
  const [payload, setPayload] = useState<ProjectPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<ActiveTab>('files');
  const [selectedPath, setSelectedPath] = useState<string>(
    'app/src/main/java/com/screenrecorder/pro/codec/HardwareCodecInspector.kt'
  );
  const [searchQuery, setSearchQuery] = useState('');
  const [copiedPath, setCopiedPath] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        setLoading(true);
        const res = await fetch('/api/project-files');
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        const data: ProjectPayload = await res.json();
        if (!cancelled) {
          setPayload(data);
          if (data.files.length > 0 && !data.files.some((f) => f.path === selectedPath)) {
            setSelectedPath(data.files[0].path);
          }
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unable to load project files.');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }
    load();
    return () => {
      cancelled = true;
    };
  }, []);

  const filteredFiles = useMemo(() => {
    if (!payload) return [];
    const q = searchQuery.trim().toLowerCase();
    if (!q) return payload.files;
    return payload.files.filter(
      (f) => f.path.toLowerCase().includes(q) || f.content.toLowerCase().includes(q)
    );
  }, [payload, searchQuery]);

  const groupedFiles = useMemo(() => {
    const groups: Record<string, ProjectFileEntry[]> = {
      'Kotlin Source': [],
      'Manifest & Resources': [],
      'Gradle & Project Config': [],
      'Unit Tests': [],
    };
    for (const file of filteredFiles) {
      groups[groupLabelForPath(file.path)].push(file);
    }
    return Object.entries(groups).filter(([, list]) => list.length > 0);
  }, [filteredFiles]);

  const activeFile = useMemo(() => {
    if (!payload) return null;
    return payload.files.find((f) => f.path === selectedPath) ?? payload.files[0] ?? null;
  }, [payload, selectedPath]);

  const totalLines = useMemo(() => {
    if (!payload) return 0;
    return payload.files.reduce((sum, f) => sum + f.lineCount, 0);
  }, [payload]);

  function handleSelectFile(filePath: string) {
    setSelectedPath(filePath);
    setActiveTab('files');
  }

  async function handleCopyActiveFile() {
    if (!activeFile) return;
    try {
      await navigator.clipboard.writeText(activeFile.content);
      setCopiedPath(activeFile.path);
      setTimeout(() => {
        setCopiedPath((prev) => (prev === activeFile.path ? null : prev));
      }, 1800);
    } catch {
      // Ignore clipboard failure in restricted iframe contexts
    }
  }

  function handleDownloadZip() {
    window.location.assign('/api/download-zip');
  }

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col">
      {/* Top Bar: strict 3-zone contract */}
      <header className="sticky top-0 z-20 bg-white border-b border-slate-200 px-6 py-3.5 flex items-center justify-between gap-8">
        <span className="font-display text-lg font-bold tracking-tight text-slate-900 whitespace-nowrap shrink-0">
          Screen Recorder
        </span>

        <nav className="flex items-center gap-6 text-sm font-medium">
          <button
            type="button"
            onClick={() => setActiveTab('files')}
            className={`whitespace-nowrap shrink-0 pb-0.5 transition-colors focus-visible:outline-2 focus-visible:outline-slate-900 ${
              activeTab === 'files'
                ? 'text-slate-900 border-b-2 border-red-700 font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Source Files
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('architecture')}
            className={`whitespace-nowrap shrink-0 pb-0.5 transition-colors focus-visible:outline-2 focus-visible:outline-slate-900 ${
              activeTab === 'architecture'
                ? 'text-slate-900 border-b-2 border-red-700 font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Architecture
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('build')}
            className={`whitespace-nowrap shrink-0 pb-0.5 transition-colors focus-visible:outline-2 focus-visible:outline-slate-900 ${
              activeTab === 'build'
                ? 'text-slate-900 border-b-2 border-red-700 font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Build Guide
          </button>
        </nav>

        <div className="shrink-0">
          <button
            type="button"
            onClick={handleDownloadZip}
            className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold text-white bg-red-700 hover:bg-red-800 rounded-lg transition-colors whitespace-nowrap shrink-0 focus-visible:outline-2 focus-visible:outline-red-700"
          >
            <Download className="w-4 h-4" />
            Download Project ZIP
          </button>
        </div>
      </header>

      {/* Honest Environment & APK Build Notice */}
      <div className="bg-amber-50 border-b border-amber-200 px-6 py-3">
        <div className="max-w-[1400px] mx-auto flex items-start gap-3 text-xs text-amber-950 leading-relaxed">
          <AlertTriangle className="w-4 h-4 text-amber-700 shrink-0 mt-0.5" />
          <div>
            <span className="font-semibold">Container Build Limitation: </span>
            This preview runs in a Debian Node.js web environment without JDK, Gradle, Android SDK (API 36–37), or an Android hardware device.
            The complete native Kotlin and Jetpack Compose Android Studio project is generated below for inspection and ZIP download, while compiling the Debug APK requires running{' '}
            <code className="font-mono bg-amber-100 px-1 py-0.5 rounded">./gradlew assembleDebug</code> locally in Android Studio.
          </div>
        </div>
      </div>

      {/* Project Summary Strip */}
      <div className="bg-white border-b border-slate-200 px-6 py-3">
        <div className="max-w-[1400px] mx-auto flex flex-wrap items-center justify-between gap-4 text-xs text-slate-600">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-slate-900">com.screenrecorder.pro</span>
            <span aria-hidden="true">·</span>
            <span>Kotlin + Jetpack Compose</span>
            <span aria-hidden="true">·</span>
            <span className="tabular-nums">Min SDK 36</span>
            <span aria-hidden="true">·</span>
            <span className="tabular-nums">Compile / Target SDK 37</span>
            <span aria-hidden="true">·</span>
            <span className="tabular-nums">Version 1.0 (1)</span>
          </div>
          {payload && (
            <div className="flex items-center gap-2 font-mono tabular-nums text-slate-600">
              <span>{payload.fileCount} files</span>
              <span aria-hidden="true">·</span>
              <span>{totalLines.toLocaleString()} lines</span>
            </div>
          )}
        </div>
      </div>

      {/* Main Content */}
      <main className="flex-1 max-w-[1400px] w-full mx-auto px-6 py-6">
        {loading ? (
          <div className="bg-white border border-slate-200 rounded-xl p-8 text-sm text-slate-600">
            Loading Android Studio project files...
          </div>
        ) : error ? (
          <div className="bg-white border border-red-200 rounded-xl p-6 text-sm text-red-800">
            Failed to load Android project files: {error}
          </div>
        ) : activeTab === 'files' ? (
          <div className="grid grid-cols-1 lg:grid-cols-[290px_minmax(0,1fr)] gap-6 items-start">
            {/* Sidebar File Tree */}
            <aside className="bg-white border border-slate-200 rounded-xl overflow-hidden">
              <div className="p-3 border-b border-slate-200">
                <label className="relative block">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Filter files or symbols..."
                    className="w-full pl-9 pr-3 py-2 text-xs bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:border-slate-400"
                  />
                </label>
              </div>

              <div className="max-h-[72vh] overflow-y-auto divide-y divide-slate-100">
                {groupedFiles.length === 0 ? (
                  <div className="p-4 text-xs text-slate-500">
                    No files matched "{searchQuery}".
                  </div>
                ) : (
                  groupedFiles.map(([group, files]) => (
                    <div key={group} className="py-2">
                      <div className="px-3 py-1.5 text-xs font-semibold text-slate-500 flex items-center gap-1.5">
                        <FolderOpen className="w-3.5 h-3.5" />
                        <span>{group}</span>
                      </div>
                      <ul className="space-y-0.5 px-1.5">
                        {files.map((file) => {
                          const isSelected = activeFile?.path === file.path;
                          return (
                            <li key={file.path}>
                              <button
                                type="button"
                                onClick={() => setSelectedPath(file.path)}
                                className={`w-full text-left px-2.5 py-2 rounded-lg text-xs flex items-center justify-between gap-2 transition-colors ${
                                  isSelected
                                    ? 'bg-slate-900 text-white font-medium'
                                    : 'text-slate-700 hover:bg-slate-100'
                                }`}
                              >
                                <span className="truncate flex items-center gap-2 min-w-0">
                                  <FileCode2 className="w-3.5 h-3.5 shrink-0 opacity-75" />
                                  <span className="truncate">{file.name}</span>
                                </span>
                                <span
                                  className={`font-mono text-[11px] tabular-nums shrink-0 ${
                                    isSelected ? 'text-slate-300' : 'text-slate-400'
                                  }`}
                                >
                                  {file.lineCount}L
                                </span>
                              </button>
                            </li>
                          );
                        })}
                      </ul>
                    </div>
                  ))
                )}
              </div>
            </aside>

            {/* Active Source Viewer */}
            <section className="bg-white border border-slate-200 rounded-xl overflow-hidden min-w-0">
              {activeFile ? (
                <>
                  <div className="px-5 py-3.5 border-b border-slate-200 flex flex-wrap items-center justify-between gap-4">
                    <div className="min-w-0">
                      <h2 className="font-mono text-xs font-semibold text-slate-900 truncate">
                        {activeFile.path}
                      </h2>
                      <div className="mt-1 flex items-center gap-2 text-xs text-slate-500 font-mono tabular-nums">
                        <span>{activeFile.lineCount} lines</span>
                        <span aria-hidden="true">·</span>
                        <span>{(activeFile.sizeBytes / 1024).toFixed(1)} KB</span>
                        <span aria-hidden="true">·</span>
                        <span>.{activeFile.extension}</span>
                      </div>
                    </div>

                    <button
                      type="button"
                      onClick={handleCopyActiveFile}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors whitespace-nowrap shrink-0"
                    >
                      {copiedPath === activeFile.path ? (
                        <>
                          <Check className="w-3.5 h-3.5 text-emerald-700" />
                          <span>Copied</span>
                        </>
                      ) : (
                        <>
                          <Copy className="w-3.5 h-3.5" />
                          <span>Copy File</span>
                        </>
                      )}
                    </button>
                  </div>

                  <div className="max-h-[72vh] overflow-auto bg-slate-950 text-slate-100 text-xs font-mono leading-relaxed">
                    <table className="w-full border-collapse">
                      <tbody>
                        {activeFile.content.split('\n').map((line, index) => (
                          <tr key={index} className="hover:bg-slate-900/70">
                            <td className="select-none text-right pr-4 pl-4 py-0.5 text-slate-500 w-12 tabular-nums align-top border-r border-slate-800/80">
                              {index + 1}
                            </td>
                            <td className="pl-4 pr-6 py-0.5 whitespace-pre">{line || ' '}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              ) : (
                <div className="p-8 text-sm text-slate-500">Select a file from the project tree.</div>
              )}
            </section>
          </div>
        ) : activeTab === 'architecture' ? (
          <section className="bg-white border border-slate-200 rounded-xl overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-200">
              <h1 className="font-display text-xl font-bold text-slate-900">
                Native Android Architecture Mapping
              </h1>
              <p className="mt-1 text-sm text-slate-600 max-w-3xl">
                Every technical constraint is implemented directly in native Kotlin using Android API 36–37 frameworks. Select any source path to inspect the implementation.
              </p>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse text-sm">
                <thead>
                  <tr className="border-b border-slate-200 bg-slate-50 text-xs font-semibold text-slate-600">
                    <th className="py-3 px-6">Subsystem</th>
                    <th className="py-3 px-6">Specification</th>
                    <th className="py-3 px-6">Implementation File</th>
                    <th className="py-3 px-6">Behavior Notes</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200">
                  {SPEC_MAPPINGS.map((row) => (
                    <tr key={row.area} className="hover:bg-slate-50/70 align-top">
                      <td className="py-3.5 px-6 font-medium text-slate-900 whitespace-nowrap">
                        {row.area}
                      </td>
                      <td className="py-3.5 px-6 text-slate-700">{row.requirement}</td>
                      <td className="py-3.5 px-6">
                        <button
                          type="button"
                          onClick={() => handleSelectFile(row.implementationFile)}
                          className="font-mono text-xs text-red-700 hover:underline text-left"
                        >
                          {row.implementationFile}
                        </button>
                      </td>
                      <td className="py-3.5 px-6 text-slate-600 text-xs leading-relaxed">
                        {row.notes}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        ) : (
          <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-6">
            <div>
              <h1 className="font-display text-xl font-bold text-slate-900">
                Local Android Studio Build & Verification Steps
              </h1>
              <p className="mt-1 text-sm text-slate-600 max-w-3xl">
                Download the complete project archive using the button in the top bar, extract it, and build the Debug APK using Android Studio or Gradle with Android SDK 37 installed.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="border border-slate-200 rounded-xl p-5 space-y-3">
                <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                  <Terminal className="w-4 h-4 text-slate-700" />
                  <span>1. Build Debug APK via Gradle</span>
                </div>
                <pre className="bg-slate-950 text-slate-100 rounded-lg p-4 text-xs font-mono overflow-x-auto leading-relaxed">
{`unzip ScreenRecorder-AndroidStudio.zip
cd ScreenRecorder-AndroidStudio
gradle assembleDebug`}
                </pre>
                <p className="text-xs text-slate-600 leading-relaxed">
                  Produces the debug binary at{' '}
                  <code className="font-mono bg-slate-100 px-1 py-0.5 rounded">
                    app/build/outputs/apk/debug/app-debug.apk
                  </code>
                  .
                </p>
              </div>

              <div className="border border-slate-200 rounded-xl p-5 space-y-3">
                <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                  <Terminal className="w-4 h-4 text-slate-700" />
                  <span>2. Install & Verify on API 36–37 Device</span>
                </div>
                <pre className="bg-slate-950 text-slate-100 rounded-lg p-4 text-xs font-mono overflow-x-auto leading-relaxed">
{`adb install -r app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest`}
                </pre>
                <p className="text-xs text-slate-600 leading-relaxed">
                  Verify hardware AVC encoder detection (`c2.qti.avc.encoder` / `c2.mtk.avc.encoder`), lossless capability notice, region GPU crop, rotation mirroring, and audio routing on a physical device.
                </p>
              </div>
            </div>
          </section>
        )}
      </main>
    </div>
  );
}
