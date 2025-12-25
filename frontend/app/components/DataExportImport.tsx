'use client';

import { useState } from 'react';
import api from '../../utils/api';

export default function DataExportImport() {
    const [isExporting, setIsExporting] = useState(false);
    const [isImporting, setIsImporting] = useState(false);
    const [importMode, setImportMode] = useState<'merge' | 'replace'>('merge');
    const [message, setMessage] = useState<{ type: 'success' | 'error' | 'info', text: string } | null>(null);

    const handleExport = async () => {
        setIsExporting(true);
        setMessage(null);

        try {
            const blob = await api.exportUserData();

            // Create download link
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `noslop_export_${Date.now()}.json`;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);

            setMessage({ type: 'success', text: 'Data exported successfully!' });
        } catch (err: any) {
            setMessage({ type: 'error', text: err.message || 'Export failed' });
        } finally {
            setIsExporting(false);
        }
    };

    const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;

        if (!file.name.endsWith('.json')) {
            setMessage({ type: 'error', text: 'Please select a JSON file' });
            return;
        }

        setIsImporting(true);
        setMessage(null);

        try {
            const result = await api.importUserData(file, importMode);

            if (result.status === 'success') {
                const imported = result.imported;
                const skipped = result.skipped;

                setMessage({
                    type: 'success',
                    text: `Import successful! Imported: ${imported.sessions} sessions, ${imported.messages} messages, ${imported.projects} projects, ${imported.tasks} tasks. Skipped: ${skipped.sessions + skipped.messages + skipped.projects + skipped.tasks} items.`
                });

                // Reload page after short delay
                setTimeout(() => window.location.reload(), 2000);
            } else {
                throw new Error(result.message || 'Import failed');
            }
        } catch (err: any) {
            setMessage({ type: 'error', text: err.message || 'Import failed' });
        } finally {
            setIsImporting(false);
            e.target.value = ''; // Reset file input
        }
    };

    return (
        <div className="bg-gray-800 rounded-lg p-6 space-y-6">
            <h3 className="text-xl font-semibold">Data Portability</h3>

            {message && (
                <div className={`p-3 rounded ${message.type === 'success' ? 'bg-green-900 text-green-200' :
                    message.type === 'error' ? 'bg-red-900 text-red-200' :
                        'bg-blue-900 text-blue-200'
                    }`}>
                    {message.text}
                </div>
            )}

            {/* Export Section */}
            <div className="space-y-3">
                <h4 className="font-medium text-gray-300">Export Your Data</h4>
                <p className="text-sm text-gray-400">
                    Download all your data including profile, sessions, messages, projects, and tasks as a JSON file.
                </p>
                <button
                    onClick={handleExport}
                    disabled={isExporting}
                    className="bg-blue-600 hover:bg-blue-700 text-white px-6 py-2 rounded font-medium transition-colors disabled:opacity-50 flex items-center gap-2"
                >
                    {isExporting ? (
                        <>
                            <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
                            Exporting...
                        </>
                    ) : (
                        <>
                            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a 3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                            </svg>
                            Export My Data
                        </>
                    )}
                </button>
            </div>

            <div className="border-t border-gray-700 pt-6">
                {/* Import Section */}
                <div className="space-y-3">
                    <h4 className="font-medium text-gray-300">Import Data</h4>
                    <p className="text-sm text-gray-400">
                        Import data from a previously exported JSON file.
                    </p>

                    <div className="space-y-2">
                        <label className="block text-sm text-gray-400">Import Mode</label>
                        <select
                            value={importMode}
                            onChange={(e) => setImportMode(e.target.value as 'merge' | 'replace')}
                            className="w-full bg-gray-700 rounded p-2 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                            disabled={isImporting}
                        >
                            <option value="merge">Merge (add new, keep existing)</option>
                            <option value="replace">Replace (overwrite existing)</option>
                        </select>
                        <p className="text-xs text-gray-500">
                            {importMode === 'merge'
                                ? 'New items will be added. Existing items with same ID will be kept unchanged.'
                                : 'Existing items with same ID will be overwritten with imported data.'}
                        </p>
                    </div>

                    <label className="inline-block bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded font-medium cursor-pointer transition-colors">
                        {isImporting ? (
                            <span className="flex items-center gap-2">
                                <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
                                Importing...
                            </span>
                        ) : (
                            <span className="flex items-center gap-2">
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
                                </svg>
                                Select File to Import
                            </span>
                        )}
                        <input
                            type="file"
                            accept=".json"
                            onChange={handleImport}
                            className="hidden"
                            disabled={isImporting}
                        />
                    </label>
                </div>
            </div>

            <div className="bg-yellow-900/30 border border-yellow-700 rounded p-4">
                <p className="text-sm text-yellow-200 flex items-start gap-2">
                    <svg className="w-5 h-5 flex-shrink-0 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                        <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                    </svg>
                    <span>
                        <strong>Important:</strong> Export your data regularly as a backup. When importing in "replace" mode, existing data will be overwritten.
                    </span>
                </p>
            </div>
        </div>
    );
}
