'use client';

import { useState } from 'react';
import api from '../../utils/api';
import { useRouter } from 'next/navigation';

export default function DeleteAccountModal() {
    const router = useRouter();
    const [isOpen, setIsOpen] = useState(false);
    const [password, setPassword] = useState('');
    const [confirmText, setConfirmText] = useState('');
    const [isDeleting, setIsDeleting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [confirmations, setConfirmations] = useState({
        understand: false,
        irreversible: false,
        exportedData: false
    });

    const allConfirmed = confirmations.understand && confirmations.irreversible && confirmations.exportedData;
    const textMatches = confirmText === 'DELETE MY ACCOUNT';

    const handleDelete = async () => {
        if (!allConfirmed || !textMatches) return;

        setError(null);
        setIsDeleting(true);

        try {
            await api.deleteOwnAccount(password, true);

            // Clear local storage
            localStorage.removeItem('noslop_token');
            localStorage.removeItem('noslop_user');

            // Redirect to login
            router.push('/login?deleted=true');
        } catch (err: any) {
            setError(err.message || 'Account deletion failed');
            setIsDeleting(false);
        }
    };

    if (!isOpen) {
        return (
            <div className="bg-red-900/20 border border-red-700 rounded-lg p-6">
                <h3 className="text-xl font-semibold text-red-400 mb-2">Danger Zone</h3>
                <p className="text-gray-400 mb-4">
                    Once you delete your account, there is no going back. This action is permanent.
                </p>
                <button
                    onClick={() => setIsOpen(true)}
                    className="bg-red-600 hover:bg-red-700 text-white px-6 py-2 rounded font-medium transition-colors"
                >
                    Delete Account
                </button>
            </div>
        );
    }

    return (
        <div className="fixed inset-0 bg-black bg-opacity-75 flex items-center justify-center p-4 z-50">
            <div className="bg-gray-800 rounded-lg max-w-2xl w-full p-8 max-h-[90vh] overflow-y-auto">
                <div className="flex items-center gap-3 mb-6">
                    <div className="w-12 h-12 bg-red-600 rounded-full flex items-center justify-center">
                        <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                        </svg>
                    </div>
                    <div>
                        <h2 className="text-2xl font-bold text-red-400">Delete Account</h2>
                        <p className="text-gray-400 text-sm">This action cannot be undone</p>
                    </div>
                </div>

                {error && (
                    <div className="bg-red-900 text-red-200 p-4 rounded mb-6">
                        {error}
                    </div>
                )}

                <div className="space-y-6 mb-6">
                    <div className="bg-yellow-900/30 border border-yellow-700 rounded p-4">
                        <h3 className="font-semibold text-yellow-400 mb-2">⚠️  Warning: Permanent Data Loss</h3>
                        <p className="text-sm text-yellow-200">
                            Deleting your account will permanently remove:
                        </p>
                        <ul className="list-disc list-inside text-sm text-yellow-200 mt-2 space-y-1">
                            <li>Your profile and all personal information</li>
                            <li>All chat sessions and message history</li>
                            <li>All projects and tasks you've created</li>
                            <li>All custom settings and preferences</li>
                        </ul>
                    </div>

                    <div className="space-y-3">
                        <label className="flex items-start gap-3 cursor-pointer">
                            <input
                                type="checkbox"
                                checked={confirmations.understand}
                                onChange={(e) => setConfirmations({ ...confirmations, understand: e.target.checked })}
                                className="mt-1 w-4 h-4"
                            />
                            <span className="text-sm text-gray-300">
                                I understand that this action is permanent and cannot be undone
                            </span>
                        </label>

                        <label className="flex items-start gap-3 cursor-pointer">
                            <input
                                type="checkbox"
                                checked={confirmations.irreversible}
                                onChange={(e) => setConfirmations({ ...confirmations, irreversible: e.target.checked })}
                                className="mt-1 w-4 h-4"
                            />
                            <span className="text-sm text-gray-300">
                                I understand that all my data will be permanently deleted
                            </span>
                        </label>

                        <label className="flex items-start gap-3 cursor-pointer">
                            <input
                                type="checkbox"
                                checked={confirmations.exportedData}
                                onChange={(e) => setConfirmations({ ...confirmations, exportedData: e.target.checked })}
                                className="mt-1 w-4 h-4"
                            />
                            <span className="text-sm text-gray-300">
                                I have exported my data or don't need a backup
                            </span>
                        </label>
                    </div>

                    <div>
                        <label className="block text-gray-400 mb-2 text-sm">
                            Type <strong className="text-white">DELETE MY ACCOUNT</strong> to confirm
                        </label>
                        <input
                            type="text"
                            value={confirmText}
                            onChange={(e) => setConfirmText(e.target.value)}
                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-red-500 outline-none font-mono"
                            placeholder="DELETE MY ACCOUNT"
                        />
                    </div>

                    <div>
                        <label className="block text-gray-400 mb-2 text-sm">
                            Enter your password to confirm
                        </label>
                        <input
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-red-500 outline-none"
                            placeholder="Your password"
                        />
                    </div>
                </div>

                <div className="flex gap-3">
                    <button
                        onClick={() => {
                            setIsOpen(false);
                            setPassword('');
                            setConfirmText('');
                            setError(null);
                            setConfirmations({ understand: false, irreversible: false, exportedData: false });
                        }}
                        disabled={isDeleting}
                        className="flex-1 bg-gray-700 hover:bg-gray-600 text-white px-6 py-3 rounded font-medium transition-colors disabled:opacity-50"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={handleDelete}
                        disabled={!allConfirmed || !textMatches || !password || isDeleting}
                        className="flex-1 bg-red-600 hover:bg-red-700 text-white px-6 py-3 rounded font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {isDeleting ? 'Deleting Account...' : 'Delete My Account Forever'}
                    </button>
                </div>
            </div>
        </div>
    );
}
