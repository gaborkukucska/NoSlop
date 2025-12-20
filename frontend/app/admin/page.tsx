'use client';

import { useState, useEffect, useRef } from 'react';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';
import { useRouter } from 'next/navigation';

export default function AdminDashboard() {
    const { user, isAdmin, isLoading: authLoading } = useAuth();
    const router = useRouter();
    const [activeTab, setActiveTab] = useState<'users' | 'settings' | 'data'>('users');
    const [users, setUsers] = useState<any[]>([]);
    const [settings, setSettings] = useState<any>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Edit User State
    const [editingUser, setEditingUser] = useState<any | null>(null);
    const [editForm, setEditForm] = useState<any>({});

    const fileInputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        if (!authLoading && !isAdmin) {
            router.push('/');
        }
    }, [isAdmin, authLoading, router]);

    useEffect(() => {
        if (isAdmin) {
            fetchData();
        }
    }, [isAdmin, activeTab]);

    const fetchData = async () => {
        setLoading(true);
        setError(null);
        try {
            if (activeTab === 'users') {
                const data = await api.getUsers();
                setUsers(data.users);
            } else if (activeTab === 'settings') {
                const data = await api.getSystemSettings();
                setSettings(data);
            }
        } catch (err: any) {
            setError(err.message || 'Failed to fetch data');
        } finally {
            setLoading(false);
        }
    };

    const handleUserUpdate = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            await api.adminUpdateUser(editingUser.id, editForm);
            setEditingUser(null);
            fetchData(); // Refresh list
        } catch (err: any) {
            alert(err.message || 'Update failed');
        }
    };

    const handleDeleteUser = async (userId: string) => {
        if (!confirm('Are you sure you want to delete this user? This cannot be undone.')) return;
        try {
            await api.adminDeleteUser(userId);
            fetchData();
        } catch (err: any) {
            alert(err.message || 'Delete failed');
        }
    };

    const handleSettingsUpdate = async () => {
        try {
            await api.updateSystemSettings(settings);
            alert('Settings updated');
        } catch (err: any) {
            alert(err.message || 'Update failed');
        }
    };

    const handleExport = async () => {
        try {
            const data = await api.exportData();
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `noslop-export-${new Date().toISOString()}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        } catch (err: any) {
            alert(err.message || 'Export failed');
        }
    };

    const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;

        if (!confirm('WARNING: Importing data will WIPE the current database and replace it. Are you sure?')) {
            e.target.value = ''; // Reset input
            return;
        }

        const reader = new FileReader();
        reader.onload = async (event) => {
            try {
                const json = JSON.parse(event.target?.result as string);
                setLoading(true);
                await api.importData(json);
                alert('Import successful! The system has been restored.');
                window.location.reload(); // Reload to refresh state
            } catch (err: any) {
                alert('Import failed: ' + err.message);
                setLoading(false);
            }
        };
        reader.readAsText(file);
    };

    if (authLoading || !isAdmin) return <div className="p-8 text-center">Loading...</div>;

    return (
        <div className="min-h-screen bg-gray-900 text-white p-6">
            <h1 className="text-3xl font-bold mb-6 bg-clip-text text-transparent bg-gradient-to-r from-blue-400 to-purple-500">
                Admin Dashboard
            </h1>

            {/* Tabs */}
            <div className="flex gap-4 mb-6 border-b border-gray-700">
                <button
                    onClick={() => setActiveTab('users')}
                    className={`pb-2 px-4 transition-colors ${activeTab === 'users' ? 'border-b-2 border-blue-500 text-blue-400' : 'text-gray-400 hover:text-white'}`}
                >
                    User Management
                </button>
                <button
                    onClick={() => setActiveTab('settings')}
                    className={`pb-2 px-4 transition-colors ${activeTab === 'settings' ? 'border-b-2 border-blue-500 text-blue-400' : 'text-gray-400 hover:text-white'}`}
                >
                    System Settings
                </button>
                <button
                    onClick={() => setActiveTab('data')}
                    className={`pb-2 px-4 transition-colors ${activeTab === 'data' ? 'border-b-2 border-blue-500 text-blue-400' : 'text-gray-400 hover:text-white'}`}
                >
                    Data Management
                </button>
            </div>

            {error && (
                <div className="bg-red-900/50 border border-red-500 text-red-200 p-4 rounded mb-6">
                    {error}
                </div>
            )}

            {/* Users Tab */}
            {activeTab === 'users' && (
                <div className="bg-gray-800 rounded-lg p-6 overflow-x-auto">
                    <table className="w-full text-left">
                        <thead className="text-gray-400 border-b border-gray-700">
                            <tr>
                                <th className="p-3">Username</th>
                                <th className="p-3">Email</th>
                                <th className="p-3">Role</th>
                                <th className="p-3">Active</th>
                                <th className="p-3">Last Login</th>
                                <th className="p-3">Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {users.map((u) => (
                                <tr key={u.id} className="border-b border-gray-700/50 hover:bg-gray-750">
                                    <td className="p-3 font-medium">{u.username}</td>
                                    <td className="p-3 text-gray-400">{u.email || '-'}</td>
                                    <td className="p-3">
                                        <span className={`px-2 py-1 rounded text-xs ${u.role === 'admin' ? 'bg-purple-900 text-purple-200' : 'bg-gray-700 text-gray-200'}`}>
                                            {u.role}
                                        </span>
                                    </td>
                                    <td className="p-3">
                                        <span className={`px-2 py-1 rounded text-xs ${u.is_active ? 'bg-green-900 text-green-200' : 'bg-red-900 text-red-200'}`}>
                                            {u.is_active ? 'Active' : 'Inactive'}
                                        </span>
                                    </td>
                                    <td className="p-3 text-sm text-gray-500">
                                        {u.last_login ? new Date(u.last_login).toLocaleDateString() : 'Never'}
                                    </td>
                                    <td className="p-3 flex gap-2">
                                        <button
                                            onClick={() => { setEditingUser(u); setEditForm({ role: u.role, is_active: u.is_active }); }}
                                            className="text-blue-400 hover:text-blue-300 px-2 py-1"
                                        >
                                            Edit
                                        </button>
                                        <button
                                            onClick={() => handleDeleteUser(u.id)}
                                            className="text-red-400 hover:text-red-300 px-2 py-1"
                                            disabled={u.id === user?.id}
                                        >
                                            Delete
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {/* Edit Modal */}
            {editingUser && (
                <div className="fixed inset-0 bg-black/50 flex items-center justify-center p-4">
                    <div className="bg-gray-800 rounded-lg p-6 w-full max-w-md">
                        <h3 className="text-xl font-bold mb-4">Edit User: {editingUser.username}</h3>
                        <form onSubmit={handleUserUpdate}>
                            <div className="mb-4">
                                <label className="block text-gray-400 mb-2">Role</label>
                                <select
                                    value={editForm.role}
                                    onChange={(e) => setEditForm({ ...editForm, role: e.target.value })}
                                    className="w-full bg-gray-700 rounded p-2 text-white"
                                >
                                    <option value="basic">Basic</option>
                                    <option value="admin">Admin</option>
                                </select>
                            </div>
                            <div className="mb-6">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={editForm.is_active}
                                        onChange={(e) => setEditForm({ ...editForm, is_active: e.target.checked })}
                                        className="form-checkbox h-5 w-5 text-blue-500"
                                    />
                                    <span>Active Account</span>
                                </label>
                            </div>
                            <div className="flex justify-end gap-3">
                                <button
                                    type="button"
                                    onClick={() => setEditingUser(null)}
                                    className="px-4 py-2 text-gray-400 hover:text-white"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded text-white"
                                >
                                    Save Changes
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Settings Tab */}
            {activeTab === 'settings' && settings && (
                <div className="bg-gray-800 rounded-lg p-6 max-w-2xl">
                    <h2 className="text-xl font-semibold mb-4">Registration Settings</h2>
                    <div className="flex items-center justify-between p-4 bg-gray-700/50 rounded-lg">
                        <div>
                            <h3 className="font-medium">User Registration</h3>
                            <p className="text-sm text-gray-400">Allow new users to sign up.</p>
                        </div>
                        <label className="relative inline-flex items-center cursor-pointer">
                            <input
                                type="checkbox"
                                className="sr-only peer"
                                checked={settings.registration_enabled}
                                onChange={(e) => setSettings({ ...settings, registration_enabled: e.target.checked })}
                            />
                            <div className="w-11 h-6 bg-gray-600 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-blue-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-blue-600"></div>
                        </label>
                    </div>
                    <div className="mt-6 flex justify-end">
                        <button
                            onClick={handleSettingsUpdate}
                            className="bg-blue-600 hover:bg-blue-700 text-white px-6 py-2 rounded-lg transition-colors"
                        >
                            Save Settings
                        </button>
                    </div>
                </div>
            )}

            {/* Data Tab */}
            {activeTab === 'data' && (
                <div className="grid md:grid-cols-2 gap-6">
                    <div className="bg-gray-800 rounded-lg p-6">
                        <h2 className="text-xl font-semibold mb-2">Export Data</h2>
                        <p className="text-gray-400 mb-6 text-sm">
                            Download a full JSON dump of the database (Users, Projects, Tasks, Chats).
                        </p>
                        <button
                            onClick={handleExport}
                            className="w-full bg-green-600 hover:bg-green-700 text-white px-4 py-3 rounded-lg flex items-center justify-center gap-2 transition-colors"
                        >
                            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
                            Download Database Dump
                        </button>
                    </div>

                    <div className="bg-gray-800 rounded-lg p-6">
                        <h2 className="text-xl font-semibold mb-2">Import Data</h2>
                        <p className="text-gray-400 mb-6 text-sm">
                            Restore from a JSON dump. <span className="text-red-400 font-bold">WARNING: This wipes all existing data!</span>
                        </p>
                        <div className="relative">
                            <input
                                type="file"
                                ref={fileInputRef}
                                onChange={handleImport}
                                accept="application/json"
                                className="hidden"
                            />
                            <button
                                onClick={() => fileInputRef.current?.click()}
                                className="w-full bg-gray-700 hover:bg-gray-600 text-white px-4 py-3 rounded-lg flex items-center justify-center gap-2 transition-colors border border-gray-600"
                            >
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"></path></svg>
                                Upload JSON & Restore
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
